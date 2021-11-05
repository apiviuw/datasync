package com.socrata.datasync.model;

import com.socrata.datasync.config.controlfile.ControlFile;
import com.socrata.datasync.config.controlfile.LocationColumn;
import com.socrata.datasync.config.controlfile.SyntheticPointColumn;
import com.socrata.datasync.job.JobStatus;
import com.socrata.datasync.validation.IntegrationJobValidity;
import com.socrata.datasync.Utils;
import com.socrata.model.importer.Column;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import info.debatty.java.stringsimilarity.Levenshtein;
import info.debatty.java.stringsimilarity.WeightedLevenshtein;
import info.debatty.java.stringsimilarity.CharacterInsDelInterface;
import info.debatty.java.stringsimilarity.CharacterSubstitutionInterface;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 *
 * This class maintains a model for the DataSync control file editor.  The model provides
 * a facility through which the UI can map each column in the CSV to a column in the dataset,
 * either by assigning the dataset column name to an the array whose items match the order in which
 * the columns exist in the CSV, or by adding the dataset column name to the ignored columns list.
 *
 * The model takes care of generating columns names from the CSV. All updates are then based on the index of the
 * values in the CSV. Consumers update the model by providing the dataset field name, and the index of the column in the CSV
 * from which the data should be pulled.  For example, if I have a dataset "car_registration" whose fields are
 * "make,model,year" and a CSV whose values are "Dodge,Durango,2000," and the customer wants to map "make" to the first
 * column in the CSV, then they will simply call "updateColumnAtPosition" passing "make" and the index "0".  The model
 * will ensure the control file is updated appropriately.  Note that we are assuming a fixed set of columns throughout.
 * Any changes to the shape of the original columns array should invalidate the rest of the model.
 *
 * The model also provides a facility for validation, leveraging the existing IntegrationJob
 * validation checks.
 *
 * Created by franklinwilliams
 */

public class ControlFileModel extends Observable {

    private ControlFile controlFile;
    private CSVModel csvModel;
    private DatasetModel datasetModel;
    private String path;

    public ControlFileModel (ControlFile file, DatasetModel dataset, boolean isPostNbeification) throws IOException{
        controlFile = file;
        if (controlFile.getFileTypeControl().hasHeaderRow)
            controlFile.getFileTypeControl().skip = 1;
        csvModel = new CSVModel(file);
        this.datasetModel = dataset;

        // Check to see if the ControlFile already has initialized columns.
        // If it doesn't, then initialize the columns list with dummy names. Note that we use dummy names here
        // instead of names from the CSV since there is no guarantee that the names in the CSV will be valid.  They
        // could be duplicates, as well as empty strings.
        if (!file.getFileTypeControl().hasColumns()){
            initializeColumns();
            // Now attempt to match those in the dataset to those in the CSV
            matchColumns();
        } else if(!isPostNbeification) {
            matchColumns();
        }
    }

    //This will be called anytime that we think the shape of the dataset has changed underneath us.
    private void initializeColumns(){
        if (controlFile.getFileTypeControl().columns != null) {
            // un-ignore all the columns we're about to wipe out
            removeIgnoredColumns(controlFile.getFileTypeControl().columns);
        }

        controlFile.getFileTypeControl().columns = new String[csvModel.getColumnCount()];

        for (int i = 0; i < getColumnCount(); i++){
            controlFile.getFileTypeControl().columns[i] = ModelUtils.generatePlaceholderName(i);
        }

        // Now ignore all of the columns by default (this is another loop to avoid the callbacks resulting in out of bounds
        // exceptions before the array is fully initialized
        for (int i = 0; i < getColumnCount(); i++){
            ignoreColumnInCSVAtPosition(i);
        }
    }

    public DatasetModel getDatasetModel(){
        return datasetModel;
    }

    public ControlFile getControlFile(){
        return controlFile;
    }

    private static final CharacterSubstitutionInterface insDel =
        new CharacterSubstitutionInterface() {
            public double cost(char c1, char c2) {
                // Where we minify the cost of a change, we return
                // something non-zero because we don't want to ignore
                // it entirely, because we want to prefer solutions
                // with a minimal number of even expected changes.
                if(!Character.isAlphabetic(c1) && !Character.isDigit(c1) && c2 == '_') return 0.1;
                if(Character.isUpperCase(c1) && Character.isLowerCase(c2)) return 0.1;
                return 1.0;
            }
        };

