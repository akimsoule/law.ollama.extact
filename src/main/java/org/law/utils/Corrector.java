package org.law.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;

public interface Corrector extends UtilsString {

    class CorrectionHolder {

        static final Map<String, String> corrections = new LinkedHashMap<>();
        static final List<String> dic = new ArrayList<>();
        static final Set<String> dicSet = new HashSet<>();
        static final List<String> patterns = new LinkedList<>();

        // ⭐ PRIORITÉ ABSOLUE : formes verbales / juridiques très fréquentes
        static final Set<String> HIGH_FREQ_WORDS = Set.of(
                // ÊTRE
                "sont", "est", "été", "être", "sera", "seront",
                "était", "étaient", "soit", "soient",

                // AVOIR
                "ont", "a", "avait", "avaient", "aura", "auront",

                // MODAUX / JURIDIQUE
                "doit", "doivent", "peut", "peuvent",
                "dispose", "disposent", "prévoit", "prévoient",
                "fixe", "fixent", "stipule", "stipulent",

                // AUTRES FRÉQUENTS
                "dit", "dits", "notamment", "conformément", "date");

        private CorrectionHolder() {
        }

        static {
            loadDictionary();
            loadCustomDictionary();
            loadCorrections();
            loadPatterns();
        }

        private static void loadDictionary() {
            Path path = Path.of("src/main/resources/tessdata/fra.dict-words");
            if (!Files.exists(path))
                return;

            try (var lines = Files.lines(path)) {
                lines.forEach(line -> {
                    if (line.isBlank() || line.startsWith("#"))
                        return;
                    String w = line.trim();
                    dic.add(w);
                    dicSet.add(w.toLowerCase());
                });
            } catch (IOException e) {
                System.err.println("Erreur dictionnaire : " + e.getMessage());
            }
        }

        private static void loadCustomDictionary() {
            Path path = Path.of("src/main/resources/tessdata/custom.dict");
            if (!Files.exists(path))
                return;

            try (var lines = Files.lines(path)) {
                lines.forEach(line -> {
                    if (line.isBlank() || line.startsWith("#"))
                        return;
                    String w = line.trim();
                    dic.add(w);
                    dicSet.add(w.toLowerCase());
                });
            } catch (IOException e) {
                System.err.println("Erreur dictionnaire custom : " + e.getMessage());
            }
        }

