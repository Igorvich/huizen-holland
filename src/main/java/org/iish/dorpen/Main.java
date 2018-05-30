package org.iish.dorpen;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.text.*;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static final TreeMap<String, Record> records = new TreeMap<>(); // Contains the records from the csv, String is the id of the record.
    private static Set<SquareKilometreRecord> squareKilometreRecords = new TreeSet<>();
    private static final Map<String, Set<String>> codesToIds = new HashMap<>(); // Contains information about link codes belonging to the ids
    private static final Map<String, Set<String>> codeHierarchy = new TreeMap<>(); // Contains information about possible children of parents
    private static final List<String> codes = new ArrayList<>(); // Contains all the codes from the csv file
    private static BigDecimal numberOfHouses = new BigDecimal(0);
    private static int record_id_counter = 2;
    private static Set<String> recordsToRemove = new TreeSet<>();
    private static final Map<String, Record> recordsToAdd = new HashMap<>();
    private static final Set<Integer> years_from_data = new TreeSet<>();
    private static final SortedMap<String, VillageComplex> dorpenCollected = new TreeMap<>();
    private static int number_of_records_with_multiple_links = 0;

    private static final CSVFormat csvFormat = CSVFormat.EXCEL
            .withFirstRecordAsHeader()
            .withDelimiter(';')
            .withIgnoreEmptyLines()
            .withNullString("");

    public enum NoteState {
        SOURCE,
        YEAR,
        YEAR_SOURCE,
        YEAR_SURFACE,
        SURFACE,
        COMBINATION;

        public String getState(int year) {
            switch (this) {
                case SOURCE:
                    return "Bron";
                case YEAR:
                    return "Jaar " + Integer.toString(year);
                case YEAR_SOURCE:
                    return "Jaar " + Integer.toString(year) + ": Bron";
                case YEAR_SURFACE:
                    return "Jaar " + Integer.toString(year) + ": Oppervlakte";
                case SURFACE:
                    return "Oppervlakte";
                case COMBINATION:
                    return Integer.toString(year) + " + Oppervlakte";
                default:
                    return "Onbekend";
            }
        }
    }

    /**
     * The main method to start it all
     *
     * @param args String[] containing the file paths to load and the file path to write to
     * @throws Exception Exception for when the data is not valid to run the code
     */
    public static void main(String[] args) throws Exception {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        System.out.println(dateFormat.format(date)); //2016/11/16 12:08:43

        String importCsv = args[0];
        String importSquareKilometres = args[1];
        String exportCsv = args[2];
        String notesCsv = args[3];

        loadData(importCsv);
        loadSquareKilometers(importSquareKilometres);

        updateLinks();

        preSplitTheRecords();

        export(exportCsv, notesCsv);

        date = new Date();
        System.out.println(dateFormat.format(date)); //2016/11/16 12:08:43
    }

    /**
     * Converts the number given to a local BigDecimal
     *
     * @param number String The number to convert to the local format
     * @return BigDecimal The BigDecimal converted to the local format
     */
    private static BigDecimal convertToLocale(String number) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator(',');

        DecimalFormat df = new DecimalFormat("########,###", symbols);
        df.setGroupingUsed(false);
        return new BigDecimal(df.format(new BigDecimal(number).setScale(3, BigDecimal.ROUND_HALF_EVEN)));
    }

    /**
     * Loads the data from the csv file
     *
     * @param csvPath String The path to the CSV file which contains the data to be processed
     * @throws Exception Exception thrown when the data is incorrect
     */
    private static void loadData(String csvPath) throws Exception {
        CSVParser parser = CSVParser.parse(new File(csvPath), Charset.forName("UTF-8"), csvFormat);
        parser.forEach(record -> {
            Record newRecord = new Record();
            newRecord.id = Integer.toString(record_id_counter);
            newRecord.year = new Integer(record.get("YEAR"));
            newRecord.houses = record.get("HOUSES") != null ? new BigDecimal(record.get("HOUSES")) : null;
            numberOfHouses = newRecord.houses != null ? numberOfHouses.add(newRecord.houses) : numberOfHouses.add(new BigDecimal(0));
            newRecord.km2 = record.get("KM2") != null ? convertToLocale(record.get("KM2")).setScale(3, BigDecimal.ROUND_HALF_EVEN) : null;
            newRecord.note = NoteState.SOURCE;
            if (record.get("LINK") != null) {
                String[] links = record.get("LINK").split("-");
                for (String code : links) {
                    if (code.substring(0, 2).contains("HO")) {
                        newRecord.links.add(code);
                        Set<String> ids = codesToIds.getOrDefault(code, new HashSet<>());
                        ids.add(newRecord.id);
                        codesToIds.put(code, ids);

                        setParentRelation(code);
                    } else {
                        newRecord.links.add(code);
                    }
                }
            } else {
                newRecord.links.add("");
            }
            records.put(newRecord.id, newRecord);
            years_from_data.add(newRecord.year);
            record_id_counter++;
        });
        System.out.println("Number of houses in total is: " + numberOfHouses);
    }

    private static void loadSquareKilometers(String csvPath) throws Exception {
        CSVParser parser = CSVParser.parse(new File(csvPath), Charset.forName("UTF-8"), csvFormat);
        Set<Integer> years = new TreeSet<>();
        for (String year : parser.getHeaderMap().keySet()) {
            if (!year.equals("SHORT-ID")) {
                years.add(Integer.parseInt(year));
            }
        }
        parser.forEach(record -> {
            SquareKilometreRecord s = new SquareKilometreRecord();
            s.linkCode = record.get("SHORT-ID");
            for (int year : years) {
                BigDecimal squareKm = record.get(Integer.toString(year)) != null ? new BigDecimal(record.get(Integer.toString(year))) : new BigDecimal(0);
                s.km2.put(year, squareKm);
            }
            squareKilometreRecords.add(s);
        });
    }

    /**
     * Sets the parent relation for each of the link codes
     *
     * @param code String The Link code to update
     */
    private static void setParentRelation(String code) {
        if (code.length() == 6)
            return;

        String parent = code.substring(0, code.length() - 1);
        Set<String> codes = codeHierarchy.getOrDefault(parent, new HashSet<>());
        codes.add(code);
        codeHierarchy.put(parent, codes);

        setParentRelation(parent);
    }

    /**
     * Updates the links for the records
     */
    private static void updateLinks() {
        new HashSet<>(codesToIds.keySet()).forEach(Main::updateLinksForCode);
    }

    /**
     * Updates the links so the lowest possible is given, and it is possible to work from down up
     *
     * @param code String The Link code to update
     */
    private static void updateLinksForCode(String code) {
        if (!codes.contains(code)) {
            codes.add(code);
        }
        if (codeHierarchy.containsKey(code)) {
            for (String parentCode : codeHierarchy.get(code)) {
                Set<String> ids = codesToIds.getOrDefault(parentCode, new HashSet<>());
                ids.addAll(codesToIds.get(code));
                codesToIds.put(parentCode, ids);

                updateLinksForCode(parentCode);
            }
            codesToIds.remove(code);
        }
    }

    private static void preSplitTheRecords() {
        List<String> temp_list = years_from_data.stream().sorted((t1, t2) -> (t1 <= t2) ? -1 : 1).map(map -> Integer.toString(map)).collect(Collectors.toList());

        Set<String> records_to_alter = new TreeSet<>();

        for (Record record : records.values()) {
            if (record.links.size() > 1) {
                number_of_records_with_multiple_links++;
            }
            for (Map.Entry<String, Set<String>> code_hierarchy : codeHierarchy.entrySet()) {
                if (record.links.containsAll(code_hierarchy.getValue())) {
                    records_to_alter.add(record.id);
                } else if (record.links.contains(code_hierarchy.getKey())) {
                    records_to_alter.add(record.id);
                }
            }
        }

        Set<String> squareKilometreLinkCodes = new TreeSet<>();
        for (SquareKilometreRecord squareKilometreRecord : squareKilometreRecords) {
            squareKilometreLinkCodes.add(squareKilometreRecord.linkCode);
        }

        Set<String> recordsToSplit = new TreeSet<>();
        for (Map.Entry<String, Set<String>> code_hier : codeHierarchy.entrySet()) {
            if (!squareKilometreLinkCodes.contains(code_hier.getKey())) {
                for (Record record : records.values()) {
                    if (record.links.contains(code_hier.getKey())) {
                        recordsToSplit.add(record.id);
                    }
                }
            } else {
                for (SquareKilometreRecord squareKilometreRecord : squareKilometreRecords) {
                    if (squareKilometreRecord.linkCode.equals(code_hier.getKey())) {
                        for (int year_to_test : years_from_data) {
                            if (squareKilometreRecord.km2.get(year_to_test) == null || squareKilometreRecord.km2.get(year_to_test).equals(BigDecimal.ZERO)) {
                                for (String code : code_hier.getValue()) {
                                    for (Record record : records.values()) {
                                        if (record.links.contains(code)) {
                                            recordsToSplit.add(record.id);
                                        } else if (record.links.contains(code_hier.getKey())) {
                                            recordsToSplit.add(record.id);
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Checks whether the codes to be split exist in the square kilometres so in case of splitting with square kilometres
        // the data is available and no records get lost...
        Set<String> record_ids_not_to_alter = new TreeSet<>();
        Set<String> squareRecordLinkCodes = new TreeSet<>();
        for (SquareKilometreRecord srecord : squareKilometreRecords) {
            squareRecordLinkCodes.add(srecord.linkCode);
        }
        for (String record_id : records_to_alter) {
            Record record_to_check = records.get(record_id);
            List<Record> records_that_compare = new ArrayList<>();
            for (Record record_to_compare : records.values()) {
                if (record_to_compare.links.equals(record_to_check.links)) {
                    records_that_compare.add(record_to_compare);
                }
            }
            if (records_that_compare.size() == temp_list.size()) {
                for (String link_to_check : record_to_check.links) {
                    if (!squareRecordLinkCodes.contains(link_to_check)) {
                        for (Record record_that_compares : records_that_compare)
                            record_ids_not_to_alter.add(record_that_compares.id);
                        break;
                    }
                }
            }
        }

//        Was used to check if the squareKilometres contains values, but not necessary??
//        Set<String> record_ids_not_to_alter_two = new TreeSet<>();
//        for (String record_to_alter : records_to_alter) {
//            Record record_to_check = records.get(record_to_alter);
//            for (SquareKilometreRecord squareKilometreRecord : squareKilometreRecords) {
//                if (record_to_check.links.contains(squareKilometreRecord.linkCode)) {
//                    if (squareKilometreRecord.km2.get(record_to_check.year).compareTo(BigDecimal.ZERO) == 0 || squareKilometreRecord.km2.get(record_to_check.year) == null) {
//                        record_ids_not_to_alter_two.add(record_to_check.id);
//                    }
//                } else {
//                    for (Map.Entry<String, Set<String>> code_hier : codeHierarchy.entrySet()) {
//                        for (String record_link : record_to_check.links) {
//                            if (code_hier.getValue().contains(record_link)) {
//                                if (squareKilometreRecord.linkCode.equals(code_hier.getKey())) {
//                                    if (squareKilometreRecord.km2.get(record_to_check.year).compareTo(BigDecimal.ZERO) == 0 || squareKilometreRecord.km2.get(record_to_check.year) == null) {
//                                        record_ids_not_to_alter_two.add(record_to_check.id);
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//        }

        records_to_alter.removeAll(record_ids_not_to_alter);
        // End of the check with squareKilometres

        // Check for link codes not to combine
        Set<String> link_codes_not_to_combine = new TreeSet<>();
        for (Map.Entry<String, Set<String>> parentEntry : codeHierarchy.entrySet()) {
            if (parentEntry.getKey().length() == 6) {
                Set<String> ids = new HashSet<>();
                for (String child : parentEntry.getValue()) {
                    if (codesToIds.get(child) != null)
                        ids.addAll(codesToIds.get(child));
                }
                if (ids.size() > years_from_data.size())
                    link_codes_not_to_combine.add(parentEntry.getKey());
            }
        }

        // CHECK whether the km2 contains the parent of the link codes, otherwise don't combine them...
        Set<String> link_codes_to_leave_out = new TreeSet<>();
        recordLoop:
        for (Record record : records.values()) {
            for (Map.Entry<String, Set<String>> code_hier : codeHierarchy.entrySet()) {
                if (record.links.containsAll(code_hier.getValue())) {
                    if (squareRecordLinkCodes.contains(code_hier.getKey())) {
                        for (SquareKilometreRecord squareKilometreRecord : squareKilometreRecords) {
                            if (squareKilometreRecord.linkCode.equals(code_hier.getKey())) {
                                if (squareKilometreRecord.km2.get(record.year) == null) {
                                    link_codes_to_leave_out.add(code_hier.getKey());
                                    continue recordLoop;
                                }
                            }
                        }
                    } else {
                        link_codes_to_leave_out.add(code_hier.getKey());
                    }
                }
            }
        }

        link_codes_to_leave_out.removeAll(link_codes_not_to_combine);

        // Code to make sure some record link codes are not combined
        // Depending on whether the number of codes found equals the number of years present.
        Map<String, Set<String>> hier_to_check = new HashMap<>();
        for (Map.Entry<String, Set<String>> hier_entry : codeHierarchy.entrySet()) {
            Set<String> codes = new TreeSet<>();
            for (Map.Entry<String, Set<String>> code_entry : codesToIds.entrySet()) {
                for (String hier : hier_entry.getValue()) {
                    if (code_entry.getKey().equals(hier)) {
                        codes.addAll(code_entry.getValue());
                    }
                }
            }
            if (codes.size() == years_from_data.size()) {
                hier_to_check.put(hier_entry.getKey(), codes);
            }
        }

        // Checking whether to remove the entries or not, saving them in a separate list
        Set<String> entries_to_remove = new TreeSet<>();
        entryLoop:
        for (Map.Entry<String, Set<String>> entry : hier_to_check.entrySet()) {
            for (String record_id : entry.getValue()) {
                for (Record rec : records.values()) {
                    if (rec.id.equals(record_id)) {
                        if (!rec.links.containsAll(codeHierarchy.get(entry.getKey()))) {
                            entries_to_remove.add(entry.getKey());
                            continue entryLoop;
                        }
                    }
                }
            }
        }
        // Removing the entries to remove, due to incomplete children Link Codes
        for (String entry_to_remove : entries_to_remove) {
            hier_to_check.remove(entry_to_remove);
        }

        // Combining link codes if all records contain link codes.
        for (Map.Entry<String, Set<String>> code_to_id : codesToIds.entrySet()) {
            if (records_to_alter.containsAll(code_to_id.getValue())) { // Checks if all the ids for a HO code are present E.G. HO0061B=[279, 1367, 601]
                for (Record record : records.values()) {
                    if (code_to_id.getValue().contains(record.id)) { // Checks if the id of the record contains one of the ids for code_to_id E.G. 279
                        if (codeHierarchy.entrySet().stream().anyMatch(map -> map.getValue().contains(code_to_id.getKey()))) { // Checks if one of the codehierachies contains the HO code of the code_to_id E.G. HO0061B
                            for (Map.Entry<String, Set<String>> hier_entry : codeHierarchy.entrySet()) {
                                if (hier_entry.getValue().contains(code_to_id.getKey())) { // Checks if the hierarchy_entry value contains the code_to_id key E.G. HO0061B (See above)
                                    if (record.links.containsAll(hier_entry.getValue())) { // Checks if the links contains all the hierarchy_entry values E.G. HO0061=[HO0061A, HO0061B]
                                        if (hier_entry.getValue().size() > 1) {
                                            if (link_codes_not_to_combine.contains(hier_entry.getKey()))
                                                continue;
                                            if (!hier_to_check.keySet().contains(hier_entry.getKey()))
                                                continue;
                                            if (link_codes_to_leave_out.contains(hier_entry.getKey()))
                                                continue;
                                            // If all the previous is correct the link codes are adjusted accordingly
                                            String parent = "";
                                            boolean record_is_invalid = false;
                                            for (String s : hier_entry.getValue()) {
                                                if (!record.links.contains(s)) {
                                                    record_is_invalid = true;
                                                    break;
                                                }
                                            }
                                            if (!record_is_invalid) {
                                                for (String s : hier_entry.getValue()) {
                                                    record.links.remove(s);
                                                }
                                                for (Map.Entry<String, Set<String>> code_hierarchy_entry : codeHierarchy.entrySet()) {
                                                    if (code_hierarchy_entry.getValue().contains(code_to_id.getKey())) {
                                                        parent = code_hierarchy_entry.getKey();
                                                    }
                                                }
                                                if (!parent.equals("") && !record.links.contains(parent)) {
                                                    record.links.add(parent);
                                                }
                                            }
                                        } else {
                                            System.out.println(getLineNumber() + " -> " + hier_entry.getValue());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (recordsToSplit.containsAll(code_to_id.getValue())) {
                for (Record record : records.values()) {
                    if (code_to_id.getValue().contains(record.id)) {
                        if (codeHierarchy.entrySet().stream().anyMatch(map -> map.getValue().contains(code_to_id.getKey()))) {
                            for (Map.Entry<String, Set<String>> hier_entry : codeHierarchy.entrySet()) {
                                if (hier_entry.getValue().contains(code_to_id.getKey())) {
                                    if (record.links.contains(hier_entry.getKey())) {
                                        if (hier_entry.getValue().size() > 1) {
                                            record.links.remove(hier_entry.getKey());
                                            for (String link : hier_entry.getValue()) {
                                                if (!record.links.contains(link)) {
                                                    record.links.add(link);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Updating the lists so there are no wrong calculations
        codeHierarchy.clear();
        codesToIds.clear();
        updateLinkRelations();
        updateLinks();
    }

    private static boolean determineIfItNeedsToBeSplitWithSquareKilometres(Map.Entry<String, Record> record) {
        boolean doesItNeedToBeSplitWithSquareKilometres = false;

        Map<String, BigDecimal> toUseSquareKilometreMap = new HashMap<>();
        Map<String, BigDecimal> currentSquareKilometreMap = new HashMap<>();

        Map<String, Set<String>> temp_code_map = new TreeMap<>();
        fillCodeMapBasedOnCodeToIds(record, temp_code_map);

        // Checking if the code_map contains all links of one parent before combining them
        determineCodesToRemoveAndToAppend(record, temp_code_map);

        // Gets the unique values and the duplicate values
        Set<String> temp_duplicates = null;
        Set<String> temp_uniques = null;

        temp_duplicates = collectDuplicateLinkCodes(temp_duplicates, temp_code_map);
        temp_uniques = collectUniquesLinkCodes(temp_uniques, temp_code_map);
        temp_uniques.removeAll(temp_duplicates);

        // Removes the unique codes for where the record doesn't contain the specific Link code,
        // so it doesn't use a child code for a parent or vice verse
        removeIncompleteChildLinkCodes(record, temp_uniques);

        // Looping through the unique codes to determine duplicate years in the Link codes
        TreeSet<Integer> temp_years = new TreeSet<>();

        Map<String, BigDecimal> temp_uniqueValuesToCalculateFrom = new HashMap<>();
        determineUniqueValuesToCalculateFrom(temp_uniques, temp_years, temp_uniqueValuesToCalculateFrom);

        Map<Integer, Set<String>> link_code_map = new HashMap<>();
        for (Map.Entry<String, BigDecimal> entry : temp_uniqueValuesToCalculateFrom.entrySet()) {
            for (Record record_to_check : records.values()) {
                if (record_to_check.id.equals(entry.getKey())) {
//                    if (record_to_check.note.equals(NoteState.YEAR_SURFACE)) {
                    if (link_code_map.containsKey(record_to_check.year)) {
                        link_code_map.get(record_to_check.year).addAll(record_to_check.links);
                    } else {
                        link_code_map.put(record_to_check.year, new TreeSet<>(record_to_check.links));
                    }
//                    }
                }
            }
        }

        link_code_map_loop:
        for (Map.Entry<Integer, Set<String>> entry : link_code_map.entrySet()) {
            if (!entry.getKey().equals(record.getValue().year)) {
                if (entry.getValue().containsAll(record.getValue().links)) {
                    Map<String, BigDecimal> mapToUse = new HashMap<>();
                    Map<String, BigDecimal> mapCurrent = new HashMap<>();
                    for (SquareKilometreRecord sr : squareKilometreRecords) {
                        if (entry.getValue().contains(sr.linkCode)) {
                            BigDecimal squareKilometresToCheck = sr.km2.get(entry.getKey());
                            BigDecimal squareKilometresCurrentRecord = sr.km2.get(record.getValue().year);
                            if (squareKilometresCurrentRecord == null || squareKilometresToCheck == null
                                    || squareKilometresCurrentRecord.compareTo(BigDecimal.ZERO) == 0
                                    || squareKilometresToCheck.compareTo(BigDecimal.ZERO) == 0) {
//                                System.out.println(getLineNumber() + " => " + entry + " -> " + sr);
                                continue link_code_map_loop;
                            } else {
                                mapToUse.put(sr.linkCode, squareKilometresToCheck);
                                mapCurrent.put(sr.linkCode, squareKilometresCurrentRecord);
                            }
                        }
                    }
                    toUseSquareKilometreMap.putAll(mapToUse);
                    currentSquareKilometreMap.putAll(mapCurrent);
                }
            }
        }

        List<BigDecimal> currentRatios = new ArrayList<>();
        List<BigDecimal> toUseRatios = new ArrayList<>();
        for (String link : record.getValue().links) {
            try {
                BigDecimal toUseSKRec = toUseSquareKilometreMap.get(link);
                BigDecimal currentSKRec = currentSquareKilometreMap.get(link);
                BigDecimal total = toUseSKRec.add(currentSKRec);
                BigDecimal currentRatio = currentSKRec.divide(total, 3, BigDecimal.ROUND_HALF_EVEN) == null ? BigDecimal.ZERO : currentSKRec.divide(total, 3, BigDecimal.ROUND_HALF_EVEN);
                BigDecimal toUseRatio = toUseSKRec.divide(total, 3, BigDecimal.ROUND_HALF_EVEN) == null ? BigDecimal.ZERO : toUseSKRec.divide(total, 3, BigDecimal.ROUND_HALF_EVEN);
                currentRatios.add(currentRatio);
                toUseRatios.add(toUseRatio);
            } catch (NullPointerException | ArithmeticException e) {
//                System.out.println(getLineNumber() + " -> " + record.getValue());
            }
        }

        for (int i = 0; i < currentRatios.size(); i++) {
            if (currentRatios.get(i).compareTo(toUseRatios.get(i)) != 0)
                doesItNeedToBeSplitWithSquareKilometres = true;
        }

        return doesItNeedToBeSplitWithSquareKilometres;
    }

    /**
     * Exports the information to a csv file.
     *
     * @param exportPath String the path to which the export is exported
     * @throws Exception NullPointerException is caught when an exception is thrown.
     */
    private static void export(String exportPath, String notesPath) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(exportPath))) {
            CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat);

            List<String> headerRow = new ArrayList<>();
//            headerRow.add("YEAR");
//            headerRow.add("LINK");
//            headerRow.add("HOUSES");
//            headerRow.add("KM2");

            headerRow.add("Code");
            @SuppressWarnings("ComparatorMethodParameterNotUsed")
            List<String> temp_list = years_from_data.stream().sorted((t1, t2) -> (t1 <= t2) ? -1 : 1).map(map -> Integer.toString(map)).collect(Collectors.toList());
            headerRow.addAll(temp_list);

            csvPrinter.printRecord(headerRow);

            List<String> codesUsed = new ArrayList<>();

            Collections.sort(codes);

            boolean tried_with_number_of_homes = false;
            boolean tryAlternativeSplitting = false;
            while (number_of_records_with_multiple_links != 0) {
                for (Map.Entry<String, Record> record : records.entrySet()) {
                    // Code to check whether or not a record needs to be split by square kilometres based on other records...
                    boolean doesItNeedToBeSplitWithSquareKilometres = false;
                    if (record.getValue().links.size() > 1) {
                        doesItNeedToBeSplitWithSquareKilometres = determineIfItNeedsToBeSplitWithSquareKilometres(record);
                    }
                    // End of the check if records need to be split on square kilometres...

                    if (record.getValue().links.size() == 2) {
                        Map<String, Set<String>> code_map = new TreeMap<>();
                        fillCodeMapBasedOnCodeToIds(record, code_map);

                        // Checking if the code_map contains all links of one parent before combining them
                        determineCodesToRemoveAndToAppend(record, code_map);

                        // Gets the unique values and the duplicate values
                        Set<String> duplicates = null;
                        Set<String> uniques = null;
                        duplicates = collectDuplicateLinkCodes(duplicates, code_map);
                        uniques = collectUniquesLinkCodes(uniques, code_map);
                        uniques.removeAll(duplicates);

                        // Removes the unique codes for where the record doesn't contain the specific Link code,
                        // so it doesn't use a child code for a parent or vice verse
                        removeIncompleteChildLinkCodes(record, uniques);

                        // Looping through the unique codes to determine duplicate years in the Link codes
                        TreeSet<Integer> years = new TreeSet<>();

                        Map<String, BigDecimal> uniqueValuesToCalculateFrom = new HashMap<>();
                        determineUniqueValuesToCalculateFrom(uniques, years, uniqueValuesToCalculateFrom);

                        // Determine the value to calculate. These are the codes that are the same, ergo: the codes that need to be split up.
                        Map<String, BigDecimal> equalValueToCalculate = new HashMap<>();
                        for (String duplicate : duplicates) {
                            for (Map.Entry<String, Record> duplicate_record : records.entrySet()) {
                                if (duplicate_record.getValue().id.equals(duplicate)) {
                                    equalValueToCalculate.put(duplicate, duplicate_record.getValue().houses);
                                }
                            }
                        }

                        Integer year_diff = Integer.MAX_VALUE;
                        Integer closest_year_for_calculating_number_of_homes = 0;

                        // Loop through the map containing the values to calculate
                        // Looping through the records to check which record corresponds with the valueToCalculate ID
                        closest_year_for_calculating_number_of_homes = determineClosestYearForCalculatingNumberOfHomes(years, equalValueToCalculate, year_diff, closest_year_for_calculating_number_of_homes);

                        Map<BigDecimal, List<String>> valuesToCalculateWithMap = new HashMap<>();
                        // Loop through the Map of unique values to perform the calculation
                        for (Map.Entry<String, BigDecimal> uniqueValue : uniqueValuesToCalculateFrom.entrySet()) {
                            // Loop through the records to get the record for which the id and year are the same as the given unique value
                            for (Map.Entry<String, Record> unique_value_record : records.entrySet()) {
                                if (unique_value_record.getValue().id.equals(uniqueValue.getKey()) && unique_value_record.getValue().year == closest_year_for_calculating_number_of_homes) {
                                    if (uniqueValue.getValue() != null) {
                                        if (uniqueValue.getValue().compareTo(BigDecimal.ZERO) != 0) {
                                            valuesToCalculateWithMap.put(uniqueValue.getValue(), unique_value_record.getValue().links);
                                        }
                                    }
                                }
                            }
                        }

                        if (valuesToCalculateWithMap.keySet().contains(BigDecimal.ZERO)) {
                            continue;
                        }

                        // Check if the Map with unique values is bigger than 0
                        if (valuesToCalculateWithMap.size() == 2 && !doesItNeedToBeSplitWithSquareKilometres) {

                            List<BigDecimal> values = new ArrayList<>();
                            // Sort the values so the first value is the smallest one for calculation purposes
                            for (Map.Entry<BigDecimal, List<String>> value_to_calculate : valuesToCalculateWithMap.entrySet()) {
                                values.add(value_to_calculate.getKey());
                            }
                            Collections.sort(values);

                            // Loop through the values to calculate in order to get the correct number of houses based on the proportions
                            for (Map.Entry<String, BigDecimal> valueToCalculate : equalValueToCalculate.entrySet()) {
                                Pair result;
                                try {
                                    result = getNumberOfHousesInAccordanceToUpcomingYear(new Pair(values.get(0), values.get(1)), valueToCalculate.getValue());
                                } catch (Exception ex) {
                                    result = new Pair(new BigDecimal(0), new BigDecimal(0));
                                }

                                if (result.lowestNumber.compareTo(BigDecimal.ZERO) != 0 || result.highestNumber.compareTo(BigDecimal.ZERO) != 0) {
                                    // Determine the highest number of the returned value after the calculation
                                    Map<List<String>, BigDecimal> resultMap = new HashMap<>();
                                    resultMap.put(valuesToCalculateWithMap.get(values.get(0)), result.lowestNumber);
                                    resultMap.put(valuesToCalculateWithMap.get(values.get(1)), result.highestNumber);

                                    if (valueToCalculate.getKey().equals(record.getKey())) {
                                        for (String record_link : record.getValue().links) {
                                            BigDecimal numberOfHomes = BigDecimal.ZERO;
                                            for (Map.Entry<List<String>, BigDecimal> entry : resultMap.entrySet()) {
                                                if (entry.getKey().contains(record_link))
                                                    numberOfHomes = entry.getValue();
                                            }
                                            createNewRecord(record.getValue(), numberOfHomes, record_link, null, NoteState.YEAR_SOURCE, closest_year_for_calculating_number_of_homes);
                                        }
                                        recordsToRemove.add(valueToCalculate.getKey());
                                    }
                                } else if (record.getValue().houses != null && record.getValue().houses.equals(BigDecimal.ZERO)) {
                                    for (String link : record.getValue().links) {
                                        createNewRecord(record.getValue(), record.getValue().houses, link, null, NoteState.YEAR_SOURCE, closest_year_for_calculating_number_of_homes);
                                    }
                                    recordsToRemove.add(record.getValue().id);
                                } else if (record.getValue().houses == null) {
                                    splitRecordWithNullHomes(record);
                                    recordsToRemove.add(record.getValue().id);
                                }
                            }
                        } else if (tried_with_number_of_homes || doesItNeedToBeSplitWithSquareKilometres) {
                            Map<String, BigDecimal> squareKilometresToCalculateWithMap = new HashMap<>();
                            BigDecimal totalSquareKilometres = new BigDecimal(0);
                            totalSquareKilometres = collectSquareKmsToCalculateWith(record, code_map, squareKilometresToCalculateWithMap, totalSquareKilometres);
                            if (squareKilometresToCalculateWithMap.size() > 0 && !totalSquareKilometres.equals(new BigDecimal(0))) {
                                for (Map.Entry<String, BigDecimal> entry : squareKilometresToCalculateWithMap.entrySet()) {
                                    BigDecimal ratio = entry.getValue().divide(totalSquareKilometres, BigDecimal.ROUND_HALF_EVEN);
                                    BigDecimal numberOfHomes;
                                    if (record.getValue().houses != null)
                                        numberOfHomes = record.getValue().houses.multiply(ratio).setScale(3, BigDecimal.ROUND_HALF_EVEN);
                                    else if (record.getValue().houses == null)
                                        numberOfHomes = null;
                                    else
                                        numberOfHomes = BigDecimal.ZERO;
                                    createNewRecord(record.getValue(), numberOfHomes, entry.getKey(), entry.getValue(), NoteState.YEAR_SURFACE, record.getValue().year);
                                }
                                recordsToRemove.add(record.getKey());
//                                tried_with_number_of_homes = false;
                                break;
                            }
                        } else if (valuesToCalculateWithMap.size() == 1) {
                            List<Record> recordsToUseForCalculation = new ArrayList<>();

                            for (Record rec : records.values()) {
                                for (Map.Entry<String, BigDecimal> entry : uniqueValuesToCalculateFrom.entrySet()) {
                                    if (rec.id.equals(entry.getKey()) && rec.year == closest_year_for_calculating_number_of_homes) {
                                        recordsToUseForCalculation.add(rec);
                                    }
                                }
                            }

                            if (recordsToUseForCalculation.size() == 2) {
                                Pair result;
                                try {
                                    result = getNumberOfHousesInAccordanceToUpcomingYear(new Pair(recordsToUseForCalculation.get(0).houses, recordsToUseForCalculation.get(1).houses), record.getValue().houses);
                                } catch (Exception ex) {
                                    result = new Pair(new BigDecimal(0), new BigDecimal(0));
                                }

                                // Determine the highest number of the returned value after the calculation
                                Map<List<String>, BigDecimal> resultMap = new HashMap<>();
                                resultMap.put(valuesToCalculateWithMap.get(recordsToUseForCalculation.get(0).houses), result.lowestNumber);
                                resultMap.put(valuesToCalculateWithMap.get(recordsToUseForCalculation.get(1).houses), result.highestNumber);

                                if (record.getValue().id.equals(record.getKey())) {
                                    for (String record_link : record.getValue().links) {
                                        BigDecimal newHouses = BigDecimal.ZERO;
                                        for (Map.Entry<List<String>, BigDecimal> entry : resultMap.entrySet()) {
                                            try {
                                                if (entry.getKey().contains(record_link)) {
                                                    newHouses = entry.getValue();
                                                    break;
                                                } else
                                                    newHouses = null;
                                            } catch (NullPointerException ex) {
                                                newHouses = entry.getValue();
                                            }
                                        }
                                        createNewRecord(record.getValue(), newHouses, record_link, null, NoteState.YEAR_SOURCE, closest_year_for_calculating_number_of_homes);
                                    }
                                    recordsToRemove.add(record.getValue().id);
                                }
                            }
                        }
                    } else if (record.getValue().links.size() > 2) {
                        Map<String, Set<String>> code_map = new TreeMap<>();

                        fillCodeMapBasedOnCodeToIds(record, code_map);

                        determineCodesToRemoveAndToAppend(record, code_map);

                        // Gets the unique values and the duplicate values
                        Set<String> duplicates = null;
                        Set<String> uniques = null;
                        duplicates = collectDuplicateLinkCodes(duplicates, code_map);
                        uniques = collectUniquesLinkCodes(uniques, code_map);
                        uniques.removeAll(duplicates);

                        // Removes the unique codes for where the record doesn't contain the specific Link code,
                        // so it doesn't use a child code for a parent or vice verse
                        removeIncompleteChildLinkCodes(record, uniques);
                        Boolean do_links_compare = true;
                        List<Record> recordList = new ArrayList<>();
                        for (String duplicate : duplicates) {
                            for (Record rec : records.values()) {
                                if (rec.id.equals(duplicate)) {
                                    recordList.add(rec);
                                }
                            }
                        }

                        for (Record rec : recordList) {
                            for (Record rec2 : recordList) {
                                if (!rec.id.equals(rec2.id)) {
                                    if (!rec.links.equals(rec2.links)) {
                                        do_links_compare = false;
                                    }
                                }
                            }
                        }

                        if (uniques.size() >= record.getValue().links.size() && do_links_compare && recordList.size() > 1 && !doesItNeedToBeSplitWithSquareKilometres) {
                            // Looping through the unique codes to determine duplicate years in the Link codes
                            TreeSet<Integer> years = new TreeSet<>();

                            Map<String, BigDecimal> uniqueValuesToCalculateFrom = new HashMap<>();
                            determineUniqueValuesToCalculateFrom(uniques, years, uniqueValuesToCalculateFrom);


                            // Determine the value to calculate. These are the codes that are the same, ergo: the codes that need to be split up.
                            Map<String, BigDecimal> equalValueToCalculate = new HashMap<>();
                            for (String duplicate : duplicates) {
                                for (Map.Entry<String, Record> duplicate_record : records.entrySet()) {
                                    if (duplicate_record.getValue().id.equals(duplicate)) {
                                        equalValueToCalculate.put(duplicate, duplicate_record.getValue().houses);
                                    }
                                }
                            }

                            Integer year_diff = Integer.MAX_VALUE;
                            Integer closest_year_for_calculating_number_of_homes = 0;

                            // Loop through the map containing the values to calculate
                            // Looping through the records to check which record corresponds with the valueToCalculate ID
                            closest_year_for_calculating_number_of_homes = determineClosestYearForCalculatingNumberOfHomes(years, equalValueToCalculate, year_diff, closest_year_for_calculating_number_of_homes);
//
                            // ############################################################ //
                            // CODE TO CHECK THE YEAR TO WORK WITH IS CORRECT //
                            // Collecting the records belonging to the unique values to calculate from.
                            Set<String> records_not_to_use = new TreeSet<>();
                            Map<Integer, Set<String>> record_year_link_map = new HashMap<>();
                            for (Integer year : years) {
                                if (!year.equals(record.getValue().year)) {
                                    Set<String> links = new TreeSet<>();
                                    for (Record record_year_to_check : records.values()) {
                                        if (uniqueValuesToCalculateFrom.keySet().contains(record_year_to_check.id)) {
                                            for (String code_map_link : code_map.keySet()) {
                                                if (record_year_to_check.links.contains(code_map_link) && record_year_to_check.links.size() == 1) {
                                                    links.add(code_map_link);
                                                } else if (record_year_to_check.links.contains(code_map_link) && record_year_to_check.links.size() > 1) {
                                                    records_not_to_use.add(record_year_to_check.id);
                                                }
                                            }
                                        }
                                    }
                                    record_year_link_map.put(year, links);
                                }
                            }

                            Map<Integer, Set<String>> record_year_link_not_to_use_map = new HashMap<>();
                            for (Record record_to_check_for_use : records.values()) {
                                if (records_not_to_use.contains(record_to_check_for_use.id)) {
                                    record_year_link_not_to_use_map.put(record_to_check_for_use.year, new TreeSet<>(record_to_check_for_use.links));
                                }
                            }

                            // This should remove the links for records that should not be used, resulting in the correct year in the
                            // following piece of code.
                            for (Map.Entry<Integer, Set<String>> record_year_link_entry : record_year_link_map.entrySet()) {
                                try {
                                    record_year_link_entry.getValue().removeIf(record_link -> record_year_link_not_to_use_map.get(record_year_link_entry.getKey()).contains(record_link));
                                } catch (NullPointerException ex) {
//                                    System.out.println(getLineNumber() + " -> " + ex);
                                }
                            }

                            // looping through the collected records to determine which year is complete in the sense of links
                            for (Map.Entry<Integer, Set<String>> record_year_link_entry : record_year_link_map.entrySet()) {
                                if (record_year_link_entry.getValue().containsAll(record.getValue().links) && record.getValue().links.size() == record_year_link_entry.getValue().size()) {
                                    closest_year_for_calculating_number_of_homes = record_year_link_entry.getKey();
                                } else {
                                    while (record_year_link_entry.getValue().size() > record.getValue().links.size()) {
//                                        System.out.println(getLineNumber() + " -> " + record.getValue().id);
                                        Iterator<String> it = record_year_link_entry.getValue().iterator();
                                        List<String> links = new ArrayList<>();
                                        while (it.hasNext()) {
                                            String link = it.next();
                                            for (Map.Entry<String, Set<String>> code_hier : codeHierarchy.entrySet()) {
                                                if (!record.getValue().links.contains(link)) {
                                                    if (code_hier.getValue().contains(link)) {
                                                        it.remove();
                                                        links.add(code_hier.getKey());
                                                    }
                                                }
                                            }
                                        }
                                        if (links.size() == 0) {
                                            break;
                                        }
                                        record_year_link_entry.getValue().addAll(links);
                                        if (record_year_link_entry.getValue().containsAll(record.getValue().links) && record.getValue().links.size() == record_year_link_entry.getValue().size()) {
                                            closest_year_for_calculating_number_of_homes = record_year_link_entry.getKey();
                                        }
                                    }
                                }
                            }
                            // END OF CODE TO CHECK THE YEAR TO WORK WITH IS CORRECT //
                            // ############################################################ //

                            Map<BigDecimal, List<String>> valuesToCalculateWithMap = new HashMap<>();
                            NoteState noteState = null;
                            // Loop through the Map of unique values to perform the calculation
                            for (Map.Entry<String, BigDecimal> uniqueValue : uniqueValuesToCalculateFrom.entrySet()) {
                                // Loop through the records to get the record for which the id and year are the same as the given unique value
                                for (Map.Entry<String, Record> unique_value_record : records.entrySet()) {
                                    if (unique_value_record.getValue().id.equals(uniqueValue.getKey()) && unique_value_record.getValue().year == closest_year_for_calculating_number_of_homes) {
                                        valuesToCalculateWithMap.put(uniqueValue.getValue(), unique_value_record.getValue().links);
                                        if (unique_value_record.getValue().note != NoteState.SOURCE)
                                            noteState = unique_value_record.getValue().note;
                                    }
                                }
                            }

                            BigDecimal total_to_calculate_from = new BigDecimal(0);
                            for (BigDecimal bd : valuesToCalculateWithMap.keySet()) {
                                if (bd == null)
                                    total_to_calculate_from = total_to_calculate_from.add(BigDecimal.ZERO);
                                else
                                    total_to_calculate_from = total_to_calculate_from.add(bd);
                            }

                            if (valuesToCalculateWithMap.size() < record.getValue().links.size()) {
                                if (tryAlternativeSplitting) {
                                    Map<String, BigDecimal> squareKilometresToCalculateWithMap = new HashMap<>();
                                    BigDecimal totalSquareKilometres = new BigDecimal(0);
                                    totalSquareKilometres = collectSquareKmsToCalculateWith(record, code_map, squareKilometresToCalculateWithMap, totalSquareKilometres);
                                    if (squareKilometresToCalculateWithMap.size() == record.getValue().links.size() && !totalSquareKilometres.equals(BigDecimal.ZERO)) {
                                        for (Map.Entry<String, BigDecimal> entry : squareKilometresToCalculateWithMap.entrySet()) {
                                            BigDecimal ratio = entry.getValue().divide(totalSquareKilometres, BigDecimal.ROUND_HALF_EVEN);
                                            BigDecimal numberOfHomes = record.getValue().houses.multiply(ratio).setScale(3, BigDecimal.ROUND_HALF_EVEN);
                                            createNewRecord(record.getValue(), numberOfHomes, entry.getKey(), entry.getValue(), NoteState.YEAR_SURFACE, record.getValue().year);
                                        }
                                        recordsToRemove.add(record.getKey());
                                        break;
                                    } else {
                                        boolean record_creation_succeeded = false;
                                        for (String record_link_code : record.getValue().links) {
                                            if (squareKilometresToCalculateWithMap.get(record_link_code) != null) {
                                                record_creation_succeeded = createNewRecordBasedOnSquareKilometres(squareKilometresToCalculateWithMap, record_link_code, totalSquareKilometres, record.getValue());
                                            } else if (codeHierarchy.get(record_link_code) != null) {
                                                for (String code_hier_child : codeHierarchy.get(record_link_code)) {
                                                    if (squareKilometresToCalculateWithMap.keySet().containsAll(codeHierarchy.get(record_link_code))) {
                                                        if (squareKilometresToCalculateWithMap.get(code_hier_child) != null) {
                                                            record_creation_succeeded = createNewRecordBasedOnSquareKilometres(squareKilometresToCalculateWithMap, code_hier_child, totalSquareKilometres, record.getValue());
                                                        }
                                                    }
                                                }
                                            } else {
                                                createNewRecord(record.getValue(), null, record_link_code, BigDecimal.ZERO, NoteState.YEAR_SURFACE, record.getValue().year);
                                            }
                                        }
                                        if (record_creation_succeeded) {
                                            recordsToRemove.add(record.getKey());
                                            break;
                                        }
                                    }
                                    tryAlternativeSplitting = false;
                                } else
                                    continue;
                            }
                            if (total_to_calculate_from.compareTo(BigDecimal.ZERO) != 0 || total_to_calculate_from != null) {
                                for (Map.Entry<String, BigDecimal> entry_to_recalculate : equalValueToCalculate.entrySet()) {
                                    Map<BigDecimal, List<String>> calculated_home_values = new HashMap<>();
                                    for (Map.Entry<BigDecimal, List<String>> entry : valuesToCalculateWithMap.entrySet()) {
                                        BigDecimal ratio = BigDecimal.ZERO;
                                        if (entry.getKey() != null)
                                            ratio = entry.getKey().divide(total_to_calculate_from, 9, BigDecimal.ROUND_HALF_EVEN);
                                        BigDecimal result;
                                        if (entry_to_recalculate.getValue() != null)
                                            result = ratio.multiply(entry_to_recalculate.getValue());
                                        else
                                            result = new BigDecimal(0);
                                        calculated_home_values.put(result, entry.getValue());
                                    }

                                    BigDecimal number_of_homes_validator = new BigDecimal(0);
                                    for (Map.Entry<BigDecimal, List<String>> calculated_home_value : calculated_home_values.entrySet()) {
                                        Record newRecord = new Record();
                                        newRecord.houses = calculated_home_value.getKey().setScale(3, BigDecimal.ROUND_HALF_EVEN);
                                        newRecord.year = records.get(entry_to_recalculate.getKey()).year;
                                        newRecord.links = calculated_home_value.getValue();
                                        newRecord.yearUsedToCalculate = closest_year_for_calculating_number_of_homes;
                                        if (noteState != null)
                                            newRecord.note = noteState;
                                        else
                                            newRecord.note = NoteState.YEAR_SOURCE;
                                        newRecord.id = entry_to_recalculate.getKey() + newRecord.year + newRecord.links.toString() + newRecord.houses;
                                        number_of_homes_validator = number_of_homes_validator.add(newRecord.houses);

                                        if (!recordsToAdd.containsKey(newRecord.id)) {
                                            recordsToAdd.put(newRecord.id, newRecord);
                                        }
                                    }
                                    recordsToRemove.add(entry_to_recalculate.getKey());
                                }
                            }
                        } else { // use square kilometres to calculate the number of homes
                            if (tried_with_number_of_homes || doesItNeedToBeSplitWithSquareKilometres) { // Check to make the use of square kilometres less frequent
                                if (record.getValue().houses != null) {
                                    Map<String, BigDecimal> squareKilometresToCalculateWithMap = new HashMap<>();
                                    BigDecimal totalSquareKilometres = new BigDecimal(0);
                                    totalSquareKilometres = collectSquareKmsToCalculateWith(record, code_map, squareKilometresToCalculateWithMap, totalSquareKilometres);
                                    if (squareKilometresToCalculateWithMap.size() == record.getValue().links.size() && !totalSquareKilometres.equals(BigDecimal.ZERO)) {
                                        for (Map.Entry<String, BigDecimal> entry : squareKilometresToCalculateWithMap.entrySet()) {
                                            BigDecimal ratio = entry.getValue().divide(totalSquareKilometres, BigDecimal.ROUND_HALF_EVEN);
                                            BigDecimal numberOfHomes = record.getValue().houses.multiply(ratio).setScale(3, BigDecimal.ROUND_HALF_EVEN);
                                            createNewRecord(record.getValue(), numberOfHomes, entry.getKey(), entry.getValue(), NoteState.YEAR_SURFACE, record.getValue().year);
                                        }
                                        recordsToRemove.add(record.getKey());
                                        break;
                                    } else {
                                        boolean record_creation_succeeded = false;
                                        for (String record_link_code : record.getValue().links) {
                                            if (squareKilometresToCalculateWithMap.get(record_link_code) != null) {
                                                record_creation_succeeded = createNewRecordBasedOnSquareKilometres(squareKilometresToCalculateWithMap, record_link_code, totalSquareKilometres, record.getValue());
                                            } else if (codeHierarchy.get(record_link_code) != null && codeHierarchy.get(record_link_code).size() > 1) {
                                                for (String code_hier_child : codeHierarchy.get(record_link_code)) {
                                                    if (squareKilometresToCalculateWithMap.keySet().containsAll(codeHierarchy.get(record_link_code))) {
                                                        if (squareKilometresToCalculateWithMap.get(code_hier_child) != null) {
                                                            record_creation_succeeded = createNewRecordBasedOnSquareKilometres(squareKilometresToCalculateWithMap, code_hier_child, totalSquareKilometres, record.getValue());
                                                        }
                                                    }
                                                }
                                            } else {
                                                record_creation_succeeded = createNewRecord(record.getValue(), null, record_link_code, BigDecimal.ZERO, NoteState.YEAR_SURFACE, record.getValue().year);
                                            }
                                        }
                                        if (record_creation_succeeded) {
                                            recordsToRemove.add(record.getKey());
                                            break;
                                        }
                                    }
                                } else {
                                    splitRecordWithNullHomes(record);
                                    recordsToRemove.add(record.getValue().id);
                                }
                            } else if (tryAlternativeSplitting && uniques.size() >= record.getValue().links.size() && do_links_compare && recordList.size() == 1 && !doesItNeedToBeSplitWithSquareKilometres) {
                                Set<String> new_uniques = new TreeSet<>();
                                for (Map.Entry<String, Set<String>> code_from_map : code_map.entrySet()) {
                                    new_uniques.addAll(code_from_map.getValue());
                                }

                                // Looping through the unique codes to determine duplicate years in the Link codes
                                TreeSet<Integer> years = new TreeSet<>();

                                Map<String, BigDecimal> uniqueValuesToCalculateFrom = new HashMap<>();
                                determineUniqueValuesToCalculateFrom(new_uniques, years, uniqueValuesToCalculateFrom);

                                // Determine the value to calculate. These are the codes that are the same, ergo: the codes that need to be split up.
                                Map<String, BigDecimal> equalValueToCalculate = new HashMap<>();
                                for (String duplicate : duplicates) {
                                    for (Map.Entry<String, Record> duplicate_record : records.entrySet()) {
                                        if (duplicate_record.getValue().id.equals(duplicate)) {
                                            equalValueToCalculate.put(duplicate, duplicate_record.getValue().houses);
                                        }
                                    }
                                }

                                Integer year_diff = Integer.MAX_VALUE;
                                Integer closest_year_for_calculating_number_of_homes = 0;

                                // Loop through the map containing the values to calculate
                                // Looping through the records to check which record corresponds with the valueToCalculate ID
                                closest_year_for_calculating_number_of_homes = determineClosestYearForCalculatingNumberOfHomes(years, equalValueToCalculate, year_diff, closest_year_for_calculating_number_of_homes);

                                // Collecting the records belonging to the unique values to calculate from.
                                Set<String> records_not_to_use = new TreeSet<>();
                                Map<Integer, Set<String>> record_year_link_map = new HashMap<>();
                                for (Integer year : years) {
                                    if (!year.equals(record.getValue().year)) {
                                        Set<String> links = new TreeSet<>();
                                        for (Record record_year_to_check : records.values()) {
                                            if (uniqueValuesToCalculateFrom.keySet().contains(record_year_to_check.id)) {
                                                for (String code_map_link : code_map.keySet()) {
                                                    if (record_year_to_check.links.contains(code_map_link) && record_year_to_check.links.size() == 1) {
                                                        links.add(code_map_link);
                                                    } else if (record_year_to_check.links.contains(code_map_link) && record_year_to_check.links.size() > 1) {
                                                        records_not_to_use.add(record_year_to_check.id);
                                                    }
                                                }
                                            }
                                        }
                                        record_year_link_map.put(year, links);
                                    }
                                }

                                Map<Integer, Set<String>> record_year_link_not_to_use_map = new HashMap<>();
                                for (Record record_to_check_for_use : records.values()) {
                                    if (records_not_to_use.contains(record_to_check_for_use.id)) {
                                        record_year_link_not_to_use_map.put(record_to_check_for_use.year, new TreeSet<>(record_to_check_for_use.links));
                                    }
                                }

                                // This should remove the links for records that should not be used, resulting in the correct year in the
                                // following piece of code.
                                for (Map.Entry<Integer, Set<String>> record_year_link_entry : record_year_link_map.entrySet()) {
                                    try {
                                        record_year_link_entry.getValue().removeIf(record_link -> record_year_link_not_to_use_map.get(record_year_link_entry.getKey()).contains(record_link));
                                    } catch (NullPointerException ex) {
//                                    System.out.println(getLineNumber() + " -> " + ex);
                                    }
                                }

                                // TODO check on ids from records not to use???
                                // TODO use unique values to calculate from??
                                // looping through the collected records to determine which year is complete in the sense of links
                                for (Map.Entry<Integer, Set<String>> record_year_link_entry : record_year_link_map.entrySet()) {
                                    if (record_year_link_entry.getValue().containsAll(record.getValue().links) && record.getValue().links.size() == record_year_link_entry.getValue().size()) {
                                        closest_year_for_calculating_number_of_homes = record_year_link_entry.getKey();
                                    } else {
                                        while (record_year_link_entry.getValue().size() > record.getValue().links.size()) {
//                                        System.out.println(getLineNumber() + " -> " + record.getValue().id);
                                            Iterator<String> it = record_year_link_entry.getValue().iterator();
                                            List<String> links = new ArrayList<>();
                                            while (it.hasNext()) {
                                                String link = it.next();
                                                for (Map.Entry<String, Set<String>> code_hier : codeHierarchy.entrySet()) {
                                                    if (!record.getValue().links.contains(link)) {
                                                        if (code_hier.getValue().contains(link)) {
                                                            it.remove();
                                                            links.add(code_hier.getKey());
                                                        }
                                                    }
                                                }
                                            }
                                            if (links.size() == 0) {
                                                break;
                                            }
                                            record_year_link_entry.getValue().addAll(links);
                                            if (record_year_link_entry.getValue().containsAll(record.getValue().links) && record.getValue().links.size() == record_year_link_entry.getValue().size()) {
                                                closest_year_for_calculating_number_of_homes = record_year_link_entry.getKey();
                                            }
                                        }
                                    }
                                }

                                for (String record_not_to_use : records_not_to_use)
                                    uniqueValuesToCalculateFrom.remove(record_not_to_use);


                                Map<BigDecimal, List<String>> valuesToCalculateWithMap = new HashMap<>();
                                NoteState noteState = null;
                                // TODO check why one of the values in values to calculate with map has six links...
                                // Loop through the Map of unique values to perform the calculation
                                for (Map.Entry<String, BigDecimal> uniqueValue : uniqueValuesToCalculateFrom.entrySet()) {
                                    // Loop through the records to get the record for which the id and year are the same as the given unique value
                                    for (Map.Entry<String, Record> unique_value_record : records.entrySet()) {
                                        //todo Change the values to calculate with are determined...
                                        if (unique_value_record.getValue().id.equals(uniqueValue.getKey()) && unique_value_record.getValue().year == closest_year_for_calculating_number_of_homes) {
                                            valuesToCalculateWithMap.put(uniqueValue.getValue(), unique_value_record.getValue().links);
                                            if (unique_value_record.getValue().note != NoteState.SOURCE)
                                                noteState = unique_value_record.getValue().note;
                                        }
                                    }
                                }

                                BigDecimal total_to_calculate_from = new BigDecimal(0);
                                for (BigDecimal bd : valuesToCalculateWithMap.keySet()) {
                                    if (bd == null)
                                        total_to_calculate_from = total_to_calculate_from.add(BigDecimal.ZERO);
                                    else
                                        total_to_calculate_from = total_to_calculate_from.add(bd);
                                }

                                if (total_to_calculate_from.compareTo(BigDecimal.ZERO) != 0 || total_to_calculate_from != null) {
                                    // TODO 2018-05-17 add the piece of code from above to calculate the number of homes per link code!
                                    try {
                                        for (Map.Entry<String, BigDecimal> entry_to_recalculate : equalValueToCalculate.entrySet()) {
                                            Map<BigDecimal, List<String>> calculated_home_values = new HashMap<>();
                                            for (Map.Entry<BigDecimal, List<String>> entry : valuesToCalculateWithMap.entrySet()) {
                                                BigDecimal ratio = BigDecimal.ZERO;
                                                if (entry.getKey() != null)
                                                    ratio = entry.getKey().divide(total_to_calculate_from, 9, BigDecimal.ROUND_HALF_EVEN);
                                                BigDecimal result;
                                                if (entry_to_recalculate.getValue() != null)
                                                    result = ratio.multiply(entry_to_recalculate.getValue());
                                                else
                                                    result = new BigDecimal(0);
                                                calculated_home_values.put(result, entry.getValue());
                                            }

                                            BigDecimal number_of_homes_validator = new BigDecimal(0);
                                            for (Map.Entry<BigDecimal, List<String>> calculated_home_value : calculated_home_values.entrySet()) {
                                                Record newRecord = new Record();
                                                newRecord.houses = calculated_home_value.getKey().setScale(3, BigDecimal.ROUND_HALF_EVEN);
                                                newRecord.year = records.get(entry_to_recalculate.getKey()).year;
                                                newRecord.links = calculated_home_value.getValue();
                                                newRecord.yearUsedToCalculate = closest_year_for_calculating_number_of_homes;
                                                if (noteState != null)
                                                    newRecord.note = noteState;
                                                else
                                                    newRecord.note = NoteState.YEAR_SOURCE;
                                                newRecord.id = entry_to_recalculate.getKey() + newRecord.year + newRecord.links.toString() + newRecord.houses;
                                                number_of_homes_validator = number_of_homes_validator.add(newRecord.houses);

                                                if (!recordsToAdd.containsKey(newRecord.id)) {
                                                    recordsToAdd.put(newRecord.id, newRecord);
                                                }
                                            }
                                            // TODO print all records that come by here!!! (recordstoremove)
                                            recordsToRemove.add(entry_to_recalculate.getKey());
                                        }
                                    } catch (ArithmeticException ex) {
//                                        System.out.println(getLineNumber() + " -> " + ex + " - " + record.getValue() + " - " + total_to_calculate_from + " vs " + BigDecimal.ZERO);
                                    }
                                } else {
                                    splitRecordWithNullHomes(record);
                                    recordsToRemove.add(record.getValue().id);
                                }
                                tryAlternativeSplitting = false;
                            }
                        }
                    }
                }

                for (String id : recordsToRemove) {
                    records.remove(id);
                }
                recordsToRemove.clear();

                for (Map.Entry<String, Record> record_to_add : recordsToAdd.entrySet()) {
                    record_to_add.getValue().id = Integer.toString(record_id_counter);
                    records.put(Integer.toString(record_id_counter), record_to_add.getValue());
                    record_id_counter++;
                }
                recordsToAdd.clear();

                BigDecimal number_of_homes = new BigDecimal(0);
                for (Record record : records.values()) {
                    number_of_homes = record.houses != null ? number_of_homes.add(record.houses) : number_of_homes.add(new BigDecimal(0));
                }

                int duplicate_link_code_validator = 0;
                for (Record record : records.values()) {
                    if (record.links.size() > 1) {
                        duplicate_link_code_validator++;
                    }
                }

                if (duplicate_link_code_validator == number_of_records_with_multiple_links) {
                    if (tried_with_number_of_homes)
                        if (duplicate_link_code_validator > 0) {
                            tryAlternativeSplitting = true;
                            tried_with_number_of_homes = false;
                        } else
                            break;
                    else {
                        tried_with_number_of_homes = true;
                    }
                } else {
                    number_of_records_with_multiple_links = duplicate_link_code_validator;
                    codeHierarchy.clear();
                    codesToIds.clear();
                    updateLinkRelations();
                    updateLinks();
                }
            }

            // ################################################################################# //
            // Making sure the records have link codes as small as possible.
            // Whilst recalculating the number of homes per record.
            int number_of_records = records.size();
            int future_number_of_records = 0;
            while (number_of_records != future_number_of_records) {
                Map<Record, Set<String>> recordsToSplitToSmallerLinks = new HashMap<>();
                for (Map.Entry<String, Set<String>> code_hier_entry : codeHierarchy.entrySet()) {
                    for (Record record : records.values()) {
                        if (record.links.contains(code_hier_entry.getKey())) {
                            if (code_hier_entry.getValue().size() > 1) {
                                Set<String> codes = new TreeSet<>();
                                try {
                                    for (String child : code_hier_entry.getValue()) {
                                        if (codeHierarchy.get(child).size() > 1) {
                                            codes.addAll(codeHierarchy.get(child));
                                        }
                                    }
                                } catch (NullPointerException ex) {
                                    codes.addAll(code_hier_entry.getValue());
                                }
                                recordsToSplitToSmallerLinks.put(record, codes);
                            }
                        }
                    }
                }

                Map<String, List<Record>> recordsToSplitWith = new HashMap<>();
                for (Map.Entry<Record, Set<String>> record_to_split : recordsToSplitToSmallerLinks.entrySet()) {
                    List<Record> recordsToAdd = new ArrayList<>();
                    for (Record record : records.values()) {
                        if (record_to_split.getValue().contains(record.links.get(0)) || record_to_split.getValue().contains(record.links.get(0))) {
                            boolean can_be_added = false;
                            if (recordsToAdd.size() > 0) {
                                for (Record test_record : recordsToAdd) {
                                    if (test_record.year == record.year) {
                                        can_be_added = true;
                                    }
                                }
                                if (can_be_added) {
                                    recordsToAdd.add(record);
                                }
                            } else {
                                recordsToAdd.add(record);
                            }
                        }
                    }
                    recordsToSplitWith.put(record_to_split.getKey().links.get(0), recordsToAdd);
                }


                List<Record> recordsToRemoveFromDataset = new ArrayList<>();
                List<Record> recordsToAddToDataset = new ArrayList<>();
                for (Map.Entry<String, List<Record>> recordEntry : recordsToSplitWith.entrySet()) {
                    try {
                        BigDecimal totalNumber = new BigDecimal(0);
                        for (Record record_to_collect_homes : recordEntry.getValue()) {
                            totalNumber = totalNumber.add(record_to_collect_homes.houses == null ? BigDecimal.ZERO : record_to_collect_homes.houses);
                        }
                        for (Record record_to_calculate_with : recordEntry.getValue()) {
                            BigDecimal ratio = record_to_calculate_with.houses.divide(totalNumber, 3, BigDecimal.ROUND_HALF_EVEN);
                            for (Map.Entry<Record, Set<String>> recordsToSplit : recordsToSplitToSmallerLinks.entrySet()) {
                                if (recordsToSplit.getValue().contains(record_to_calculate_with.links.get(0)) || recordsToSplit.getValue().contains(record_to_calculate_with.links.get(0).substring(0, record_to_calculate_with.links.get(0).length()))) {
                                    if (recordsToSplit.getKey().links.contains("HO3550"))
                                        getLineNumber();
                                    BigDecimal result;
                                    if (recordsToSplit.getKey().houses == null) {
                                        result = null;
                                    } else {
                                        try {
                                            result = ratio.multiply(recordsToSplit.getKey().houses);
                                        } catch (NullPointerException ex) {
                                            result = BigDecimal.ZERO;
                                        }
                                    }
                                    Record newRecord = new Record();
                                    newRecord.year = recordsToSplit.getKey().year;
                                    newRecord.links.addAll(record_to_calculate_with.links);
                                    newRecord.km2 = null;
                                    newRecord.houses = result;
                                    newRecord.yearUsedToCalculate = recordsToSplit.getKey().yearUsedToCalculate;
                                    newRecord.note = recordsToSplit.getKey().note;
                                    recordsToAddToDataset.add(newRecord);
                                }
                                if (!recordsToRemoveFromDataset.contains(recordsToSplit.getKey())) {
                                    recordsToRemoveFromDataset.add(recordsToSplit.getKey());
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.out.println(getLineNumber() + " -> " + ex + " " + recordEntry);
                    }
                }
                number_of_records = records.size();

                for (Record record : recordsToRemoveFromDataset) {
                    records.remove(record.id);
                }

                for (Record record : recordsToAddToDataset) {
                    record.id = Integer.toString(record_id_counter);
                    records.put(Integer.toString(record_id_counter), record);
                    record_id_counter++;
                }
                future_number_of_records = records.size();
                System.out.println(getLineNumber() + " -> " + number_of_records + " vs " + future_number_of_records);
            }
            // End of splitting the record links from parents to children
            // ################################################################################# //

            BigDecimal number_of_homes = new BigDecimal(0);
            for (Record record : records.values()) {
                number_of_homes = record.houses != null ? number_of_homes.add(record.houses) : number_of_homes.add(BigDecimal.ZERO);
            }
            System.out.println("Final number of duplicate links is: " + number_of_records_with_multiple_links);
            System.out.println("Final number of homes is: " + number_of_homes);

            // Looping through the link codes combined with the ids belonging to the code
            for (String entry : codes) {
                VillageComplex village = new VillageComplex();

                // Adding the Link codes to a list for further processing, removing duplicates
                // And adding the Link to the dorpenComplex map for processing
                if (!codesUsed.contains(entry)) {
                    if (records.values().stream().anyMatch(map -> map.links.contains(entry))) {
                        codesUsed.add(entry);
                        SortedSet<String> other_link_codes = new TreeSet<>();
                        other_link_codes.add(entry);
                        village.linkCode = new Tuple(entry, "0", NoteState.SOURCE, other_link_codes, 0);
                    }
                }

                // Checking to see if the dorpencomplex Hashmap is empty
                if (village.linkCode != null) {
                    // Looping through the records collected from the CSV file
                    for (Map.Entry<String, Record> recordEntry : records.entrySet()) {
                        // Check if the record entry attribute 'links' contains the Link code from dorpencomplex Hashmap
                        // If so, it adds the place of the record entry in the dorpencomplex Hashmap
                        for (String link : recordEntry.getValue().links) {
                            if (link.equals(village.linkCode.key)) {
                                // Tries to put the year belonging to the record in the Hashmap dorpenComplex
                                try {
                                    if (recordEntry.getValue().links.size() == 1) {
                                        village.linkCode.otherLinkCodes.addAll(recordEntry.getValue().links);
                                        // TODO: 07-05-2018 change this set to a String, seeing as the links have been split in all the records that are available...
                                        SortedSet<String> set = new TreeSet<>(recordEntry.getValue().links);
                                        village.yearMap.put(Integer.toString(recordEntry.getValue().year), new Tuple(recordEntry.getValue().houses != null ? recordEntry.getValue().houses.toString() : "N/A", recordEntry.getValue().id, recordEntry.getValue().note, set, recordEntry.getValue().yearUsedToCalculate));
                                    }
                                    break;
                                } catch (NullPointerException e) {
                                    System.out.println("NULLPOINTER!!");
                                }
                            }
                        }
                    }

                    // Checks if the length of the Link Code is longer than 6 characters
                    // If so, it gets the code from the codeHierarchy, by checking if the codehierarchy code equals the beginning of the dorpencomplex code
                    dorpenCollected.put(village.linkCode.key.toString(), village);
                }
            }

            // NOTE using for testing purposes
//            System.out.println(getLineNumber() + " -> Printing the values to houses export.csv");
//            for (Integer year : years_from_data) {
//                for (Record record : records.values()) {
//                    if (record.year == year) {
//                        List<String> dorpenOutput = new ArrayList<>();
//                        for (String s : headerRow) {
//                            try {
//                                switch (s) {
//                                    case "YEAR":
//                                        dorpenOutput.add(Integer.toString(record.year));
//                                        break;
//                                    case "LINK":
//                                        dorpenOutput.add(record.links.toString());
//                                        break;
//                                    case "HOUSES":
//                                        dorpenOutput.add(record.houses.toString());
//                                        break;
//                                    case "KM2":
//                                        dorpenOutput.add(record.km2.toString());
//                                        break;
//                                    default:
//                                        break;
//                                }
//                            } catch (Exception e) {
//                                dorpenOutput.add("N/A");
//                            }
//                        }
//                        csvPrinter.printRecord(dorpenOutput);
//                    }
//                }
//            }
//            csvPrinter.flush();
//            csvPrinter.close();

            // Converts the dorpenCollected so it can be saved in the CSV file!
            for (Map.Entry<String, VillageComplex> dorpCollected : dorpenCollected.entrySet()) {
                List<String> dorpenOutput = new ArrayList<>();
                for (String s : headerRow) {
                    try {
                        switch (s) {
                            case "Code":
                                dorpenOutput.add(dorpCollected.getValue().linkCode.key.toString());
                                break;
                            default:
                                if (dorpCollected.getValue().yearMap.get(s).key != null && !dorpCollected.getValue().yearMap.get(s).key.toString().equals("N/A")) {
                                    if (new BigDecimal(dorpCollected.getValue().yearMap.get(s).key.toString()).compareTo(BigDecimal.ZERO) == 0) {
                                        dorpenOutput.add("0");
                                    } else {
                                        BigDecimal numberOfHomes = new BigDecimal(dorpCollected.getValue().yearMap.get(s).key.toString()).setScale(3, BigDecimal.ROUND_HALF_EVEN);
                                        dorpenOutput.add(numberOfHomes.toString());
                                    }
                                } else {
                                    dorpenOutput.add("N/A");
                                }
                                break;
                        }
                    } catch (NullPointerException e) {
                        dorpenOutput.add("N/A");
                    }
                }
                csvPrinter.printRecord(dorpenOutput);
            }
            csvPrinter.flush();
            csvPrinter.close();
        }

        // Prints the notes data to a separate CSV file.
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(notesPath))) {
            CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat);

            List<String> headerRow = new ArrayList<>();
            headerRow.add("Code");
            @SuppressWarnings("ComparatorMethodParameterNotUsed")
            List<String> temp_list = years_from_data.stream().sorted((t1, t2) -> (t1 <= t2) ? -1 : 1).map(map -> Integer.toString(map)).collect(Collectors.toList());
            headerRow.addAll(temp_list);

            csvPrinter.printRecord(headerRow);

            // Converts the dorpenCollected so it can be saved in the CSV file!
            for (Map.Entry<String, VillageComplex> dorpCollected : dorpenCollected.entrySet()) {
                List<String> dorpenOutput = new ArrayList<>();
                for (String s : headerRow) {
                    try {
                        switch (s) {
                            case "Code":
                                dorpenOutput.add(dorpCollected.getValue().linkCode.key.toString());
                                break;
                            default:
                                dorpenOutput.add(dorpCollected.getValue().yearMap.get(s).noteState.getState(dorpCollected.getValue().yearMap.get(s).otherYear));
                                break;
                        }
                    } catch (NullPointerException e) {
                        dorpenOutput.add("N/A");
                    }
                }
                csvPrinter.printRecord(dorpenOutput);
            }
            csvPrinter.flush();
            csvPrinter.close();
        }
    }

    private static void splitRecordWithNullHomes(Map.Entry<String, Record> record) {
        for (String link : record.getValue().links) {
            Record newRecord = new Record();
            newRecord.year = record.getValue().year;
            newRecord.links.add(link);
            newRecord.houses = null;
            newRecord.note = NoteState.SOURCE;

            newRecord.id = record.getKey() + record.getValue().year + newRecord.links.toString() + newRecord.houses;
            if (!recordsToAdd.containsKey(newRecord.id)) {
                recordsToAdd.put(newRecord.id, newRecord);
            }
        }
    }

    private static boolean createNewRecordBasedOnSquareKilometres(Map<String, BigDecimal> squareKilometresToCalculateWithMap, String link_code, BigDecimal totalSquareKilometres, Record record) {
        boolean record_added;
        BigDecimal toCalculateBigDecimal = squareKilometresToCalculateWithMap.get(link_code);
        try {
            BigDecimal ratio;
            ratio = toCalculateBigDecimal.divide(totalSquareKilometres, BigDecimal.ROUND_HALF_EVEN);
            BigDecimal numberOfHomes = record.houses.multiply(ratio).setScale(3, BigDecimal.ROUND_HALF_EVEN);
            record_added = createNewRecord(record, numberOfHomes, link_code, toCalculateBigDecimal, NoteState.YEAR_SURFACE, record.year);
        } catch (Exception e) {
            record_added = false;
        }
        return record_added;
    }

    /**
     * Creates a new record based upon the information provided
     *
     * @param record        Record The record from which the initial data comes from, used for the year and id for the new record.
     * @param numberOfHomes BigDecimal The number of homes the new record has
     * @param linkCode      String The link code for the new Record
     * @param km2           BigDecimal The km2 of the record for that year
     */
    private static boolean createNewRecord(Record record, BigDecimal numberOfHomes, String linkCode, BigDecimal km2, NoteState noteState, int yearUsedToCalculate) {
        boolean record_added = false;
        Record newRecord = new Record();
        newRecord.year = record.year;
        newRecord.links.add(linkCode);
        newRecord.km2 = km2;
        newRecord.houses = numberOfHomes;
        newRecord.yearUsedToCalculate = yearUsedToCalculate;
        newRecord.note = noteState;

        newRecord.id = record.id + record.year + linkCode + newRecord.houses;

        if (!recordsToAdd.containsValue(newRecord)) {
            record_added = true;
            recordsToAdd.put(newRecord.id, newRecord);
        }
        return record_added;
    }

    /**
     * Collects the square kilometres that are needed to calculate the new number of homes for the specific record.
     *
     * @param record                             Map.Entry<String, Record> contains the record entry to be checked from the list of records.
     * @param code_map                           Map<String, Set<String>> contains the ids belonging to a specific Link Code
     * @param squareKilometresToCalculateWithMap Map<String, BigDecimal> the map in which the square kilometres will be put along with the Link Code
     * @param totalSquareKilometres              BigDecimal in which the total of square kilometres for a specific Link Code is held.
     * @return BigDecimal
     */
    private static BigDecimal collectSquareKmsToCalculateWith(Map.Entry<String, Record> record, Map<String, Set<String>> code_map, Map<String, BigDecimal> squareKilometresToCalculateWithMap, BigDecimal totalSquareKilometres) {
        for (Map.Entry<String, Set<String>> code_to_id : code_map.entrySet()) {
            for (SquareKilometreRecord srecord : squareKilometreRecords) {
                if (srecord.linkCode.equals(code_to_id.getKey())) {
                    if (srecord.km2.get(record.getValue().year) != null) {
                        squareKilometresToCalculateWithMap.put(code_to_id.getKey(), srecord.km2.get(record.getValue().year));
                        totalSquareKilometres = totalSquareKilometres.add(srecord.km2.get(record.getValue().year));
                    }
                }
            }
        }
        return totalSquareKilometres;
    }

    /**
     * Determines the closest year with which the new number of homes will be calculated with.
     *
     * @param years                          TreeSet<Integer> holds the years belonging to the Link Codes
     * @param equalValueToCalculate          Map<String, BigDecimal> contains the values to calculate which have equal Link Codes.
     * @param year_diff                      Integer holds the difference of the closest years, needed to determine the closest year
     * @param closest_year_to_calculate_from Integer will hold the closest year to calculate from
     * @return Integer the closest year to calculate the new number of homes
     */
    private static Integer determineClosestYearForCalculatingNumberOfHomes(TreeSet<Integer> years, Map<String, BigDecimal> equalValueToCalculate, Integer year_diff, Integer closest_year_to_calculate_from) {
        Integer yearToCheck;
        for (Map.Entry<String, Record> calculate_record : records.entrySet()) {
            // Get the year to perform the check upon */
            if (equalValueToCalculate.containsKey(calculate_record.getValue().id)) {
                yearToCheck = calculate_record.getValue().year;

                // Determine which year is closest to the year to calculate the values for
                for (Integer yearToCalculateFrom : years) {
                    Integer timeBetweenYears = yearToCalculateFrom - yearToCheck;
                    if (timeBetweenYears < 0) {
                        timeBetweenYears = timeBetweenYears * -1;
                    }
                    if (timeBetweenYears < year_diff) {
                        closest_year_to_calculate_from = yearToCalculateFrom;
                        year_diff = timeBetweenYears;
                    }
                }
            }

        }
        return closest_year_to_calculate_from;
    }

    /**
     * Determines the unique values to calculate the new number of homes from
     *
     * @param uniques                     Set<String> holds the ids of the unique records, e.g. records with only one Link Code
     * @param years                       TreeSet<Integer> holds the years belonging to the Link Code(s)
     * @param uniqueValuesToCalculateFrom Map<String, BigDecimal> contains the unique values to calculate the new number of homes from.
     */
    private static void determineUniqueValuesToCalculateFrom(Set<String> uniques, TreeSet<Integer> years, Map<String, BigDecimal> uniqueValuesToCalculateFrom) {
        for (String unique_code : uniques) {
            for (Map.Entry<String, Record> unique_record : records.entrySet()) {
                if (unique_record.getValue().id.equals(unique_code)) {
                    uniqueValuesToCalculateFrom.put(unique_code, unique_record.getValue().houses);
                    if (!years.add(unique_record.getValue().year)) {
                        years.add(unique_record.getValue().year);
                    }
                }
            }
        }
    }

    /**
     * Removes ids from the unique Set if the Link Codes for that id don't complete the parent Link Code.
     *
     * @param record  Map.Entry<String, Record> the record to check the link codes from
     * @param uniques Set<String> the Set from which to remove the ids of incomplete Link Codes.
     */
    private static void removeIncompleteChildLinkCodes(Map.Entry<String, Record> record, Set<String> uniques) {
        for (Record record_unique : records.values()) {
            if (uniques.contains(record_unique.id)) {
                for (String s : record_unique.links) {
                    if (!record.getValue().links.contains(s)) {
                        uniques.remove(record_unique.id);
                    }
                }
            }
        }
    }

    /**
     * Collects duplicate Link Codes, E.G. checks which Link Codes contain the same record ids and saves them to the duplicates set
     *
     * @param duplicates Set<String> contains the duplicate ids
     * @param code_map   Map<String, Set<String> contains the ids belonging to a Link Code
     * @return Set<String> which contains the duplicate ids
     */
    private static Set<String> collectDuplicateLinkCodes(Set<String> duplicates, Map<String, Set<String>> code_map) {
        for (String key : code_map.keySet()) {
            if (duplicates == null) {
                duplicates = new TreeSet<>(code_map.get(key));
            } else {
                duplicates.retainAll(code_map.get(key));
            }
        }
        return duplicates;
    }

    /**
     * Collects unique Link Codes, E.G. checks which Link Codes don't contain the same record ids and saves them to the uniques set
     *
     * @param uniques  Set<String> contains the unique ids
     * @param code_map Map<String, Set<String> contains the ids belonging to a Link Code
     * @return Set<String> which contains the unique ids
     */
    private static Set<String> collectUniquesLinkCodes(Set<String> uniques, Map<String, Set<String>> code_map) {
        for (String key : code_map.keySet()) {
            if (uniques == null) {
                uniques = new TreeSet<>(code_map.get(key));
            } else {
                uniques.addAll(code_map.get(key));
            }
        }
        return uniques;
    }

    /**
     * Determines the Link Codes to remove from the code_map and also the Link Codes to add to the code_map.
     * Depending on whether or not the Link Codes are (complete) children of a parent Link Code
     *
     * @param record   Map.Entry<String, Record> the entry to check for if the link codes complete the parent code
     * @param code_map Map<String, Set<String> the map containing the Link Code with the ids that contain that Link Code
     */
    private static void determineCodesToRemoveAndToAppend(Map.Entry<String, Record> record, Map<String, Set<String>> code_map) {
        for (String link : record.getValue().links) {
            Set<String> testSet = codeHierarchy.get(link.substring(0, link.length() - 1)) != null ? codeHierarchy.get(link.substring(0, link.length() - 1)) : new TreeSet<>();
            if (code_map.values().containsAll(testSet)) {

                Map<String, Set<String>> code_map_to_append = new TreeMap<>();
                Set<String> codes_to_remove = new TreeSet<>();
                Map.Entry<String, Set<String>> firstEntry = code_map.entrySet().iterator().next();
                for (Map.Entry<String, Set<String>> next : code_map.entrySet()) {
                    if (!next.equals(firstEntry)) {
                        if (next.getKey().startsWith("HO")) {
                            if (next.getKey().substring(0, 6).equals(firstEntry.getKey().substring(0, 6)) && next.getValue().equals(firstEntry.getValue())) {
                                codes_to_remove.add(firstEntry.getKey());
                                codes_to_remove.add(next.getKey());
                                code_map_to_append.put(firstEntry.getKey().substring(0, 6), firstEntry.getValue());
                            } else {
                                codes_to_remove.clear();
                            }
                        } else {
                            if (next.getKey().equals(firstEntry.getKey()) && next.getValue().equals(firstEntry.getValue())) {
                                codes_to_remove.add(firstEntry.getKey());
                                codes_to_remove.add(next.getKey());
                                code_map_to_append.put(firstEntry.getKey(), firstEntry.getValue());
                            } else {
                                codes_to_remove.clear();
                            }
                        }
                        firstEntry = next;
                    }
                }

                for (String code_to_remove : codes_to_remove) {
                    code_map.remove(code_to_remove);
                }
                code_map.putAll(code_map_to_append);
            }
        }
    }

    /**
     * Fills the code_map based on the codeToIds Map.
     * This by checking whether or not the codeToIds contain the Link code.
     *
     * @param record   Map.Entry<String, Record> the entry to check for in the codeToIds map
     * @param code_map Map<String, Set<String> the map containing the Link Code with the ids that contain that Link Code
     */
    private static void fillCodeMapBasedOnCodeToIds(Map.Entry<String, Record> record, Map<String, Set<String>> code_map) {
        for (String link : record.getValue().links) {
            for (Map.Entry<String, Set<String>> codeToIdEntry : codesToIds.entrySet()) {
                if (codesToIds.containsKey(link)) {
                    if (codeToIdEntry.getKey().equals(link)) {
                        if (code_map.entrySet().stream().noneMatch(map -> map.getValue().equals(codeToIdEntry))) {
                            code_map.put(codeToIdEntry.getKey(), codeToIdEntry.getValue());
                        }
                    }
                } else {
                    List<Set<String>> childLinks = codeHierarchy.entrySet().stream().filter(map -> map.getKey().equals(link)).map(Map.Entry::getValue).collect(Collectors.toList());
                    if (childLinks.size() > 0) {
                        for (String child : childLinks.get(0)) {
                            if (codeToIdEntry.getKey().equals(child)) {
                                if (code_map.entrySet().stream().noneMatch(map -> map.getValue().contains(child)) && codeToIdEntry.getValue().contains(record.getKey())) {
                                    code_map.put(codeToIdEntry.getKey(), codeToIdEntry.getValue());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Updates the relations between the ids and the Link Codes aswell the Parent to Child relation for the Link Codes
     */
    private static void updateLinkRelations() {
        for (Record record : records.values()) {
            for (String code : record.links) {
                Set<String> ids = codesToIds.getOrDefault(code, new HashSet<>());
                ids.add(record.id);
                codesToIds.put(code, ids);

                if (code.contains("HO")) {
                    setParentRelation(code);
                }
            }
        }
    }

    /**
     * Calculates the number of homes for the new records by using the closest year.
     * This only works if the record contains two Link Codes
     *
     * @param upcomingYear          Pair contains the year and the values to calculate from.
     * @param numberOfHousesToSplit BigDecimal contains the number of homes to split for the new records.
     * @return Pair containing the new number of homes
     */
    private static Pair getNumberOfHousesInAccordanceToUpcomingYear(Pair upcomingYear, BigDecimal numberOfHousesToSplit) {
        BigDecimal lowestResult;
        BigDecimal highestResult;
        if (upcomingYear.lowestNumber == null) {
            lowestResult = null;
            highestResult = numberOfHousesToSplit;
        } else if (upcomingYear.highestNumber == null) {
            lowestResult = numberOfHousesToSplit;
            highestResult = null;
        } else {
            BigDecimal percentage = upcomingYear.lowestNumber.divide(upcomingYear.highestNumber.add(upcomingYear.lowestNumber), 9, BigDecimal.ROUND_HALF_EVEN); //.setScale(9, BigDecimal.ROUND_HALF_EVEN);
            lowestResult = numberOfHousesToSplit.multiply(percentage).setScale(3, BigDecimal.ROUND_HALF_EVEN);
            highestResult = numberOfHousesToSplit.subtract(lowestResult).setScale(3, BigDecimal.ROUND_HALF_EVEN);
        }
        return new Pair(lowestResult, highestResult);
    }

    /**
     * Class to contain multiple values for further calculation
     */
    private static class Tuple {
        Object key;
        String value;
        SortedSet<String> otherLinkCodes;
        NoteState noteState;
        int otherYear;

        /**
         * Constructor for the Tuple class
         *
         * @param key            Object
         * @param value          String
         * @param noteState      NoteState
         * @param otherLinkCodes SortedSet<String>
         */
        Tuple(Object key, String value, NoteState noteState, SortedSet<String> otherLinkCodes, int otherYear) {
            this.key = key;
            this.value = value;
            this.noteState = noteState;
            this.otherLinkCodes = otherLinkCodes;
            this.otherYear = otherYear;
        }
    }

    /**
     * Class to contain the lowest and highest number of homes
     */
    private static class Pair {
        BigDecimal lowestNumber;
        BigDecimal highestNumber;

        /**
         * Constructor for the class Pair
         *
         * @param low  BigDecimal low number of homes
         * @param high BigDecimal high number of homes
         */
        Pair(BigDecimal low, BigDecimal high) {
            lowestNumber = low;
            highestNumber = high;
        }

        /**
         * Returns the lowest and highest number in a String
         *
         * @return String
         */
        public String toString() {
            return "Lowestnumber is: " + lowestNumber + " Highestnumber is: " + highestNumber;
        }
    }

    /**
     * The Record class containing information about each Record
     */
    private static class Record {
        String id;
        int year;
        BigDecimal houses;
        BigDecimal km2;
        List<String> links = new ArrayList<>();
        NoteState note;
        int yearUsedToCalculate;

        /**
         * Returns the Record as a String
         *
         * @return String
         */
        public String toString() {
            return "ID: " + id + " - Year: " + year + " - Houses: " + houses + " - KM2: " + km2 + " - Links: " + links.toString() + " - Note: " + note + " - Year used to calculate: " + yearUsedToCalculate;
        }
    }

    /**
     * The SquareKilometreRecord class containing square kilometres per Link Code
     */
    private static class SquareKilometreRecord implements Comparable<SquareKilometreRecord> {
        String linkCode;
        Map<Integer, BigDecimal> km2 = new HashMap<>();

        /**
         * Compares the given SquareKilometreRecord with the current SquareKilometreRecord
         *
         * @param sq SquareKilometreRecord containing information about the square kilometres for a specific Link Code
         * @return Integer
         */
        public int compareTo(SquareKilometreRecord sq) {
            return linkCode.compareTo(sq.linkCode);
        }

        /**
         * Returns the value of the SquareKilometreRecord as a String
         *
         * @return String
         */
        public String toString() {
            return linkCode + " - " + km2.keySet() + " - " + km2.values();
        }
    }

    /**
     * The VillageComplex Class used to output the data to the CSV
     */
    private static class VillageComplex {
        Tuple linkCode;
        Map<String, Tuple> yearMap = new HashMap<>();

        /**
         * Returns the toString value of VillageComplex
         *
         * @return String
         */
        public String toString() {
            return "Linkcode value: " + linkCode.value +
                    " Linkcode key: " + linkCode.key +
                    " Linkcode otherLinkCodes: " + linkCode.otherLinkCodes +
                    " Linkcode noteState: " + linkCode.noteState +
                    " yearMap: " + yearMap;
        }
    }

    /**
     * Get the current line number.
     *
     * @return int - Current line number.
     */
    private static int getLineNumber() {
        return Thread.currentThread().getStackTrace()[2].getLineNumber();
    }
}