    private static final CharacterInsDelInterface subst =
        new CharacterInsDelInterface() {
            public double deletionCost(char c) {
                if(Character.isAlphabetic(c) || Character.isDigit(c) || c == '_') {
                    return 1.0;
                }
                return 0.1;
            }
            public double insertionCost(char c) {
                return 1.0;
            }
        };

    private static final WeightedLevenshtein csvToFieldName = new WeightedLevenshtein(insDel, subst);

    private static final CharacterSubstitutionInterface penalizeChanges =
        new CharacterSubstitutionInterface() {
            public double cost(char c1, char c2) {
                if(c1 == '_' && !Character.isAlphabetic(c2) && !Character.isDigit(c2)) return 0;
                return 1.0;
            }
        };

    private static final CharacterInsDelInterface penalizeDeletions =
        new CharacterInsDelInterface() {
            public double deletionCost(char c) {
                return 1.0;
            }
            public double insertionCost(char c) {
                return 0;
            }
        };

    private static final WeightedLevenshtein fieldNameToCsv = new WeightedLevenshtein(penalizeChanges, penalizeDeletions);

    public static double fieldNameEditDistance(String csvHeader, String fieldName) {
        double editDistance = csvToFieldName.distance(csvHeader, fieldName);
        // ok, if we stop and produce a solution here, we'll get
        // badly confused if the CSV has extra columns in it
        // (because if we encounter one such early, we'll
        // basically pick a random existing column to pair it
        // with).  So instead we'll look at the edit distance from
        // dataset column to the CSV field name, heavily
        // penalizing deletions.  If the result is a significant
        // fraction of the size of the dataset column name, we'll
        // consider things unmatched.
        if(fieldNameToCsv.distance(fieldName, csvHeader) < fieldName.length() * 0.25) {
            return editDistance;
        } else {
            // Nope; too many deletions / unexpected changes in the
            // field name -> csv name direction, reject this
            // possibility.
            return Double.POSITIVE_INFINITY;
        }
    }

    private static final Levenshtein csvToHumanName = new Levenshtein();

    public static double humanNameEditDistance(String csvHeader, String humanName) {
        double editDistance = csvToHumanName.distance(csvHeader, humanName);
        if(editDistance < Math.max(csvHeader.length(), humanName.length()) * 0.25) {
            return editDistance;
        } else {
            // too much change to the human name; just assume they're different
            return Double.POSITIVE_INFINITY;
        }
    }

    private static class Guess implements Comparable<Guess> {
        public final Column column;
        public final double badness;
        public final int index;

        public Guess(Column column, double badness, int index) {
            this.column = column;
            this.badness = badness;
            this.index = index;
        }

        public int compareTo(Guess that) {
            int badnessOrd = Double.compare(this.badness, that.badness);
            if(badnessOrd == 0) return Integer.compare(this.index, that.index);
            else return badnessOrd;
        }

        @Override public boolean equals(Object that) {
            if(that instanceof Guess) return compareTo((Guess) that) == 0;
            return false;
        }
    }

    private static class Candidate implements Comparable<Candidate> {
        public final int sourceColumnIndex;
        public final PriorityQueue<Guess> preferences;

        public Candidate(int sourceColumnIndex, PriorityQueue<Guess> preferences) {
            this.sourceColumnIndex = sourceColumnIndex;
            this.preferences = preferences;
        }

        public int compareTo(Candidate that) {
            boolean thisIsInfinitelyBad = this.preferences.isEmpty() || this.preferences.peek().badness == Double.POSITIVE_INFINITY;
            boolean thatIsInfinitelyBad = that.preferences.isEmpty() || that.preferences.peek().badness == Double.POSITIVE_INFINITY;

            if(thisIsInfinitelyBad) {
                if(thatIsInfinitelyBad) {
                    return Integer.compare(this.sourceColumnIndex, that.sourceColumnIndex);
                } else {
                    return 1; // that is not infinitely bad, so put it first
                }
            } else {
                if(thatIsInfinitelyBad) {
                    return -1;
                } else {
                    int badnessOrd = Double.compare(this.preferences.peek().badness, that.preferences.peek().badness);
                    if(badnessOrd == 0) return Integer.compare(this.sourceColumnIndex, that.sourceColumnIndex);
                    else return badnessOrd;
                }
            }
        }

