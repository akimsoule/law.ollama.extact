package org.law.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class Stat {

    private static Stat instance;

    private final Map<String, Integer> articleCounts;

    private Stat() {
        articleCounts = new HashMap<>();
    }

    public static Stat getInstance() {
        if (instance == null) {
            instance = new Stat();
        }
        return instance;
    }

}
