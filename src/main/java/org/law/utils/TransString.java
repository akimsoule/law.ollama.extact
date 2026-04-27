package org.law.utils;

import org.law.model.LawSection;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.law.service.parse.Constant.DELIMITERS_ALLOWED;
import static org.law.service.parse.Constant.MONTH_ALLOWED;

public interface TransString extends Corrector {

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