        @Override public boolean equals(Object that) {
            if(that instanceof Candidate) return compareTo((Candidate) that) == 0;
            return false;
        }
    }

    private static int bestGuess(SortedMap<Integer, PriorityQueue<Guess>> allPreferences) {
        // Returns the leftmost column with the best (== lowest)
        // badness at the head.  Precondition: allPreferences is
        // non-empty.
        int best = -1;
        double bestBadness = Double.POSITIVE_INFINITY;
        for(Map.Entry<Integer, PriorityQueue<Guess>> ent : allPreferences.entrySet()) {
            if(best == -1) {
                best = ent.getKey();
                if(ent.getValue().isEmpty()) bestBadness = Double.POSITIVE_INFINITY;
                else bestBadness = ent.getValue().peek().badness;
            } else if(ent.getValue().isEmpty()) {
                // ok, we're definitely not better than our best
                // guess.
            } else if(ent.getValue().peek().badness < bestBadness) {
                best = ent.getKey();
                bestBadness = ent.getValue().peek().badness;
            }
            if(bestBadness == 0) return best; // short circuit: we're not going to get any better
        }
        return best;
    }

    static final boolean DEBUG = false;
    private static void debug(Object... items) {
        if(DEBUG) {
            StringBuilder sb = new StringBuilder();
            for(Object item : items) {
                sb.append(item);
            }
            System.out.println(sb.toString());
        }
    }

    private void matchColumns() {
        debug("Starting match columns");

        List<String> columnNames = new ArrayList<>();
        for(Column column : datasetModel.getColumns()) {
            columnNames.add(column.getName());
        }

        PriorityQueue<Candidate> candidates = new PriorityQueue<>();

        for (int i = 0; i < csvModel.getColumnCount(); i++) {
            String csvHeader = csvModel.getColumnName(i);
            debug("Looking at CSV column: ", csvHeader);

            PriorityQueue<Guess> preferences = new PriorityQueue<>();
            int idx = 0;
            for(Column column : datasetModel.getColumns()) {
                double editDistanceToName = humanNameEditDistance(csvHeader, column.getName());
                double editDistanceToFieldName = fieldNameEditDistance(csvHeader, column.getFieldName());
                double badness = Math.min(editDistanceToName, editDistanceToFieldName);
                debug("  Badness to ", column.getName(), " (", column.getFieldName(), ") : ", badness);
                preferences.add(new Guess(column, badness, idx));
                idx += 1;
            }

            candidates.add(new Candidate(i, preferences));
        }

        Set<String> usedFieldNames = new HashSet<>();
        while(!candidates.isEmpty()) {
            Candidate candidate = candidates.poll();
            debug("Leftmost csv column with the least bad preference is ", csvModel.getColumnName(candidate.sourceColumnIndex));
            PriorityQueue<Guess> preferences = candidate.preferences;
            if(preferences.isEmpty()) {
                debug("  It has no preferences.  Bailing.");
                ignoreColumnInCSVAtPosition(candidate.sourceColumnIndex);
                continue;
            }

            Guess guess = preferences.poll();
            if(guess.badness == Double.POSITIVE_INFINITY) {
                debug("  Its most-prefered choice is infinitely bad.  Bailing.");
                ignoreColumnInCSVAtPosition(candidate.sourceColumnIndex);
                continue;
            }

            if(usedFieldNames.contains(guess.column.getFieldName())) {
                // Its best choice is something we've already
                // selected; put the candidate back in the pool, minus
                // that guess because mutability, and try again.
                debug("  Its most-prefered choice (", guess.column.getFieldName(), ") has already been picked.  Removing that option and trying again.");
                candidates.add(candidate);
                continue;
            }

            Column col = guess.column;
            debug("  That best matches column ", col.getName(), " (", col.getFieldName(), ") with badness ", guess.badness);
            updateColumnAtPosition(col.getFieldName(), candidate.sourceColumnIndex);
            usedFieldNames.add(col.getFieldName());
        }
    }

