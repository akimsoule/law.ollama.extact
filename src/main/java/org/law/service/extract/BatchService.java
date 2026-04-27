package org.law.service.extract;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONObject;
import org.json.JSONArray;
import org.law.config.Config;
import org.law.service.chat.DeepSeekBatchClient;
import org.law.model.LawInput;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service d'extraction par batch optimisé avec DeepSeek.
 */
public class BatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchService.class);


    private final Config config;
    
    public BatchService() {
        this.config = Config.getInstance();
    }

    /**
     * Traite un batch de lois en parallèle.
     */
    public Map<String, JSONObject> processBatch(List<LawInput> lawInputs) throws IOException {
        LOGGER.info("[BatchService] Début traitement batch de " + lawInputs.size() + " lois avec DeepSeek");
        
        try (DeepSeekBatchClient client = new DeepSeekBatchClient()) {
            if (!client.isAvailable()) {
                throw new IOException("[BatchService] DeepSeek service non disponible");
            }
            
            // Préparer les prompts pour le batch
            List<String> prompts = new ArrayList<>();
            Map<String, LawInput> promptToLawMap = new HashMap<>();
            
            for (LawInput input : lawInputs) {
                String processedOcr = OcrPreprocessor.preprocess(input.rawOcr, input.pdfBaseName);
                String prompt = buildExtractionPrompt(processedOcr, input.pdfBaseName);
                prompts.add(prompt);
                promptToLawMap.put(prompt, input);
            }
            
            // Traiter le batch en parallèle
            List<String> responses = client.processBatch(prompts, "deepseek-chat");
            
            // Parser les réponses
            Map<String, JSONObject> results = new ConcurrentHashMap<>();
            int successCount = 0;
            
            for (int i = 0; i < responses.size(); i++) {
                String response = responses.get(i);
                LawInput input = lawInputs.get(i);
                
                if (response != null && !response.trim().isEmpty()) {
                    try {
                        JSONObject result = parseJsonFromLlmResponse(response, input.pdfBaseName);
                        if (isValidResult(result)) {
                            results.put(input.pdfBaseName, result);
                            successCount++;
                        } else {
                            LOGGER.error("[BatchService] Résultat invalide pour " + input.pdfBaseName);
                            // Tentative de réparation avec le modèle rapide
                            tryRepairWithFastModel(client, input, results);
                        }
                    } catch (Exception e) {
                        LOGGER.error("[BatchService] Erreur parsing réponse pour " + input.pdfBaseName + ": " + e.getMessage());
                        tryRepairWithFastModel(client, input, results);
                    }
                } else {
                    LOGGER.error("[BatchService] Réponse vide pour " + input.pdfBaseName);
                    tryRepairWithFastModel(client, input, results);
                }
            }
            
            LOGGER.info("[BatchService] Batch terminé: " + successCount + "/" + lawInputs.size() + " succès");
            LOGGER.info("[BatchService] 💰 Coût estimé: $" + calculateEstimatedCost(lawInputs.size(), successCount));
            
            return results;
            
        } catch (Exception e) {
            LOGGER.error("[BatchService] Erreur traitement batch: " + e.getMessage());
            throw new IOException("[BatchService] Erreur traitement batch", e);
        }
    }
    
    /**
     * Calcule le coût estimé du traitement.
     */
    private String calculateEstimatedCost(int totalInputs, int successCount) {
        // Estimation tokens: 11,500 input par loi, 1,500 output par loi
        long inputTokens = totalInputs * 11500;
        long outputTokens = successCount * 1500;
        
        // Tarifs DeepSeek: $0.28/input, $0.42/output par million tokens
        double inputCost = (inputTokens / 1_000_000.0) * 0.28;
        double outputCost = (outputTokens / 1_000_000.0) * 0.42;
        double totalCost = inputCost + outputCost;
        
        return String.format("%.4f", totalCost);
    }
    
    /**
     * Tente une réparation avec le modèle rapide pour les cas problématiques.
     */
    private void tryRepairWithFastModel(DeepSeekBatchClient client, LawInput input, Map<String, JSONObject> results) {
        try {
            // Créer un JSON vide pour la réparation
            JSONObject emptyJson = new JSONObject();
            emptyJson.put("metadata", new JSONObject());
            emptyJson.put("articles", new JSONArray());
            
            List<String> qaErrors = List.of("Previous extraction failed", "Need complete re-extraction");
            
            String repairPrompt = buildRepairPrompt(input.rawOcr, emptyJson, qaErrors, input.pdfBaseName);
            String repairResponse = client.generate(repairPrompt, "deepseek-chat", 0.1);
            
            if (repairResponse != null) {
                JSONObject repairedResult = parseJsonFromLlmResponse(repairResponse, input.pdfBaseName + "#repair");
                if (isValidResult(repairedResult)) {
                    results.put(input.pdfBaseName, repairedResult);
                    LOGGER.info("[BatchService] Réparation réussie: " + input.pdfBaseName);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[BatchService] Échec réparation " + input.pdfBaseName + ": " + e.getMessage());
        }
    }
    
    /**
     * Traite une seule loi (compatibilité avec l'interface existante).
     */
    public JSONObject processLaw(File pdfFile, String rawOcr, String pdfBaseName) throws IOException {
        List<LawInput> batchInputs = List.of(new LawInput(pdfFile, rawOcr, pdfBaseName));
        // Traiter le batch avec DeepSeek
        Map<String, JSONObject> results = processBatch(batchInputs);
        return results.get(pdfBaseName);
    }
    
        
    /**
     * Construit le prompt d'extraction pour DeepSeek.
     */
    private String buildExtractionPrompt(String rawOcr, String pdfBaseName) {
        String signatoriesData = loadSignatoriesData();
        return """
                You are a specialized legal document parser for Beninese laws (République du Bénin).

                CONTEXT:
                The input text is the raw OCR output from a scanned PDF law document. It may contain:
                - Garbled characters (e.g., "ü_", "?RESIDÈNCD", "§ue", "RXPUBIÏQUE", "I,A" instead of "LA")
                - Broken words split across lines
                - Wrong accent marks or missing letters
                - Table noise and artifacts (dashes, pipes, stray characters)
                - Old French typography (e.g., "1er" for "premier")
                - The document identifier is: %s

                YOUR TASK:
                1. Mentally reconstruct the correct French text by correcting all OCR errors.
                2. Extract and return a single valid JSON object (no markdown, no explanation).

                STRICT JSON SCHEMA (return ONLY this, no wrapping):
                {
                  "articles": [
                    { "index": "<article_number>", "content": "Article <number> : <full corrected text>" }
                  ],
                  "metadata": {
                    "signatories": [
                      { "role": "<role>", "name": "<FIRSTNAME LASTNAME>" }
                    ],
                    "lawDate": "<YYYY-MM-DD>",
                    "lawObject": "<object starting with Portant/Autorisant/Tendant/Fixant...>",
                    "source": "https://sgg.gouv.bj/doc/%s/",
                    "lawNumber": "<YYYY - NN DU DD MOIS YYYY>"
                  }
                }

                EXTRACTION RULES:
                - Extract ALL articles: introductory (Article 1er), numbered (Article 2, Article 3...), and final (last article about promulgation)
                - Article "index" = only the number/identifier (e.g., "1er", "2", "810-1"), NOT the full "Article X :" prefix
                - Article "content" = starts with "Article X : " followed by the complete corrected text
                - Do NOT include section headers or titles (TITRE I, CHAPITRE I...) as articles
                - Preserve full legal wording — do not summarize or truncate
                - For signatories: use the KNOWN SIGNATORIES list below to identify names. Do NOT invent signatories.
                - If the footer OCR is damaged and a signatory name is missing, you may infer a signatory name only if it is historically unambiguous from the law date and the role.
                - "lawDate" must be in ISO format YYYY-MM-DD. If date is ambiguous, use the date from the footer.
                - "lawObject" must be the law's official subject (typically starts with "Portant", "Tendant", "Autorisant", "Fixant", "Relatif", etc.)
                - "lawNumber": format is "YYYY - NN DU DD MOIS YYYY" — extract from the law header
                - If a field cannot be extracted with confidence, use an empty string "". Do NOT invent data.

                KNOWN SIGNATORIES (name;role format):
                %s

                RAW OCR TEXT TO PROCESS:
                <<<
                %s
                >>>

                Return ONLY the JSON object. No markdown code blocks. No explanation. No prefix.
                """.formatted(pdfBaseName, pdfBaseName, signatoriesData, rawOcr);
    }

    private String loadSignatoriesData() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("signatories.csv")) {
            if (is == null) {
                return "No signatories data available.";
            }
            return new String(is.readAllBytes());
        } catch (Exception e) {
            return "Error loading signatories data: " + e.getMessage();
        }
    }
    
    /**
     * Construit un prompt de réparation.
     */
    private String buildRepairPrompt(String rawOcr, JSONObject currentJson, List<String> qaErrors, String pdfBaseName) {
        String signatoriesData = loadSignatoriesData();
        return """
                You are repairing an extracted JSON representation of a Beninese law.

                GOAL:
                Improve the JSON below by fixing ONLY missing or obviously wrong values.
                Preserve correct article text already present unless it is clearly broken.

                DOCUMENT ID:
                %s

                CURRENT JSON:
                %s

                QA ERRORS TO FIX:
                %s

                RAW OCR SOURCE:
                <<<
                %s
                >>>

                KNOWN SIGNATORIES:
                %s

                REPAIR RULES:
                - Return one single valid JSON object only.
                - Keep the same schema: articles + metadata.
                - Prefer preserving existing article content if already coherent.
                - Fill missing metadata fields when they are recoverable from OCR.
                - Fix empty signatory names when recoverable from OCR, known signatories, or historically unambiguous context from the promulgation date and role.
                - Do not fabricate uncertain values.
                - Keep source as https://sgg.gouv.bj/doc/%s/

                Return ONLY the repaired JSON.
                """.formatted(
                pdfBaseName,
                currentJson.toString(2),
                String.join("\n", qaErrors),
                rawOcr,
                signatoriesData,
                pdfBaseName);
    }
    
    /**
     * Parse la réponse JSON du LLM.
     */
    private JSONObject parseJsonFromLlmResponse(String response, String source) {
        try {
            // Nettoyer la réponse pour extraire le JSON
            String jsonStr = response.trim();
            
            // Chercher le début et la fin du JSON
            int jsonStart = jsonStr.indexOf("{");
            int jsonEnd = jsonStr.lastIndexOf("}");
            
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
            }
            
            return new JSONObject(jsonStr);
        } catch (Exception e) {
            LOGGER.error("[DeepSeekBatchService] Erreur parsing JSON pour " + source + ": " + e.getMessage());
            // Créer un JSON de fallback
            JSONObject fallback = new JSONObject();
            fallback.put("metadata", new JSONObject()
                .put("lawNumber", "")
                .put("lawDate", "")
                .put("lawObject", "Parsing failed")
                .put("signatories", new JSONArray()));
            fallback.put("articles", new JSONArray());
            return fallback;
        }
    }
    
    /**
     * Validation rapide du résultat JSON.
     */
    private boolean isValidResult(JSONObject result) {
        if (result == null) return false;
        
        JSONObject metadata = result.optJSONObject("metadata");
        if (metadata == null) return false;
        
        JSONArray articles = result.optJSONArray("articles");
        if (articles == null || articles.isEmpty()) return false;
        
        // Vérification basique des métadonnées
        String lawDate = metadata.optString("lawDate", "");
        String lawNumber = metadata.optString("lawNumber", "");
        
        // Au moins un champ de métadonnée ET au moins un article requis
        boolean hasMetadata = !lawDate.isEmpty() || !lawNumber.isEmpty();
        boolean hasArticles = articles.length() > 0;
        return hasMetadata && hasArticles;
    }
}
