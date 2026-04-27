package org.law.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class Stat {

    private final Map<String, Integer> articleCounts;

    private Stat() {
        articleCounts = new HashMap<>();
    }

    // Pattern initialization-on-demand holder - thread-safe
    private static class StatHolder {
        private static final Stat INSTANCE = new Stat();
    }

    public static Stat getInstance() {
        return StatHolder.INSTANCE;
    }

}