    private void removeIgnoredColumns(String[] columnsToRemove) {
        for (String column : columnsToRemove) {
            removeIgnoredColumn(column);
        }
    }

    private void removeIgnoredColumn(String columnName){
        String[] ignoredColumns = controlFile.getFileTypeControl().ignoreColumns;
        if (ignoredColumns != null) {
            ArrayList<String> newColumns = new ArrayList<String>();
            for (String c : ignoredColumns) {
                if (!columnName.equals(c))
                    newColumns.add(c);
            }
            controlFile.getFileTypeControl().ignoreColumns(newColumns.toArray(new String[newColumns.size()]));
        }
    }

    public int getColumnCount(){
        return controlFile.getFileTypeControl().columns.length;
    }

    public void ignoreColumnFromDataset(Column column){
        String fieldName = column.getFieldName();
        ArrayList<String> newColumns = new ArrayList<String>(Arrays.asList(controlFile.getFileTypeControl().ignoreColumns));
        newColumns.add(fieldName);
        controlFile.getFileTypeControl().ignoreColumns(newColumns.toArray(new String[newColumns.size()]));
        updateListeners();
    }

    public void ignoreColumnInCSVAtPosition(int index){
        if (index >=  getColumnCount())
            throw new IllegalStateException("Cannot update field outside of the CSV");
        String columnName = getColumnAtPosition(index);
        ArrayList<String> newColumns = new ArrayList<String>(Arrays.asList(controlFile.getFileTypeControl().ignoreColumns));
        if (!newColumns.contains(columnName))
            newColumns.add(columnName);
        controlFile.getFileTypeControl().ignoreColumns(newColumns.toArray(new String[newColumns.size()]));
        updateListeners();
    }

    public boolean isIgnored(String fieldName){
        String[] ignoredColumns = controlFile.getFileTypeControl().ignoreColumns;
        if (ignoredColumns != null) {
            for (String column : ignoredColumns) {
                if (column.equals(fieldName))
                    return true;
            }
        }
        return false;
    }

    public int getIndexOfColumnName(String fieldName){
        String [] columns = controlFile.getFileTypeControl().columns;
        for (int i = 0; i < columns.length; i++){
            if (columns[i].equalsIgnoreCase(fieldName))
                return i;
        }
        return -1;
    }

    public void updateColumnAtPosition(String datasetFieldName, int position){
        if (position >  getColumnCount())
            throw new IllegalStateException("Cannot update field outside of the CSV");
        int index = getIndexOfColumnName(datasetFieldName);
        // The same column cannot be mapped twice.  If the column is already mapped, set the mapped version to be ignored
        if (index != -1 && index != position){
            controlFile.getFileTypeControl().columns[index] = ModelUtils.generatePlaceholderName(index);
            ignoreColumnInCSVAtPosition(index);
        }

        removeIgnoredColumn(getColumnAtPosition(position));
        removeSyntheticColumn(datasetFieldName);
        controlFile.getFileTypeControl().columns[position] = datasetFieldName;

        updateListeners();
    }

    public void removeSyntheticColumn(String fieldName){
        if (controlFile.getFileTypeControl().syntheticLocations != null)
            controlFile.getFileTypeControl().syntheticLocations.remove(fieldName);

        updateListeners();
    }

    public String getColumnAtPosition(int i){
        if (i >= getColumnCount())
            throw new IllegalStateException("Cannot update field outside of the CSV");
        return controlFile.getFileTypeControl().columns[i];
    }

    public CSVModel getCsvModel() {
        return csvModel;
    }

    public void setEmptyTextIsNull(boolean isNull){
        controlFile.getFileTypeControl().emptyTextIsNull(isNull);
        updateListeners();
    }

    public void updateListeners(){
        //TODO: Should we really be swallowing this exception from here?  Given the current way it's factored, I think
        // that we will need to...
        try {
            csvModel.updateTable(controlFile);
        }
        catch (IOException e){
            System.out.println(e.getStackTrace());
        }
        setChanged();
        notifyObservers();
    }

    public void setSeparator(String sep){
        controlFile.getFileTypeControl().separator(sep);

        try {
            // update our understanding of the CSV, given the new separator
            csvModel.updateTable(controlFile);
        }
        catch (IOException e){
            System.out.println(e.getStackTrace());
        }

        // update the controlfile's idea of the columns, now that the CSVModel has changed
        initializeColumns();
        matchColumns();
        updateListeners();
    }

