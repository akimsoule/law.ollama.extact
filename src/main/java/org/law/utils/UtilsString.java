package org.law.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public interface UtilsString {

    default int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++)
            dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++)
            dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;

                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }

        return dp[a.length()][b.length()];
    }

    default double calculatePartialJaccard(String input, String pattern) {
        Set<String> inputTokens = tokenize(input);
        Set<String> patternTokens = tokenize(pattern);

        long matches = patternTokens.stream().filter(inputTokens::contains).count();
        return (double) matches / patternTokens.size(); // Ratio basé sur le pattern uniquement
    }

    private Set<String> tokenize(String text) {
        if (text == null) return new HashSet<>();
        // On met en minuscule et on garde les mots d'au moins 2 caractères
        return Arrays.stream(text.toLowerCase().split("[\\s,.:;!\"'()-]+"))
                .filter(word -> word.length() > 1)
                .collect(Collectors.toSet());
    }

}
