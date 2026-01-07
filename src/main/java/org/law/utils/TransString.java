package org.law.utils;

import org.law.model.LawSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.law.service.section.Constant.DELIMITERS_ALLOWED;
import static org.law.service.section.Constant.MONTH_ALLOWED;

public interface TransString extends UtilsString {

    class CorrectionHolder {
        static final Map<String, String> corrections = new LinkedHashMap<>();

        private CorrectionHolder() {
            // Prevent instantiation
        }

        static {
            loadCorrections();
        }

        private static void loadCorrections() {
            Path csvPath = Path.of("src/main/resources/correct.csv");
            try (var lines = Files.lines(csvPath)) {
                lines.forEach(line -> {
                    String[] parts = line.split(",", 2);
                    if (parts.length == 2) {
                        corrections.put(parts[0], parts[1].trim());
                    }
                });
            } catch (IOException e) {
                System.err.println("Erreur lors du chargement de correct.csv : " + e.getMessage());
            }
        }
    }

    default String applyCorrections(String line) {
        Map<String, String> corrections = CorrectionHolder.corrections;
        for (Map.Entry<String, String> entry : corrections.entrySet()) {
            line = line.replace(entry.getKey(), entry.getValue());
        }
        return line;
    }

    default String correctionLine(String line) {
        return applyCorrections(line);
    }

    default List<String> getCleanTokens(String input) {
        // 1. On remplace les retours à la ligne par des espaces
        // 2. On split par espace pour avoir chaque "mot"
        String[] rawTokens = input.split("\\s+");
        List<String> cleanTokens = new LinkedList<>();

        for (String token : rawTokens) {
            // On ignore les tokens de 1 ou 2 caractères (le bruit : z, X, PR, ', à)
            // Sauf si c'est un chiffre (ex: "N° 1") ou un mot important comme "du"
            if (token.length() >= 2 || token.matches("\\d+")) {
                cleanTokens.add(token);
            }
        }
        return cleanTokens;
    }

    default String toSingleLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text
                // 1. Normaliser les retours ligne
                .replace("\r", "")

                // 2. Gérer les mots coupés en fin de ligne (césures OCR)
                // Exemple: "pré- \n sident" -> "président"
                .replaceAll("(?<=\\w)-\\s*\\n\\s*(?=\\w)", "")

                // 3. PRÉSERVER les retours ligne pour les énumérations (tirets de liste)
                // Si on a un tiret en début de ligne, on remplace le retour ligne par un
                // marqueur temporaire
                .replaceAll("\\n\\s*-\\s*", "###LINE###- ")

                // 4. Transformer tous les autres retours ligne en espaces
                .replace("\n", " ")

                // 5. Rétablir les retours ligne des énumérations
                .replace("###LINE###", "\n")

                // 6. Nettoyage final des espaces multiples
                .replaceAll(" {2,}", " ")
                .trim();
    }

    default String cleanupArticleNumber(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        text = text.replace("'", " ");

        String[] parts = text.split(" ");

        if (parts.length < 2) {
            return "";
        }

        text = parts[parts.length - 1];

        return text
                .replace(" ", "")
                .replace(".", "")
                .replace("-", "")
                .replace("'", "")
                .replace("|", "1")
                .replace("t", "1")
                .replace("I", "1")
                .replace("l", "1")
                .replace("err", "er")
                .replace("ë", "er");
    }

    default String cleanUpTitle(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] lines = text.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.matches("[A-Z0-9\\s]+")) {
                result.append(line).append("\n");
            }
        }

        return result.toString().trim();
    }

    default String normalizeDelimitersOnPrefix(String input, int n) {
        if (input == null || input.isBlank() || n <= 0) {
            return input;
        }

        int limit = Math.min(n, input.length());

        String prefix = input.substring(0, limit);
        String suffix = input.substring(limit);

        // Regex des délimiteurs simples
        String delimiterRegex = Stream.of(DELIMITERS_ALLOWED)
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");

        /*
         * Séquence = (délimiteur + espaces optionnels)+
         * Exemple matchés :
         * ".- :"
         * "-  , :"
         * ". :"
         */
        String regex = "(?:(?:" + delimiterRegex + ")\\s*)+";

        prefix = prefix.replaceAll(regex, ": ");

        // Nettoyage léger sans casser la jonction
        prefix = prefix.replaceAll("\\s{2,}", " ");

        return prefix + suffix;
    }

    default String normalizeColonSpacingOnPrefix(String input, int n) {
        if (input == null || input.isBlank() || n <= 0) {
            return input;
        }

        int limit = Math.min(n, input.length());

        String prefix = input.substring(0, limit);
        String suffix = input.substring(limit);

        // Standardise exactement " : " dans le préfixe uniquement
        prefix = prefix
                .replaceAll("\\s*:\\s*", " : ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        return prefix + suffix;
    }

    default String replaceInNFirstChar(
            String line,
            String target,
            String replacement,
            int n) {
        if (line == null || line.isEmpty()
                || target == null || target.isEmpty()
                || replacement == null
                || n <= 0) {
            return line;
        }

        int limit = Math.min(n, line.length());

        String prefix = line.substring(0, limit);
        String suffix = line.substring(limit);

        prefix = prefix.replace(target, replacement);

        return prefix + suffix;
    }

    default String repairDatePart(String datePart, LawSection lawSection) {
        if (datePart == null || datePart.isBlank())
            return datePart;

        // 1. Découpage (supporte espaces, tirets, slashs et virgules)
        String[] parts = datePart.trim().split("[\\s/\\-,]+");
        if (parts.length < 3)
            return datePart;

        // 2. Nettoyage du JOUR (ex: "1er" -> "1", "ll" -> "11")
        String jourRaw = parts[0].toLowerCase().replace("er", "");
        String jour = cleanOcrDigits(jourRaw);

        // 3. Correction du MOIS (Levenshtein)
        String moisInput = parts[1].toLowerCase(Locale.FRENCH);
        String moisCorrige = correctWordWithLevenshtein(moisInput, MONTH_ALLOWED, 2);

        // 4. Nettoyage de l'ANNÉE (ex: "I964" -> "1964")
        String annee = cleanOcrDigits(parts[2]);

        // On vérifie si l'année est vide ou manifestement trop courte (ex: moins de 4
        // chiffres)
        if (annee.length() < 4) {
            annee = lawSection.getYear();
        }

        return jour + " " + moisCorrige + " " + annee;
    }

    // Utilitaire pour transformer les erreurs OCR courantes en chiffres
    default String cleanOcrDigits(String input) {
        return input.toUpperCase()
                .replace("I", "1") // I majuscule -> 1
                .replace("L", "1") // L majuscule -> 1
                .replace("O", "0") // O majuscule -> 0
                .replaceAll("\\D", ""); // Supprime tout le reste (ponctuation, etc)
    }

    default String correctWordWithLevenshtein(String word, List<String> dictionary, int maxDistance) {
        String best = word;
        int minDist = Integer.MAX_VALUE;

        for (String dictWord : dictionary) {
            int dist = levenshteinDistance(word, dictWord);
            if (dist < minDist && dist <= maxDistance) {
                minDist = dist;
                best = dictWord;
            }
        }

        return best;
    }

}