    public void setRowsToSkip(int rowsToSkip){
        controlFile.getFileTypeControl().skip(rowsToSkip);
        updateListeners();
    }

    public void setEncoding(String encoding){
        controlFile.getFileTypeControl().encoding(encoding);
        updateListeners();
    }

    public void setQuote(String quote){
        controlFile.getFileTypeControl().quote(quote);
        updateListeners();
    }

    public void setType(String type){
        controlFile.action = type;
        updateListeners();
    }

    public void setTrimWhiteSpace(boolean trim){
        controlFile.getFileTypeControl().trimWhitespace(trim);
        updateListeners();
    }

    public void setUseSocrataGeocoding(boolean useSocrataGeocoding){
        controlFile.getFileTypeControl().useSocrataGeocoding(useSocrataGeocoding);
        updateListeners();
    }

    public void setEscape(String escape){
        controlFile.getFileTypeControl().escape(escape);
        updateListeners();
    }

    public void setHasHeaderRow(boolean headerRow){
        boolean existingValue = controlFile.getFileTypeControl().hasHeaderRow;
        if (headerRow && !existingValue)
            controlFile.getFileTypeControl().skip(controlFile.getFileTypeControl().skip + 1);
        if (!headerRow && existingValue)
            controlFile.getFileTypeControl().skip(controlFile.getFileTypeControl().skip - 1);
        controlFile.getFileTypeControl().hasHeaderRow(headerRow);
        updateListeners();
    }

    public void setSocrataGeocoding(boolean socrataGeocoding){
       controlFile.getFileTypeControl().useSocrataGeocoding(socrataGeocoding);
        updateListeners();
    }

    // Returns the friendliest possible name - The CSV if it exists, the dummy name if it doesn't
    // Intended only for display.  If you attempt to use this for indexing, you're likely going to break things
    // as everything is done off of the index
    public String getDisplayName(int i){
        if (controlFile.getFileTypeControl().hasHeaderRow)
            return getCsvModel().getColumnName(i);
        else
            return getColumnAtPosition(i);
    }

    public String getFloatingDateTime(){
        return Utils.commaJoin(controlFile.getFileTypeControl().floatingTimestampFormat);
    }

    public String getTimezone(){
        return controlFile.getFileTypeControl().timezone;
     }

    public void setFixedDateTime(String fixed){
        String[] newDateTime = Utils.commaSplit(fixed);
        controlFile.getFileTypeControl().fixedTimestampFormat(newDateTime);
        updateListeners();
    }

    public void setFloatingDateTime(String floating){
        String[] newDateTime = Utils.commaSplit(floating);
        controlFile.getFileTypeControl().floatingTimestampFormat(newDateTime);
        updateListeners();
    }

    public void setTimezone(String timezone){
        controlFile.getFileTypeControl().timezone(timezone);
        updateListeners();
    }

    public void setSetAsideErrors(Boolean setAsideErrors){
        controlFile.getFileTypeControl().setAsideErrors(setAsideErrors);
        updateListeners();
    }

    public void setSyntheticLocation(String fieldName, LocationColumn locationField) {
        Map<String, LocationColumn> columnsMap = controlFile.getFileTypeControl().syntheticLocations;
        if (columnsMap != null) {
            controlFile.getFileTypeControl().syntheticLocations.put(fieldName, locationField);
        } else {
            TreeMap<String, LocationColumn> map = new TreeMap<>();
            map.put(fieldName, locationField);
            controlFile.getFileTypeControl().syntheticLocations = map;
        }

        syncSynth(fieldName);
    }

    public void setSyntheticPoint(String fieldName, SyntheticPointColumn locationField) {
        Map<String, SyntheticPointColumn> columnsMap = controlFile.getFileTypeControl().syntheticPoints;
        if (columnsMap != null) {
            controlFile.getFileTypeControl().syntheticPoints.put(fieldName, locationField);
        } else {
            TreeMap<String, SyntheticPointColumn> map = new TreeMap<>();
            map.put(fieldName, locationField);
            controlFile.getFileTypeControl().syntheticPoints = map;
        }

        syncSynth(fieldName);
    }

