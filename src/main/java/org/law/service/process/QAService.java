package org.law.service.process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Service de contrôle qualité pour valider le JSON extrait.
 */
public class QAService {

    private static final Path TESSDATA_PATH = Paths.get("src/main/resources/tessdata");
    private final Set<String> dictionary;

    public QAService() {
        this.dictionary = loadTesseractDictionary();
        System.out.println("📚 QAService: Dictionnaire chargé (" + dictionary.size() + " mots uniques).");
    }

    private Set<String> loadTesseractDictionary() {
        Set<String> words = new HashSet<>();
        if (!Files.exists(TESSDATA_PATH)) {
            System.err.println("⚠️ Dossier tessdata introuvable : " + TESSDATA_PATH.toAbsolutePath());
            return words;
        }

        try (Stream<Path> paths = Files.list(TESSDATA_PATH)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path);
                    for (String line : lines) {
                        String[] parts = line.split("\\s+");
                        for (String w : parts) {
                            String clean = normalize(w);
                            if (clean.length() > 2) {
                                words.add(clean);
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Erreur lecture tessdata " + path + " : " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("❌ Erreur accès tessdata : " + e.getMessage());
        }
        return words;
    }

    private String normalize(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z]", "");
    }

    /**
     * Valide le JSON selon les critères QA.
     *
     * @param jsonObject le JSON à valider
     * @return liste des erreurs trouvées (vide si valide)
     */
    public List<String> validateJson(JSONObject jsonObject) {
        List<String> errors = new ArrayList<>();

        if (!jsonObject.has("metadata") || !jsonObject.getJSONObject("metadata").has("lawNumber")) {
            errors.add("Structure: lawNumber manquant");
        }
        if (!jsonObject.has("articles") || jsonObject.getJSONArray("articles").length() < 2) {
            errors.add("Structure: < 2 articles");
        }

        if (jsonObject.has("articles")) {
            JSONArray articles = jsonObject.getJSONArray("articles");
            for (int i = 0; i < articles.length(); i++) {
                String content = articles.getJSONObject(i).optString("content", "");
                int unknownWords = countUnknownWords(content);
                if (unknownWords > 0) {
                    errors.add(String.format("⚠️ LEXIQUE: %d mots inconnus dans l'article %d", unknownWords, i + 1));
                }
            }
        }

        return errors;
    }

    private int countUnknownWords(String text) {
        if (dictionary.isEmpty() || text == null || text.isBlank()) {
            return 0;
        }

        int count = 0;
        String[] words = text.split("[^a-zA-Zàâéèêëîïôûùçœ'-]+");
        for (String w : words) {
            String clean = normalize(w);
            if (clean.length() >= 4 && !dictionary.contains(clean)) {
                count++;
            }
        }
        return count;
    }
}