        private static void loadCorrections() {
            Path dir = Path.of("src/main/resources/correction");
            if (!Files.exists(dir))
                return;

            try (Stream<Path> stream = Files.list(dir)) {
                stream.forEach(path -> {
                    try (var lines = Files.lines(path)) {
                        lines.forEach(line -> {
                            String[] parts = line.split(",", 2);
                            if (parts.length == 2) {
                                corrections.put(parts[0], parts[1].trim());
                            }
                        });
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }

        private static void loadPatterns() {
            Path dir = Path.of("src/main/resources/pattern");
            if (!Files.exists(dir))
                return;

            try (Stream<Path> stream = Files.list(dir)) {
                stream.forEach(path -> {
                    try (var lines = Files.lines(path)) {
                        lines.filter(l -> !l.isBlank() && !l.startsWith("#"))
                                .forEach(patterns::add);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    /* ========================================================= */

    default String correctionWordInLine(String line) {
        if (line == null)
            return null;

        String result = line;
        for (Map.Entry<String, String> e : CorrectionHolder.corrections.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    default String correctWordWithDict(String line) {
        if (line == null || line.isEmpty())
            return line;

        String[] words = line.split(" ");
        StringBuilder sb = new StringBuilder(line.length());

        for (int i = 0; i < words.length; i++) {
            String w = words[i];

            if (shouldSkipCorrection(w, i, words)) {
                sb.append(w);
            } else {
                sb.append(applyCorrectionRules(w));
            }

            if (i < words.length - 1)
                sb.append(" ");
        }
        return sb.toString();
    }

    /* ========================================================= */

    private boolean shouldSkipCorrection(String word, int index, String[] allWords) {
        if (word == null || word.length() < 2)
            return true;

        // 1. Majuscule initiale → nom propre
        if (Character.isUpperCase(word.charAt(0)))
            return true;

        // 2. Caractères non alphabétiques
        boolean lettersOnly = word.chars()
                .allMatch(c -> Character.isLetter(c) || c == 'œ' || c == 'Œ');
        if (!lettersOnly)
            return true;

        // 3. Sigles
        if (word.chars().allMatch(Character::isUpperCase))
            return true;

        // 4. Contexte Nom Propre
        boolean prevUpper = index > 0 && Character.isUpperCase(allWords[index - 1].charAt(0));
        boolean nextUpper = index < allWords.length - 1 && Character.isUpperCase(allWords[index + 1].charAt(0));
        if (prevUpper && nextUpper)
            return true;

        // 5. Déjà valide
        return CorrectionHolder.dicSet.contains(
                word.toLowerCase().replace("œ", "oe"));
    }

    /* ========================================================= */

    private String applyCorrectionRules(String original) {

        // CAS CRITIQUE OCR : lfa → Ifa
        if (original.startsWith("l") && original.length() > 1) {
            String cand = "I" + original.substring(1);
            if (CorrectionHolder.dic.contains(cand)) {
                return formatResult(original, cand, "ASCII_I_L");
            }
        }

        String word = original.toLowerCase().replace("œ", "oe");
        int len = word.length();

        // ⭐ Nouvelle règle : collage OCR prépositions (vieà → vie à)
        if (word.length() > 2) {
            if (word.endsWith("à")) {
                String base = word.substring(0, word.length() - 1);
                if (CorrectionHolder.dicSet.contains(base)) {
                    return formatResult(original, base + " à", "COLLAGE_PREP");
                }
            }
            if (word.endsWith("au")) {
                String base = word.substring(0, word.length() - 2);
                if (CorrectionHolder.dicSet.contains(base)) {
                    return formatResult(original, base + " au", "COLLAGE_PREP");
                }
            }
            if (word.endsWith("aux")) {
                String base = word.substring(0, word.length() - 3);
                if (CorrectionHolder.dicSet.contains(base)) {
                    return formatResult(original, base + " aux", "COLLAGE_PREP");
                }
            }
        }

        // 🛑 Protection adjectifs de nationalité / dérivés
        if (word.matches(".*(ois|oise|oises|ois(es)?)$")) {
            return original;
        }

        // ⭐ PRIORITÉ ABSOLUE : formes verbales fréquentes
        for (String hf : CorrectionHolder.HIGH_FREQ_WORDS) {
            if (hf.length() == len && levenshteinDistance(word, hf) == 1) {
                return formatResult(original, hf, "PRIORITÉ_VERBALE");
            }
        }

        // 🛑 Ne pas découper un mot déjà valide
        if (CorrectionHolder.dicSet.contains(word)) {
            return original;
        }

        /* ===================================================== */
        /* ⭐ DÉCOUPAGE SCORÉ (corrige siles / parmiles / etc.) */
        /* ===================================================== */

        Set<String> shortOk = Set.of(
                "si", "le", "la", "de", "un", "il", "en",
                "au", "et", "ce", "ne", "me", "se",
                "par", "du");

        String bestSplit = null;
        int bestScore = -1;

        for (int i = 2; i <= len - 2; i++) {
            String p1 = word.substring(0, i);
            String p2 = word.substring(i);

            if (!CorrectionHolder.dicSet.contains(p1)
                    || !CorrectionHolder.dicSet.contains(p2)) {
                continue;
            }

            if ((p1.length() < 3 && !shortOk.contains(p1))
                    || (p2.length() < 3 && !shortOk.contains(p2))) {
                continue;
            }

            // ⭐ Score linguistique :
            // priorité au premier mot long (parmi > par)
            int score = (p1.length() * 10) + p2.length();

            if (score > bestScore) {
                bestScore = score;
                bestSplit = p1 + " " + p2;
            }
        }

        if (bestSplit != null) {
            return formatResult(original, bestSplit, "DÉCOUPAGE");
        }

        /* ===================================================== */

        // 1. ACCENTS
        for (String d : CorrectionHolder.dic) {
            if (d.length() != len)
                continue;

            String norm = Normalizer.normalize(d, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "")
                    .replace("ç", "c");

            if (norm.equals(word)) {
                return formatResult(original, d, "ACCENT");
            }
        }

        // 2. GÉOMÉTRIE
        String desc = "gqpyj";
        String asc = "dfhkltb";

        for (String d : CorrectionHolder.dic) {
            if (d.length() != len)
                continue;
            if (levenshteinDistance(word, d) != 1)
                continue;

            int pos = findDiffPos(word, d);
            char c1 = word.charAt(pos);
            char c2 = d.charAt(pos);

            boolean li = (c1 == 'l' && c2 == 'i');
            boolean geom = (desc.indexOf(c1) >= 0 && desc.indexOf(c2) >= 0) ||
                    (asc.indexOf(c1) >= 0 && asc.indexOf(c2) >= 0);

            if (li || geom) {
                return formatResult(original, d, "GÉOMÉTRIE");
            }
        }

        // 3. DICT STANDARD
        for (String d : CorrectionHolder.dic) {
            if (d.length() == len && levenshteinDistance(word, d) == 1) {
                return formatResult(original, d, "DICT");
            }
        }

        return original;
    }

    /* ========================================================= */

    private int findDiffPos(String a, String b) {
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i))
                return i;
        }
        return 0;
    }

    private String formatResult(String original, String corrected, String type) {
        System.out.println("=> [" + type + "] " + original + " -> " + corrected);

        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(corrected.charAt(0)) +
                    (corrected.length() > 1 ? corrected.substring(1) : "");
        }
        return corrected;
    }
}
