package com.socrata.datasync;

import com.google.common.net.HttpHeaders;
import com.socrata.datasync.config.userpreferences.UserPreferences;
import org.apache.commons.net.util.Base64;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import static org.apache.http.conn.ssl.SSLConnectionSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER;

public class HttpUtility {

    private CloseableHttpClient httpClient = null;
    private RequestConfig proxyConfig = null;
    private RequestConfig noProxyConfig = null;
    private String authHeader;
    private String appToken;
    private boolean authRequired = false;
    private final int maxRetries;
    private final double retryDelayFactor;

    private static final String datasyncVersionHeader = "X-Socrata-DataSync-Version";
    private static final String appHeader = "X-App-Token";
    private static String userAgent = "datasync";


    public HttpUtility() { this(null, false); }


    public HttpUtility(UserPreferences userPrefs, boolean useAuth) {
        this(userPrefs, useAuth, 5, 3.5);
    }

    public HttpUtility(UserPreferences userPrefs, boolean useAuth, String usrAgent) {
        this(userPrefs, useAuth);
        userAgent = usrAgent;
    }

    public HttpUtility(UserPreferences userPrefs, boolean useAuth, int maxRetries, double retryDelayFactor) {
        this.maxRetries = maxRetries;
        this.retryDelayFactor = retryDelayFactor;

        HttpClientBuilder clientBuilder = HttpClients.custom();
        if (useAuth) {
            authHeader = getAuthHeader(userPrefs.getUsername(), userPrefs.getPassword());
            appToken = userPrefs.getConnectionInfo().getToken();
        }
        authRequired = useAuth;

        noProxyConfig = RequestConfig.custom().
            setConnectTimeout(15000). // 15s
            setSocketTimeout(60000). // 1m
            build();

        if(userPrefs != null) {
            String proxyHost = userPrefs.getProxyHost();
            String proxyPort = userPrefs.getProxyPort();
            if (canUse(proxyHost) && canUse(proxyPort)) {
                HttpHost proxy = new HttpHost(proxyHost, Integer.valueOf(proxyPort));
                proxyConfig = RequestConfig.copy(noProxyConfig).setProxy(proxy).build();
                if (canUse(userPrefs.getProxyUsername()) && canUse(userPrefs.getProxyPassword())) {
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(
                        new AuthScope(proxyHost, Integer.valueOf(proxyPort)),
                        new UsernamePasswordCredentials(userPrefs.getProxyUsername(), userPrefs.getProxyPassword()));
                    clientBuilder.setDefaultCredentialsProvider(credsProvider);
                }
            }
        }

        SSLContext sslContext;
        try {
            sslContext = SSLContexts.custom().useTLS().build();
        } catch (NoSuchAlgorithmException|KeyManagementException e) {
            // there’s no way for the client to recover,
            // so a checked exception is not necessary
            throw new RuntimeException(e);
        }

        SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(
            sslContext,
            new String[] { "TLSv1.1", "TLSv1.2" },
            null,
            BROWSER_COMPATIBLE_HOSTNAME_VERIFIER
        );

        httpClient = clientBuilder.
            setSSLSocketFactory(factory).
            setRetryHandler(datasyncDefaultHandler).
            setKeepAliveStrategy(datasyncDefaultKeepAliveStrategy).
            setDefaultRequestConfig(noProxyConfig).
            build();
    }

    /**
     * Conducts a basic get, passing the auth information in the header.
     * @param uri the uri from which the get will be made
     * @param contentType the expected contentType of the return value
     * @return the unproccessed query results
     */
    public CloseableHttpResponse get(URI uri, String contentType) throws IOException {
        HttpGet httpGet = buildHttpGet(uri,contentType);
        return httpClient.execute(httpGet);
    }

    public <T> T get(URI uri, String contentType, ResponseHandler<T> handler) throws IOException {
        HttpGet httpGet = buildHttpGet(uri,contentType);
        return httpClient.execute(httpGet,handler);
    }