    private void syncSynth(String fieldName) {
        //Reset the location column
        int locationIndex = getIndexOfColumnName(fieldName);
        if (locationIndex != -1) {
            ignoreColumnInCSVAtPosition(locationIndex);
        }

        updateListeners();
    }

    //Return the empty set if there are no synthetic locaitons
    public Map<String, LocationColumn> getSyntheticLocations(){
        Map<String, LocationColumn> locations = controlFile.getFileTypeControl().syntheticLocations;
        if (locations == null)
            locations = new TreeMap<>();
        return locations;
    }

    public Map<String, SyntheticPointColumn> getSyntheticPoints() {
        Map<String, SyntheticPointColumn> points = controlFile.getFileTypeControl().syntheticPoints;
        if (points == null)
            points = new TreeMap<>();
        return points;
    }

    public ArrayList<Column> getUnmappedDatasetColumns(){
        ArrayList<Column> unmappedColumns = new ArrayList<Column>();
        for (Column datasetColumn : datasetModel.getColumns()){
            // If the column doesn't exist in the mapped columns list, and it hasn't already been explicitly ignored
            // then we should add it to the list of unmapped columns
            String fieldName = datasetColumn.getFieldName();
            if (getIndexOfColumnName(fieldName) == -1
                    && !isIgnored(fieldName))
                unmappedColumns.add(datasetColumn);
        }
        return unmappedColumns;
    }

    public String getControlFileContents()  {
        ObjectMapper mapper = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true);
        try {
            return mapper.writeValueAsString(controlFile);
        } catch (IOException e) {
            return null;
        }
    }

    //Get the path from which this control file was loaded.
    public String getPath(){
        return path;
    }


    public JobStatus validate(){
        if (!rowsContainSameNumberOfColumns())
            return JobStatus.ROWS_DO_NOT_CONTAIN_SAME_NUMBER_OF_COLUMNS;

        JobStatus status = IntegrationJobValidity.checkControl(controlFile,controlFile.getFileTypeControl(),datasetModel.getDatasetInfo(),new File(controlFile.getFileTypeControl().filePath),datasetModel.getDomain());
        if (status.isError()){
            return status;
        }
        else
            return checkDateTime();
    }

    //Sample the rows in the CSV and attempt to parse the columns that represent dates.
    private JobStatus checkDateTime(){
        for (int i = 0; i < csvModel.getRowCount(); i++){
            for (int j = 0; j < controlFile.getFileTypeControl().columns.length; j++) {
                String columnName = controlFile.getFileTypeControl().columns[j];
                Column c = datasetModel.getColumnByFieldName(columnName);

                if (c != null) {
                    String fieldType = c.getDataTypeName();
                    if (fieldType.equals("calendar_date") ||
                            fieldType.equals("date")) {
                        Object value = csvModel.getValueAt(i, j);
                        if (!canParseDateTime(value, controlFile.getFileTypeControl().floatingTimestampFormat)) {
                            JobStatus status = JobStatus.INVALID_DATETIME;
                            status.setMessage("Cannot parse the datetime value \"" +value.toString()+  "\" in column \"" + columnName+ "\" given the current formatting.  Please check your formatting values under advanced options and try again.");
                            return status;
                        }
                    }
                }
            }
        }
        return JobStatus.VALID;
    }

    //Blanks and nulls are included as parseable
    private boolean canParseDateTime(Object value, String[] dateTimeFormats){
        if (value == null || value.toString().isEmpty())
            return true;

        for (String format : dateTimeFormats) {
            try {
                if (format.startsWith("ISO"))
                    ISODateTimeFormat.dateTime().parseDateTime((String) value);
                else {
                    DateTimeFormatter dateStringFormat = DateTimeFormat.forPattern(format);
                    dateStringFormat.parseDateTime((String) value);
                }
                //If we make it here, then we know that we've been able to parse the value
                return true;
            } catch (Exception e) {
                continue;
            }
        }
        return false;
    }

    public boolean rowsContainSameNumberOfColumns()
    {
        int columnLength = csvModel.getColumnCount();
        for (int i = 0; i < csvModel.getRowCount(); i++){
            if (columnLength != csvModel.getRowSize(i))
                return false;
        }
        return true;
    }

}
