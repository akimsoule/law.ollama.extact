package org.law.service.main;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Service de contrôle qualité pour valider le JSON extrait.
 */
public class QAService {

    /**
     * Valide le JSON selon les critères QA.
     *
     * @param jsonObject le JSON à valider
     * @return liste des erreurs trouvées (vide si valide)
     */
    public List<String> validateJson(JSONObject jsonObject) {
        List<String> errors = new ArrayList<>();
        int totalChecks = 0;
        int successfulChecks = 0;

        // Vérifier les signataires
        totalChecks++;
        if (!jsonObject.has("metadata") || !jsonObject.getJSONObject("metadata").has("signatories")) {
            errors.add("Metadata ou signatories manquant");
        } else {
            successfulChecks++;
            JSONArray signatories = jsonObject.getJSONObject("metadata").getJSONArray("signatories");
            if (signatories.isEmpty()) {
                errors.add("Au moins un signataire requis");
            } else {
                for (int i = 0; i < signatories.length(); i++) {
                    totalChecks++;
                    JSONObject sig = signatories.getJSONObject(i);
                    boolean sigValid = true;
                    if (!sig.has("role") || sig.getString("role").trim().isEmpty()) {
                        errors.add("Signataire " + (i + 1) + " : rôle manquant ou vide");
                        sigValid = false;
                    }
                    if (!sig.has("name") || sig.getString("name").trim().isEmpty()) {
                        errors.add("Signataire " + (i + 1) + " : nom manquant ou vide");
                        sigValid = false;
                    }
                    if (sigValid) {
                        successfulChecks++;
                    }
                }
            }
        }

        // Vérifier les champs de metadata
        totalChecks++;
        if (jsonObject.has("metadata")) {
            JSONObject metadata = jsonObject.getJSONObject("metadata");
            String[] requiredFields = { "lawNumber", "lawObject", "lawDate" };
            boolean metadataValid = true;
            for (String field : requiredFields) {
                if (!metadata.has(field) || metadata.getString(field).trim().isEmpty()) {
                    errors.add("Champ metadata '" + field + "' manquant ou vide");
                    metadataValid = false;
                }
            }
            if (metadataValid) {
                successfulChecks++;
            }
        } else {
            errors.add("Metadata manquant");
        }

        // Vérifier les articles
        totalChecks++;
        if (!jsonObject.has("articles")) {
            errors.add("Articles manquant");
        } else {
            JSONArray articles = jsonObject.getJSONArray("articles");
            boolean articlesValid = true;
            if (articles.length() < 2) {
                errors.add("Au moins deux articles requis");
                articlesValid = false;
            }
            if (!articles.isEmpty()) {
                // Vérifier le dernier article
                JSONObject lastArticle = articles.getJSONObject(articles.length() - 1);
                if (lastArticle.has("content")) {
                    String content = lastArticle.getString("content");
                    int wordCount = content.split("\\s+").length;
                    if (wordCount > 25) {
                        errors.add("Le dernier article a " + wordCount + " mots (maximum 25 autorisé)");
                        articlesValid = false;
                    }
                } else {
                    errors.add("Contenu du dernier article manquant");
                    articlesValid = false;
                }
            }
            if (articlesValid) {
                successfulChecks++;
            }
        }

        // Ajouter le taux de réussite dans la variable errors uniquement s'il y a des
        // erreurs
        if (!errors.isEmpty()) {
            double successRate = totalChecks > 0 ? (double) successfulChecks / totalChecks * 100 : 0;
            errors.add(String.format("QA Validation - Taux de réussite: %.1f%% (%d/%d)", successRate, successfulChecks,
                    totalChecks));
            errors.add("Succès : " + successfulChecks + " / " + totalChecks);
        }

        return errors;
    }
}