package org.iish.dorpen;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.text.*;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static final TreeMap<String, Record> records = new TreeMap<>(); // Contains the records from the csv
    private static Set<SquareKilometreRecord> squareKilometreRecords = new TreeSet<>();
    private static final Map<String, Set<String>> codesToIds = new HashMap<>(); // Contains information about link codes belonging to the ids
    private static final Map<String, Set<String>> codeHierarchy = new HashMap<>(); // Contains information about possible children of parents
    private static final List<String> codes = new ArrayList<>(); // Contains all the codes from the csv file
    private static BigDecimal numberOfHouses = new BigDecimal(0);
    private static int record_id_counter = 2;
    private static Set<String> recordsToRemove = new TreeSet<>();
    private static final Map<String, Record> recordsToAdd = new HashMap<>();

    private static final CSVFormat csvFormat = CSVFormat.EXCEL
            .withFirstRecordAsHeader()
            .withDelimiter(';')
            .withIgnoreEmptyLines()
            .withNullString("");

    /**
     * The main method to start it all
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        String importCsv = args[0];
        String importSquareKilometres = args[1];
        String exportCsv = args[2];

        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        System.out.println(dateFormat.format(date)); //2016/11/16 12:08:43

        loadData(importCsv);
        loadSquareKilometers(importSquareKilometres);

        updateLinks();

        export(exportCsv);

        date = new Date();

        System.out.println(dateFormat.format(date)); //2016/11/16 12:08:43
    }

    /**
     * Converts the number given to a local BigDecimal
     *
     * @param number String
     * @return BigDecimal
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
     * @param csvPath
     * @throws Exception
     */
    private static void loadData(String csvPath) throws Exception {
        CSVParser parser = CSVParser.parse(new File(csvPath), Charset.forName("UTF-8"), csvFormat);
        parser.forEach(record -> {
            Record r = new Record();
            r.id = Integer.toString(record_id_counter);
            r.year = new Integer(record.get("YEAR"));
            r.houses = record.get("HOUSES") != null ? new BigDecimal(record.get("HOUSES")) : new BigDecimal(0);
            numberOfHouses = numberOfHouses.add(r.houses);
            r.km2 = record.get("KM2") != null ? convertToLocale(record.get("KM2")).setScale(3, BigDecimal.ROUND_HALF_EVEN) : null;
            if (record.get("LINK") != null) {
                String[] links = record.get("LINK").split("-");
                for (String code : links) {
                    if (code.substring(0, 2).contains("HO")) {
                        r.links.add(code);
                        Set<String> ids = codesToIds.getOrDefault(code, new HashSet<>());
                        ids.add(r.id);
                        codesToIds.put(code, ids);

                        setParentRelation(code);
                    } else {
                        r.links.add(code);
                        System.out.println(getLineNumber() + " - Code that not corresponds to default: " + code);
                    }
                }
            } else {
                r.links.add("");
            }
            records.put(r.id, r);
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
     * @param code
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
     * @param code
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

    /**
     * Exports the information to a csv file.
     *
     * @param exportPath
     * @throws Exception
     */
    private static void export(String exportPath) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(exportPath))) {
            CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat);

            List<String> headerRow = new ArrayList<>();
            headerRow.add("Code");
            List<String> tempList = records.values().stream().sorted((t1, t2) -> t1.year <= t2.year ? -1 : 1).map(map -> Integer.toString(map.year)).distinct().collect(Collectors.toList());
            headerRow.addAll(tempList);
            System.out.println(tempList);
            csvPrinter.printRecord(headerRow);

            List<String> codesUsed = new ArrayList<>();
            SortedMap<String, VillageComplex> dorpenCollected = new TreeMap<>();

            SortedMap<String, Set<String>> codesToIdsSorted = new TreeMap<>();
            SortedSet<String> keys = new TreeSet<>(codesToIds.keySet());
            for (String key : keys) {
                Set<String> value = codesToIds.get(key);
                codesToIdsSorted.put(key, value);
            }

            int number_of_records_with_multiple_links = 0;
            Set<String> records_to_alter = new TreeSet<>();

            for (Map.Entry<String, Record> record : records.entrySet()) {
                if (record.getValue().links.size() > 1) {
                    number_of_records_with_multiple_links++;
                }
                for (Map.Entry<String, Set<String>> code_hierarchy : codeHierarchy.entrySet()) {
                    if (record.getValue().links.containsAll(code_hierarchy.getValue())) {
                        records_to_alter.add(record.getValue().id);
                    } else if (record.getValue().links.contains(code_hierarchy.getKey())) {
                        records_to_alter.add(record.getValue().id);
                    }
                }
            }

            for (Map.Entry<String, Set<String>> code_to_id : codesToIds.entrySet()) {
                if (records_to_alter.containsAll(code_to_id.getValue())) {
                    for (Record record : records.values()) {
                        if (code_to_id.getValue().contains(record.id)) {
                            if (codeHierarchy.keySet().contains(code_to_id.getKey()) || codeHierarchy.entrySet().stream().anyMatch(map -> map.getValue().contains(code_to_id.getKey()))) {
                                record.links.remove(code_to_id.getKey());
                                String parent = "";
                                for (Map.Entry<String, Set<String>> code_hierarchy_entry : codeHierarchy.entrySet()) {
                                    if (code_hierarchy_entry.getValue().contains(code_to_id.getKey())) {
                                        parent = code_hierarchy_entry.getKey();
                                    }
                                }
                                if (!parent.equals("") && !record.links.contains(parent)) {
                                    record.links.add(parent);
                                }
                            }
                        }
                    }
                }
            }

            Collections.sort(codes);

            // TODO: Add a check to let the code first calculate by using the number of homes.
            // TODO: After that stalls, then use the km² values from the separate list.
            boolean tried_with_number_of_homes = false;
            while (number_of_records_with_multiple_links != 0) {
//                for (Map.Entry<String, Set<String>> code_to_id : codesToIds.entrySet()) {
//                    System.out.println(getLineNumber() + " -> " + code_to_id.getKey() + " - " + code_to_id.getValue());
//                }
//                System.out.println(getLineNumber() + " -> " + codeHierarchy.values());

                for (Map.Entry<String, Record> record : records.entrySet()) {
                    if (record.getValue().links.size() > 1) {
                        System.out.println(getLineNumber() + " -> " + record.getKey());
                        Map<String, Set<String>> code_map = new TreeMap<>();
                        for (String link : record.getValue().links) {
                            System.out.println(link);
                            for (Map.Entry<String, Set<String>> codeToIdEntry : codesToIds.entrySet()) {
                                if (codeToIdEntry.getKey().equals(link)) {
                                    if (code_map.entrySet().stream().noneMatch(map -> map.getValue().equals(codeToIdEntry))) {
                                        code_map.put(codeToIdEntry.getKey(), codeToIdEntry.getValue());
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

                        // NOTE: Might need it, not sure
//                            Map<String, Set<String>> code_map_to_append_2 = new HashMap<>();
//                            for(Set<String> codes : code_map.values()){
//                                for(String code : codes) {
//                                    for (Map.Entry<String, Set<String>> code_hier : codesToIds.entrySet()) {
//                                        if (code_hier.getValue().contains(code) && !code_hier.equals(codes)){
//                                            code_map_to_append_2.put(code_hier.getKey(), code_hier.getValue());
//                                        }
//                                    }
//                                }
//                            }
//                            code_map.putAll(code_map_to_append_2);

                        Map<String, Set<String>> code_map_to_append = new TreeMap<>();
                        Set<String> codes_to_remove = new TreeSet<>();
                        Map.Entry<String, Set<String>> firstEntry = code_map.entrySet().iterator().next();
                        Iterator<Map.Entry<String, Set<String>>> it = code_map.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<String, Set<String>> next = it.next();
                            if (!next.equals(firstEntry)) {
                                if (next.getKey().substring(0, 6).equals(firstEntry.getKey().substring(0, 6)) && next.getValue().equals(firstEntry.getValue())) {
                                    codes_to_remove.add(firstEntry.getKey());
                                    codes_to_remove.add(next.getKey());
                                    code_map_to_append.put(firstEntry.getKey().substring(0, 6), firstEntry.getValue());
                                }
                                firstEntry = next;
                            }
                        }


                        for (String code_to_remove : codes_to_remove) {
                            code_map.remove(code_to_remove);
                        }
                        code_map.putAll(code_map_to_append);

                        for (Map.Entry<String, Set<String>> code_to_id : code_map.entrySet()) {
                            System.out.println(getLineNumber() + " -> " + code_to_id.getKey() + " - " + code_to_id.getValue().toString());
                        }

                        // Gets the unique values and the duplicate values
                        Set<String> duplicates = null;
                        Set<String> uniques = null;
                        for (String key : code_map.keySet()) {
                            if (duplicates == null && uniques == null) {
                                duplicates = new TreeSet<>(code_map.get(key));
                                uniques = new TreeSet<>(code_map.get(key));
                            } else {
                                duplicates.retainAll(code_map.get(key));
                                uniques.addAll(code_map.get(key));
                            }
                        }
                        uniques.removeAll(duplicates);

                        System.out.println(getLineNumber() + " -> " + duplicates);
                        System.out.println(getLineNumber() + " -> " + uniques);

                        /** Looping through the unique codes to determine duplicate years in the Link codes */
                        TreeSet<Integer> years = new TreeSet<>();

                        Map<String, BigDecimal> uniqueValuesToCalculateFrom = new HashMap<>();
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

//                        System.out.println(getLineNumber() + " -> " + years + " - " + uniqueValuesToCalculateFrom.values());

                        /** Determine the value to calculate. These are the codes that are the same, ergo: the codes that need to be split up.*/
                        TreeSet<Integer> duplicateYears = new TreeSet<>();
                        Map<String, BigDecimal> equalValueToCalculate = new HashMap<>();
                        for (String duplicate : duplicates) {
                            for (Map.Entry<String, Record> duplicate_record : records.entrySet()) {
                                if (duplicate_record.getValue().id.equals(duplicate)) {
                                    equalValueToCalculate.put(duplicate, duplicate_record.getValue().houses);
                                    duplicateYears.add(duplicate_record.getValue().year);
                                }
                            }
                        }

//                        System.out.println(getLineNumber() + " -> " + equalValueToCalculate.keySet() + " - " + equalValueToCalculate.values() + " - " + duplicateYears);

                        Integer yearToCheck = Integer.MAX_VALUE;
                        Integer year_diff = Integer.MAX_VALUE;
                        Integer yearToCalculateWith = Integer.MAX_VALUE;
                        Integer year_temp_something_dunno = 0;

                        /** Loop through the map containing the values to calculate */
                        /** Looping through the records to check which record corresponds with the valueToCalculate ID */
                        for (Map.Entry<String, Record> calculate_record : records.entrySet()) {
                            /** Get the year to perform the check upon */
                            if (equalValueToCalculate.containsKey(calculate_record.getValue().id)) {
                                yearToCheck = calculate_record.getValue().year;
//                                System.out.println(getLineNumber() + " -> " + calculate_record.getValue().year);

                                /** Determine which year is closest to the year to calculate the values for */
                                for (Integer yearToCalculateFrom : years) {
                                    Integer timeBetweenYears = yearToCalculateFrom - yearToCheck;
                                    if (timeBetweenYears < 0) {
                                        timeBetweenYears = timeBetweenYears * -1;
                                    }
                                    if (timeBetweenYears < year_diff) {
//                                        System.out.println(getLineNumber() + " -> " + timeBetweenYears + " - " + year_diff + " - " + yearToCalculateFrom);
                                        year_temp_something_dunno = yearToCalculateFrom;
                                        year_diff = timeBetweenYears;
                                        yearToCalculateWith = calculate_record.getValue().year;
                                    }
                                }
                            }

                        }

//                        System.out.println(getLineNumber() + " -> " + year_diff + " - " + yearToCalculateWith);

                        Map<BigDecimal, String> valuesToCalculateWithMap = new HashMap<>();
                        /** Loop through the Map of unique values to perform the calculation */
                        for (Map.Entry<String, BigDecimal> uniqueValue : uniqueValuesToCalculateFrom.entrySet()) {
                            /** Loop through the records to get the record for which the id and year are the same as the given unique value */
                            for (Map.Entry<String, Record> unique_value_record : records.entrySet()) {
                                if (unique_value_record.getValue().id.equals(uniqueValue.getKey()) && unique_value_record.getValue().year == year_temp_something_dunno) {
                                    valuesToCalculateWithMap.put(uniqueValue.getValue(), unique_value_record.getValue().links.toString());
                                }
                            }
                        }

//                        System.out.println(getLineNumber() + " -> " + valuesToCalculateWithMap.values());

                        /** Check if the Map with unique values is bigger than 0 */
                        if (valuesToCalculateWithMap.size() > 1) {
                            System.out.println(getLineNumber() + " -> " + " Using number of homes!");

                            List<BigDecimal> values = new ArrayList<>();
                            /** Sort the values so the first value is the smallest one for calculation purposes */
                            for (Map.Entry<BigDecimal, String> value_to_calculate : valuesToCalculateWithMap.entrySet()) {
                                values.add(value_to_calculate.getKey());
                            }
                            Collections.sort(values);
//                            System.out.println(getLineNumber() + " -> " + values);

                            /** Loop through the values to calculate in order to get the correct number of houses based on the proportions */
                            for (Map.Entry<String, BigDecimal> valueToCalculate : equalValueToCalculate.entrySet()) {
                                Pair result;
                                try {
                                    result = getNumberOfHousesInAccordanceToUpcomingYear(new Pair(values.get(0), values.get(1)), valueToCalculate.getValue());
                                } catch (Exception ex) {
                                    result = new Pair(new BigDecimal(0), new BigDecimal(0));
                                }

//                                System.out.println(getLineNumber() + " -> " + result.lowestNumber + " - " + result.highestNumber + " from " + valueToCalculate.getValue() + " - " + valueToCalculate.getKey());
                                if (!result.lowestNumber.equals(new BigDecimal(0))) {

                                    /** Determine the highest number of the returned value after the calculation */
                                    String linkLowestNumber = valuesToCalculateWithMap.get(values.get(0));
                                    String linkHighestNumber = "";
                                    if (valuesToCalculateWithMap.size() > 1) {
                                        linkHighestNumber = valuesToCalculateWithMap.get(values.get(1));
                                    }
//                                    System.out.println(getLineNumber() + " -> " + valuesToCalculateWithMap + " - " + result.lowestNumber + " - " + result.highestNumber);
//                                    System.out.println(getLineNumber() + " -> " + valueToCalculate.getKey() + " - " + record.getValue().links.toString());

                                    if (valueToCalculate.getKey().equals(record.getKey())) {
                                        for (int i = 0; i < record.getValue().links.size(); i++) {
                                            Record r = new Record();
                                            r.year = record.getValue().year;
                                            r.links.add(record.getValue().links.get(i));
                                            linkLowestNumber = linkLowestNumber.replace('[', ' ');
                                            linkLowestNumber = linkLowestNumber.replace(']', ' ');
                                            linkLowestNumber = linkLowestNumber.trim();

                                            r.houses = linkLowestNumber.equals(r.links.get(0)) ? result.lowestNumber : result.highestNumber;
                                            r.id = record.getKey() + record.getValue().year + record.getValue().links.get(i) + r.houses;

                                            if (!recordsToAdd.containsValue(r)) {
                                                recordsToAdd.put(r.id, r);
                                            }
                                        }
                                        recordsToRemove.add(valueToCalculate.getKey());
                                    }
                                } else {
                                    System.out.println(getLineNumber() + " -> " + " Result lowestnumber is 0!!!");
                                }
                            }
//                            System.out.println(getLineNumber() + " -> " + recordsToAdd.values());
                        } else if (valuesToCalculateWithMap.size() <= 1 && tried_with_number_of_homes) {
                            System.out.println(getLineNumber() + " -> " + " Using square kilometres");
                            Map<String, BigDecimal> squareKilometresToCalculateWithMap = new HashMap<>();
                            BigDecimal totalSquareKilometres = new BigDecimal(0);
                            for (Map.Entry<String, Set<String>> code_to_id : code_map.entrySet()) {
                                for (SquareKilometreRecord srecord : squareKilometreRecords) {
                                    if (srecord.linkCode.equals(code_to_id.getKey())) {
                                        if (srecord.km2.get(record.getValue().year) != null) {
                                            System.out.println(getLineNumber() + " -> " + srecord.km2.get(record.getValue().year));
                                            squareKilometresToCalculateWithMap.put(code_to_id.getKey(), srecord.km2.get(record.getValue().year));
                                            totalSquareKilometres = totalSquareKilometres.add(srecord.km2.get(record.getValue().year));
                                        }
                                    }
                                }
                            }
//                            System.out.println(getLineNumber() + " -> " + record.getValue().year);
                            if (squareKilometresToCalculateWithMap.size() > 0) {
                                for (Map.Entry<String, BigDecimal> entry : squareKilometresToCalculateWithMap.entrySet()) {
                                    BigDecimal ratio = entry.getValue().divide(totalSquareKilometres, BigDecimal.ROUND_HALF_EVEN);
                                    BigDecimal numberOfHomes = record.getValue().houses.multiply(ratio).setScale(3, BigDecimal.ROUND_HALF_EVEN);
                                    Record newRecord = new Record();
                                    newRecord.year = record.getValue().year;
                                    newRecord.links.add(entry.getKey());
                                    newRecord.km2 = entry.getValue();
                                    newRecord.houses = numberOfHomes;

                                    newRecord.id = record.getKey() + record.getValue().year + entry.getKey() + newRecord.houses;

                                    if (!recordsToAdd.containsValue(newRecord)) {
                                        recordsToAdd.put(newRecord.id, newRecord);
                                    }
                                }
                                recordsToRemove.add(record.getKey());
                                System.out.println(getLineNumber() + " -> Set the tried back to false!");
                                tried_with_number_of_homes = false;
                                break;
                            }
                        }
                    }
                }

                for (String id : recordsToRemove) {
                    records.remove(id);
                }
                System.out.println(getLineNumber() + " -> " + recordsToRemove.toString());
                recordsToRemove.clear();

                for (Map.Entry<String, Record> record_to_add : recordsToAdd.entrySet()) {
                    record_to_add.getValue().id = Integer.toString(record_id_counter);
                    records.put(Integer.toString(record_id_counter), record_to_add.getValue());
                    record_id_counter++;
                }
                recordsToAdd.clear();

                BigDecimal number_of_homes = new BigDecimal(0).setScale(3, BigDecimal.ROUND_HALF_EVEN);
                List<Record> sortedRecords = records.values().stream().sorted((t1, t2) -> {
                    if (t1.year == t2.year && t1.links.size() == 1 && t2.links.size() == 1)
                        return t1.links.get(0).compareTo(t2.links.get(0));
                    return t1.year <= t2.year ? -1 : 1;
                }).collect(Collectors.toList());

                for (Record record : sortedRecords) {
                    System.out.println(getLineNumber() + " -> " + record.toString());
                    number_of_homes = number_of_homes.add(record.houses);
                }

                System.out.println(getLineNumber() + " -> New number of homes is: " + number_of_homes);

                int duplicate_link_code_validator = 0;
                for (Record record : records.values()) {
                    if (record.links.size() > 1) {
                        duplicate_link_code_validator++;
                    }
                }

                System.out.println(getLineNumber() + " -> " + duplicate_link_code_validator + " vs " + number_of_records_with_multiple_links);

                if (duplicate_link_code_validator == number_of_records_with_multiple_links) {
                    if(tried_with_number_of_homes)
                        break;
                    else {
                        System.out.println("Tried it!!");
                        tried_with_number_of_homes = true;
                    }
                } else {
                    number_of_records_with_multiple_links = duplicate_link_code_validator;
                    codeHierarchy.clear();
                    codesToIds.clear();
                    for (Record record : records.values()) {
                        for (String code : record.links) {
                            Set<String> ids = codesToIds.getOrDefault(code, new HashSet<>());
                            ids.add(record.id);
                            codesToIds.put(code, ids);

                            setParentRelation(code);
                        }
                    }
                    updateLinks();
                }
            }

            /** Looping through the link codes combined with the ids belonging to the code */
            for (String entry : codes) {
                VillageComplex village = new VillageComplex();

                /** Adding the Link codes to a list for further processing, removing duplicates
                 *  And adding the Link to the dorpenComplex map for processing */
                if (!codesUsed.contains(entry)) {
                    codesUsed.add(entry);
                    SortedSet<String> other_link_codes = new TreeSet<>();
                    other_link_codes.add(entry);
                    village.linkCode = new Tuple(entry, "0", true, other_link_codes);
                }

                /** Checking to see if the dorpencomplex Hashmap is empty*/
                if (village.linkCode != null) {
                    /** Looping through the records collected from the CSV file */
                    for (Map.Entry<String, Record> recordEntry : records.entrySet()) {
                        /** Check if the record entry attribute 'links' contains the Link code from dorpencomplex Hashmap
                         *  If so, it adds the place of the record entry in the dorpencomplex Hashmap */
                        for (String link : recordEntry.getValue().links) {
                            if (link.equals(village.linkCode.key)) {
                                /** Tries to put the year belonging to the record in the Hashmap dorpenComplex */
                                try {
                                    if (recordEntry.getValue().links.size() == 1) {
                                        village.linkCode.otherLinkCodes.addAll(recordEntry.getValue().links);
                                        SortedSet<String> set = new TreeSet<>(recordEntry.getValue().links);
                                        village.yearMap.put(Integer.toString(recordEntry.getValue().year), new Tuple(recordEntry.getValue().houses.toString(), recordEntry.getValue().id, true, set));
                                    } else {
                                        village.linkCode.otherLinkCodes.addAll(recordEntry.getValue().links);
                                        SortedSet<String> set = new TreeSet<>(recordEntry.getValue().links);
                                        village.yearMap.put(Integer.toString(recordEntry.getValue().year), new Tuple(recordEntry.getValue().houses.toString(), recordEntry.getValue().id, true, set));
                                    }
                                    break;
                                } catch (NullPointerException e) {
                                    System.out.println("NULLPOINTER!!");
                                }
                            }
                        }
                    }

                    /** Checks if the length of the Link Code is longer than 6 characters
                     *  If so, it gets the code from the codeHierarchy, by checking if the codehierarchy code equals the beginning of the dorpencomplex code */
                    if (village.linkCode.key.length() > 6) {
                        for (Map.Entry<String, Set<String>> codeHierarchyEntry : codeHierarchy.entrySet()) {
                            if (codeHierarchyEntry.getValue().contains(village.linkCode.key)) {
                                dorpenCollected.put(village.linkCode.key, village);
                            }
                        }
                    } else {
                        dorpenCollected.put(village.linkCode.key, village);
                    }
                }
            }

            /** Converts the dorpenCollected so it can be saved in the CSV file! */
            for (Map.Entry<String, VillageComplex> dorpCollected : dorpenCollected.entrySet()) {
                List<String> dorpenOutput = new ArrayList<>();
                for (String s : headerRow) {
                    try {
                        switch (s) {
                            case "Code":
                                dorpenOutput.add(dorpCollected.getValue().linkCode.key);
                                break;
                            default:
                                if (dorpCollected.getValue().yearMap.get(s).key.equals("0")) {
                                    System.out.println(getLineNumber() + " -> Number of homes is zero for: " + dorpCollected.getKey());
                                    String numberOfHouses = handleHousesBeingZero(dorpCollected, s);
                                    dorpenOutput.add(numberOfHouses);
                                    dorpCollected.getValue().yearMap.get(s).key = numberOfHouses;
                                } else {
                                    dorpenOutput.add(dorpCollected.getValue().yearMap.get(s).key);
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
        } catch (Exception e) {
            System.out.println(getLineNumber() + " -> " + e);
        }
    }

    /**
     * @param dorpCollected
     * @param year
     * @return
     */
    private static String handleHousesBeingZero(Map.Entry<String, VillageComplex> dorpCollected, String year) {
        String result = "NULL";
        String divider = "None";
        BigDecimal proportionKM2 = new BigDecimal(Integer.MAX_VALUE).setScale(3, BigDecimal.ROUND_HALF_EVEN);
        Record testRecord = new Record();
        for (Map.Entry<String, Record> record : records.entrySet()) {
            if (record.getValue().links.contains(dorpCollected.getKey()) && record.getValue().id.equals(dorpCollected.getValue().yearMap.get(year).value)) {
                testRecord = record.getValue();
            }
        }
        for (Map.Entry<String, Record> record : records.entrySet()) {
            if (record.getValue().links.contains(dorpCollected.getKey()) && !record.getValue().id.equals(dorpCollected.getValue().yearMap.get(year).value)) {
                /** Collect the proportion to calculate the empty value of dorpCollected with */
                if (!record.getValue().houses.equals(new BigDecimal(0))) {
                    BigDecimal houses = record.getValue().houses.setScale(3, RoundingMode.HALF_EVEN);
                    if (record.getValue().km2 != null && testRecord.km2 != null) {
                        BigDecimal km2 = record.getValue().km2.setScale(3, RoundingMode.HALF_EVEN);
                        proportionKM2 = houses.divide(km2, RoundingMode.HALF_EVEN);
                        divider = "km2";
                        break;
                    }
                }
            }
        }
        for (Map.Entry<String, Record> record : records.entrySet()) {
            if (!divider.equals("None")) {
                if (Integer.toString(record.getValue().year).equals(year)) {
                    if (record.getValue().id.equals(dorpCollected.getValue().yearMap.get(year).value)) {
                        if (record.getValue().km2 != null) {
                            result = record.getValue().km2.multiply(proportionKM2).toString();
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Pair getNumberOfHousesInAccordanceToUpcomingYear(Pair upcomingYear, BigDecimal numberOfHousesToSplit) {
        BigDecimal percentage = upcomingYear.lowestNumber.divide(upcomingYear.highestNumber.add(upcomingYear.lowestNumber), 9, BigDecimal.ROUND_HALF_EVEN); //.setScale(9, BigDecimal.ROUND_HALF_EVEN);
        BigDecimal lowestResult = numberOfHousesToSplit.multiply(percentage).setScale(3, BigDecimal.ROUND_HALF_EVEN);
        BigDecimal highestResult = numberOfHousesToSplit.subtract(lowestResult).setScale(3, BigDecimal.ROUND_HALF_EVEN);
        return new Pair(lowestResult, highestResult);
    }

    private static class Tuple {
        String key;
        String value;
        SortedSet<String> otherLinkCodes;
        boolean singleCode;

        Tuple(String key, String value, boolean singleCode, SortedSet<String> otherLinkCodes) {
            this.key = key;
            this.value = value;
            this.singleCode = singleCode;
            this.otherLinkCodes = otherLinkCodes;
        }
    }

    private static class Pair {
        BigDecimal lowestNumber;
        BigDecimal highestNumber;

        Pair(BigDecimal low, BigDecimal high) {
            lowestNumber = low;
            highestNumber = high;
        }
    }

    private static class Record {
        String id;
        int year;
        BigDecimal houses;
        BigDecimal km2;
        List<String> links = new ArrayList<>();

        public String toString() {
            return id + " - " + year + " - " + houses + " - " + km2 + " - " + links.toString();
        }
    }

    private static class SquareKilometreRecord implements Comparable<SquareKilometreRecord> {
        String linkCode;
        Map<Integer, BigDecimal> km2 = new HashMap<>();

        public int compareTo(SquareKilometreRecord sq) {
            return linkCode.compareTo(sq.linkCode);
        }

        public String toString() {
            return linkCode + " - " + km2.keySet() + " - " + km2.values();
        }
    }

    private static class VillageComplex {
        Tuple linkCode;
        Map<String, Tuple> yearMap = new HashMap<>();
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