package org.law.utils;

import java.util.Arrays;
import java.util.Optional;

import static org.law.service.parse.Constant.DELIMITERS_ALLOWED;

public interface BoolString extends UtilsString {

    default boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    default boolean startsWithIgnoreCase(String text, String prefix) {
        if (text == null || prefix == null) {
            return false;
        }
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    default boolean containsIgnoreCase(String text, String substring) {
        if (text == null || substring == null) {
            return false;
        }
        return text.toLowerCase().contains(substring.toLowerCase());
    }

    default boolean containsInFirstChars(String text, String needle, int maxChars) {
        if (text == null || needle == null || maxChars <= 0) {
            return false;
        }

        int limit = Math.min(text.length(), maxChars);
        String head = text.substring(0, limit);

        return head.contains(needle);
    }

    default boolean isTitle(String line) {
        if (line == null || line.isBlank())
            return false;

        String newline = line.trim().toUpperCase();

        // 1. Détection stricte des titres de structure (obligatoire pour le RAG)
        // On cherche : TITRE I, CHAPITRE PREMIER, SECTION 2, etc.
        return newline.matches("^(TITRE|CHAPITRE|SECTION|LIVRE|SOUS-SECTION)\\s+[0-9IVXerPREMIER]+.*");

        // 2. Pourquoi "PROSPECTION, RECHERCHE..." doit être ignoré ?
        // Ces lignes sont du contenu thématique. On ne les considère comme titres
        // que si elles sont très courtes ET commencent par un mot-clé.

        // Votre ligne "PROSPECTION, RECHERCHE..." échouera ici car :
        // - Elle ne commence pas par un mot-clé de structure.
        // - Elle contient des virgules (souvent absentes des titres de structure purs).
    }

    /**
     * Méthode stratLookLikeArticle avec sa propre logique pour détecter les
     * articles.
     * Utilise un chemin rapide (fast path) pour les matches parfaits, sinon un
     * chemin flou (fuzzy)
     * avec n-grammes sur les 7 premiers caractères, suivi d'une validation
     * obligatoire du numéro
     * sur les 15 premiers caractères.
     */
    default boolean stratLookLikeArticle(String line) {
        if (line == null || line.isBlank())
            return false;

        String cleanLine = line.trim();
        String target = "article";

        // 1. ZONE MOT (7 premiers caractères)
        String wordZone = cleanLine.substring(0, Math.min(7, cleanLine.length())).toLowerCase();

        Optional<String> delimiterOptionalFound = Arrays.stream(DELIMITERS_ALLOWED).filter(
                delimiter -> containsInFirstChars(line, delimiter, 25)).findFirst();

        if (line.startsWith("ARTICLES") && delimiterOptionalFound.isPresent()) {
            return true;
        }

        if (startsWithIgnoreCase(line, "articles")) {
            return false;
        }

        // 2. VALIDATION PARFAITE OU FUZZY
        boolean isArticleWord = false;
        if (startsWithIgnoreCase(wordZone, target)) {
            isArticleWord = true;
        } else {
            int hits = 0;
            int ngramSize = 2;
            for (int i = 0; i <= target.length() - ngramSize; i++) {
                if (wordZone.contains(target.substring(i, i + ngramSize)))
                    hits++;
            }
            isArticleWord = (hits >= 3);
        }

        // 3. VALIDATION DU NUMÉRO + ANCRAGE STRICT AU DÉBUT
        if (isArticleWord) {
            String numberingZone = cleanLine.substring(0, Math.min(15, cleanLine.length())).toLowerCase();

            /*
             * LA SOLUTION : La Regex ci-dessous impose :
             * ^ : Le début de la ligne
             * (art) : Le mot commence par 'art' (donc pas de 'l'article' ou 'd'article')
             * .* : Suivi de n'importe quoi (espaces, bruits OCR)
             * (\\d+|1er|premier|unique) : Un numéro ou ordinal
             */
            return numberingZone.matches("(?i)^art.*(\\d+|1er|premier|unique).*");
        }

        return false;
    }

    default boolean fuzzyMatch(
            String text,
            String pattern,
            int firstNChars,
            int ngramSize,
            int minHits) {
        if (text == null || text.isBlank()
                || pattern == null || pattern.isBlank()
                || firstNChars <= 0
                || ngramSize <= 0) {
            return false;
        }

        String cleanText = text.trim().toLowerCase();
        String cleanPattern = pattern.toLowerCase();

        int limit = Math.min(firstNChars, cleanText.length());
        String zone = cleanText.substring(0, limit);

        // 1️⃣ Match exact rapide (n'importe où dans la zone)
        if (zone.contains(cleanPattern)) {
            return true;
        }

        // 2️⃣ Matching fuzzy par n-grams
        int hits = 0;
        for (int i = 0; i <= cleanPattern.length() - ngramSize; i++) {
            String ngram = cleanPattern.substring(i, i + ngramSize);
            if (zone.contains(ngram)) {
                hits++;
            }
        }

        return hits >= minHits;
    }

    default boolean validateWithLevenshtein(
            String text,
            String pattern,
            int firstNChars,
            int maxDistance) {
        if (text == null || pattern == null
                || text.isBlank() || pattern.isBlank()
                || firstNChars <= 0
                || maxDistance < 0) {
            return false;
        }

        int limit = Math.min(firstNChars, text.length());
        String zone = text.substring(0, limit);

        int patternLength = pattern.length();

        // Fenêtre glissante dans la zone analysée
        for (int i = 0; i <= zone.length() - patternLength; i++) {
            String candidate = zone.substring(i, i + patternLength);

            if (levenshteinDistance(candidate, pattern) <= maxDistance) {
                return true;
            }
        }

        return false;
    }

    /**
     * Vérifie si une cible (ex: "Cotonou") est présente dans une ligne avec une
     * tolérance aux fautes de frappe.
     */
    default boolean containTarget(String line, String target, int maxTolerance) {
        if (line == null || target == null)
            return false;

        String targetLower = target.toLowerCase().trim();
        String[] words = line.toLowerCase().split("[\\s,.;/!]+"); // On split par espace et ponctuation

        for (String word : words) {
            // 1. Test direct (si le mot contient déjà la cible ou inversement)
            if (word.contains(targetLower) || targetLower.contains(word)) {
                // Optionnel : vérifier que le mot n'est pas trop court pour éviter les faux
                // positifs
                if (word.length() > 2)
                    return true;
            }

            // 2. Test flou avec Levenshtein
            if (levenshteinDistance(word, targetLower) <= maxTolerance) {
                return true;
            }
        }

        return false;
    }

}
