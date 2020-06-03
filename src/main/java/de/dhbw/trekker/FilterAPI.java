package de.dhbw.trekker;

import java.util.HashMap;

public class FilterAPI {
    private String name;
    private HashMap<String, Integer> combinations = new HashMap<>();
    private int count;

    public FilterAPI(String name) {
        this.name = name;
    }

    public void count() {
        count++;
    }

    public void addCombinationWith(String apiName) {
        combinations.put(apiName, combinations.getOrDefault(apiName,0) + 1);
    }

    public int getCount() {
        return count;
    }

    public String getName() {
        return name;
    }

    public HashMap<String, Integer> getCombinations() {
        return combinations;
    }

}