    private HttpGet buildHttpGet(URI uri, String contentType){
        HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader(HttpHeaders.USER_AGENT, userAgent);
        httpGet.addHeader(HttpHeaders.ACCEPT, contentType);
        httpGet.addHeader(datasyncVersionHeader, VersionProvider.getThisVersion());
        if (proxyConfig != null)
            httpGet.setConfig(proxyConfig);
        if (authRequired) {
            httpGet.setHeader(appHeader, appToken);
            httpGet.setHeader(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return httpGet;
    }

    /**
     * Conducts a basic post of the given entity; auth information is passed in the header.
     * @param uri  the uri to which the entity will be posted
     * @param entity an entity to post
     * @return the unprocessed results of the post
     */
    public CloseableHttpResponse post(URI uri, HttpEntity entity) throws IOException {
        return doPost(uri, entity, null);
    }

    public CloseableHttpResponse post(URI uri, HttpEntity entity, int timeoutMS) throws IOException {
        return doPost(uri, entity, timeoutMS);
    }

    private CloseableHttpResponse doPost(URI uri, HttpEntity entity, Integer timeoutMS) throws IOException {
        HttpPost httpPost = new HttpPost(uri);
        httpPost.setHeader(HttpHeaders.USER_AGENT, userAgent);
        httpPost.setHeader(entity.getContentType());
        httpPost.addHeader(datasyncVersionHeader, VersionProvider.getThisVersion());
        httpPost.setEntity(entity);

        RequestConfig baseRequestConfig;

        if (proxyConfig == null) {
            baseRequestConfig = noProxyConfig;
        } else {
            baseRequestConfig = proxyConfig;
        }
        httpPost.setConfig(addTimeout(RequestConfig.copy(baseRequestConfig), timeoutMS).build());

        if (authRequired) {
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, authHeader);
            httpPost.setHeader(appHeader, appToken);
        }
        return httpClient.execute(httpPost);
    }

    private static RequestConfig.Builder addTimeout(RequestConfig.Builder base, Integer timeoutMS) {
        if(timeoutMS == null) {
            return base;
        } else {
            return base.
                setConnectTimeout(timeoutMS).
                setSocketTimeout(timeoutMS);
        }
    }

    public void close() throws IOException {
        httpClient.close();
    }

    private String getAuthHeader(String username, String password) {
        String auth = username + ":" + password;
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(Charset.forName("US-ASCII")));
        return "Basic " + new String(encodedAuth);
    }

    private boolean canUse(String option) {
        return option != null && !option.isEmpty();
    }

    HttpRequestRetryHandler datasyncDefaultHandler = new HttpRequestRetryHandler() {

        public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
            // Do not retry if over max retry count
            if (executionCount >= maxRetries) {
                return false;
            }

            // Do not retry calls to the github api
            HttpClientContext clientContext = HttpClientContext.adapt(context);
            HttpRequest request = clientContext.getRequest();
            for (Header header : request.getHeaders("Host")) {
                if (header.getValue().contains("github"))
                    return false;
            }

            // Do not retry calls that are not idempotent - posts in our case
            // currently, we make 2 types of posts:
            //  1) posting blobs - this is idempotent
            //  2) posting commit of blob ids - this is not idempotent and we need to fall back to the logic
            //     in DeltaImporter2Publisher.commitBlobPostings
            boolean idempotent = !(request.getRequestLine().getUri().contains("commit"));
            if (idempotent) { // Retry if the request is considered idempotent
                double wait = Math.pow(retryDelayFactor, executionCount);
                System.err.println("Request failed. Retrying request in " + Math.round(wait) + " seconds");
                try {
                    Thread.sleep((long) wait*1000);
                    return true;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return false;
                }
            }
            return false;
        }

    };

    ConnectionKeepAliveStrategy datasyncDefaultKeepAliveStrategy = new ConnectionKeepAliveStrategy() {
        @Override
        public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
            return 30 * 1000;
        }
    };
}
