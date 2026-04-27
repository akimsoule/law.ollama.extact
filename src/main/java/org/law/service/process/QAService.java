package org.law.service.process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service de contrôle qualité pour valider le JSON extrait.
 */
public class QAService {

    /**
     * Valide le JSON selon les critères QA.
     * Adapté pour les lois anciennes avec des règles plus flexibles.
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

        // Vérifier les champs de metadata avec tolérance pour les lois anciennes
        totalChecks++;
        if (jsonObject.has("metadata")) {
            JSONObject metadata = jsonObject.getJSONObject("metadata");
            String[] requiredFields = { "lawNumber", "lawObject", "lawDate" };
            boolean metadataValid = true;
            
            // Vérification plus flexible pour les lois anciennes
            String lawDate = metadata.optString("lawDate", "");
            String lawNumber = metadata.optString("lawNumber", "");
            
            // Si c'est une loi ancienne (détectée via le numéro), on est plus tolérant
            boolean isOldLaw = isOldLaw(lawNumber);
            
            for (String field : requiredFields) {
                String value = metadata.optString(field, "");
                if (value.trim().isEmpty()) {
                    if (isOldLaw && (field.equals("lawNumber") || field.equals("lawDate"))) {
                        // Plus tolérant pour les lois anciennes
                        errors.add("Champ metadata '" + field + "' manquant ou vide (loi ancienne)");
                    } else {
                        errors.add("Champ metadata '" + field + "' manquant ou vide");
                        metadataValid = false;
                    }
                }
            }
            if (metadataValid || isOldLaw) {
                successfulChecks++;
            }
        } else {
            errors.add("Metadata manquant");
        }

        // Vérifier les articles avec gestion de la longueur
        totalChecks++;
        if (!jsonObject.has("articles")) {
            errors.add("Articles manquant");
        } else {
            JSONArray articles = jsonObject.getJSONArray("articles");
            boolean articlesValid = true;
            
            // Plus flexible pour les lois anciennes (peuvent avoir un seul article)
            int minArticles = isOldLaw(jsonObject) ? 1 : 2;
            if (articles.length() < minArticles) {
                errors.add("Au moins " + minArticles + " article(s) requis(s)");
                articlesValid = false;
            }
            
            if (!articles.isEmpty()) {
                // Vérifier le dernier article avec seuil adaptatif
                JSONObject lastArticle = articles.getJSONObject(articles.length() - 1);
                if (lastArticle.has("content")) {
                    String content = lastArticle.getString("content");
                    int wordCount = content.split("\\s+").length;
                    
                    // Seuil plus élevé pour les lois anciennes (OCR plus verbeux)
                    int maxWords = isOldLaw(jsonObject) ? 100 : 25;
                    if (wordCount > maxWords) {
                        errors.add("Le dernier article a " + wordCount + " mots (maximum " + maxWords + " autorisé" + (isOldLaw(jsonObject) ? " - loi ancienne" : "") + ")");
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
    
    /**
     * Détecte si c'est une loi ancienne basée sur le numéro ou le contenu
     */
    private boolean isOldLaw(String lawNumber) {
        if (lawNumber != null && !lawNumber.isEmpty()) {
            Matcher matcher = Pattern.compile("(\\d{4})").matcher(lawNumber);
            if (matcher.find()) {
                int year = Integer.parseInt(matcher.group(1));
                return year <= 1970;
            }
        }
        return false;
    }
    
    /**
     * Détecte si c'est une loi ancienne basée sur tout le JSON
     */
    private boolean isOldLaw(JSONObject jsonObject) {
        if (!jsonObject.has("metadata")) return false;

        JSONObject metadata = jsonObject.getJSONObject("metadata");

        String lawNumber = metadata.optString("lawNumber", "");
        if (isOldLaw(lawNumber)) return true;

        String lawDate = metadata.optString("lawDate", "");
        if (lawDate != null && lawDate.length() >= 4) {
            try {
                int year = Integer.parseInt(lawDate.substring(0, 4));
                return year <= 1970;
            } catch (NumberFormatException ignored) {}
        }

        return false;
    }
}