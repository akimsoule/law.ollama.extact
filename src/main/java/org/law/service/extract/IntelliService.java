package org.law.service.extract;

/**
 * Legal Document Restructuring Tool
 *
 * Pipeline: Raw OCR (Tesseract) → LLM correction + structured extraction
 *
 * Models:
 * - Text: DeepSeek (configured via deepseek.default.model)
 *
 * Features:
 * - OCR error correction via LLM (garbled characters, broken words, accents)
 * - Anti-hallucination prompts with strict schema enforcement
 * - Forced JSON Schema validation
 * - Law-oriented extraction (articles, metadata, signatories)
 * - Used as fallback when the regex-based pipeline produces empty results
 */

import org.json.JSONObject;
import org.json.JSONArray;
import org.law.config.Config;
import org.law.service.chat.DeepSeekBatchClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IntelliService {

    private static final Config CONFIG = Config.getInstance();
    private static final Logger LOGGER = LoggerFactory.getLogger(IntelliService.class);
    private static final String MODEL = CONFIG.getDeepSeekDefaultModel();
    private static final double TEMPERATURE = CONFIG.getDeepSeekTemperature();
    private static final int MAX_RETRIES = CONFIG.getDeepSeekMaxRetries();
    private static final long RETRY_DELAY_MS = CONFIG.getDeepSeekRetryDelayMs();

    // =========================================================
    // ===================== PUBLIC API ========================
    // =========================================================

    /**
     * Point d'entrée principal : corrige l'OCR bruité et extrait le JSON structuré
     * de la loi en une seule passe LLM.
     *
     * @param pdfFile     fichier PDF source (utilisé pour le nom seulement)
     * @param rawOcr      texte OCR brut produit par Tesseract (peut être bruité)
     * @param pdfBaseName nom de base du PDF, ex: "loi-1962-39" (sans extension)
     * @return JSONObject conforme au schéma attendu (articles + metadata)
     * @throws IOException si le service DeepSeek n'est pas disponible
     */
    public JSONObject processLaw(File pdfFile, String rawOcr, String pdfBaseName) throws IOException {
        LOGGER.info("[IntelliService] Démarrage extraction LLM pour {}", pdfBaseName);

        // Pré-traitement du texte OCR pour les lois anciennes
        String processedOcr = OcrPreprocessor.preprocess(rawOcr, pdfBaseName);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JSONObject result = executeDeepSeekExtraction(processedOcr, pdfBaseName, attempt);
                if (isValidResult(result)) {
                    return result;
                }
                if (attempt < MAX_RETRIES) {
                    LOGGER.warn("[IntelliService] Résultat invalide, nouvelle tentative...");
                    Thread.sleep(RETRY_DELAY_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("[IntelliService] Interruption lors du retry", e);
            } catch (Exception e) {
                LOGGER.error("[IntelliService] Erreur tentative {}: {}", attempt, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw new IOException("[IntelliService] Erreur DeepSeek lors de la generation", e);
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("[IntelliService] Interruption lors du retry", ie);
                }
            }
        }

        throw new IOException("[IntelliService] Échec après " + MAX_RETRIES + " tentatives");
    }

    private JSONObject executeDeepSeekExtraction(String processedOcr, String pdfBaseName, int attempt)
            throws IOException {
        try (DeepSeekBatchClient client = new DeepSeekBatchClient()) {
            if (!client.isAvailable()) {
                throw new IOException("[IntelliService] DeepSeek non disponible.");
            }

            String prompt = buildExtractionPrompt(processedOcr, pdfBaseName);
            LOGGER.info("[IntelliService] Appel DeepSeek ({}) - tentative {}/{}", MODEL, attempt, MAX_RETRIES);

            String llmResponse = client.generate(prompt, MODEL, TEMPERATURE);
            LOGGER.info("[IntelliService] Réponse DeepSeek reçue.");

            return parseJsonFromLlmResponse(llmResponse, pdfBaseName);
        }
    }

    public JSONObject repairJson(String rawOcr, JSONObject currentJson, List<String> qaErrors, String pdfBaseName)
            throws IOException {
        LOGGER.info("[IntelliService] Démarrage réparation LLM pour {}", pdfBaseName);
        try (DeepSeekBatchClient client = new DeepSeekBatchClient()) {
            if (!client.isAvailable()) {
                throw new IOException("[IntelliService] DeepSeek non disponible.");
            }

            String prompt = buildRepairPrompt(rawOcr, currentJson, qaErrors, pdfBaseName);
            LOGGER.info("[IntelliService] Appel DeepSeek réparation ({})...", MODEL);
            String llmResponse = client.generate(prompt, MODEL, TEMPERATURE);
            LOGGER.info("[IntelliService] Réponse DeepSeek réparation reçue.");
            return parseJsonFromLlmResponse(llmResponse, pdfBaseName + "#repair");
        }
    }

    /**
     * Teste uniquement la disponibilité du service DeepSeek.
     */
    public boolean isAvailable() {
        try (DeepSeekBatchClient client = new DeepSeekBatchClient()) {
            return client.isAvailable();
        }
    }

    // =========================================================
    // ===================== PROMPTS ===========================
    // =========================================================

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
                """
                .formatted(pdfBaseName, pdfBaseName, signatoriesData, rawOcr);
    }

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
                """
                .formatted(
                        pdfBaseName,
                        currentJson.toString(2),
                        String.join("\n", qaErrors),
                        rawOcr,
                        signatoriesData,
                        pdfBaseName);
    }

    public static boolean hasEmptySignatoryName(JSONObject jsonObject) {
        JSONObject metadata = jsonObject.optJSONObject("metadata");
        if (metadata == null) {
            return false;
        }

        JSONArray signatories = metadata.optJSONArray("signatories");
        if (signatories == null) {
            return false;
        }

        for (int index = 0; index < signatories.length(); index++) {
            JSONObject signatory = signatories.optJSONObject(index);
            if (signatory != null && signatory.optString("name", "").trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // =========================================================
    // ===================== RESPONSE PARSING ==================
    // =========================================================

    /**
     * Extrait le JSONObject depuis la réponse LLM qui peut contenir du markdown
     * ou du texte parasite autour du JSON.
     */
    private JSONObject parseJsonFromLlmResponse(String llmResponse, String pdfBaseName) throws IOException {
        if (llmResponse == null || llmResponse.isBlank()) {
            throw new IOException("[IntelliService] Réponse LLM vide pour " + pdfBaseName);
        }

        // 1. Essayer directement
        String candidate = llmResponse.trim();
        if (candidate.startsWith("{")) {
            try {
                return new JSONObject(candidate);
            } catch (Exception ignored) {
                // Reponse non strictement JSON, on tente les strategies d'extraction suivantes.
            }
        }

        // 2. Extraire le bloc JSON depuis des marqueurs markdown (```json ... ```)
        Matcher mdMatcher = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL)
                .matcher(llmResponse);
        if (mdMatcher.find()) {
            String extracted = mdMatcher.group(1).trim();
            try {
                return new JSONObject(extracted);
            } catch (Exception ignored) {
                // Bloc markdown detecte mais contenu encore invalide, on tente l'extraction par
                // accolades.
            }
        }

        // 3. Chercher la première accolade ouvrante et la dernière fermante
        int first = llmResponse.indexOf('{');
        int last = llmResponse.lastIndexOf('}');
        if (first >= 0 && last > first) {
            String extracted = llmResponse.substring(first, last + 1);
            try {
                return new JSONObject(extracted);
            } catch (Exception e) {
                throw new IOException("[IntelliService] JSON invalide dans la réponse LLM pour "
                        + pdfBaseName + ": " + e.getMessage());
            }
        }

        throw new IOException("[IntelliService] Aucun JSON valide trouvé dans la réponse LLM pour " + pdfBaseName);
    }

    // =========================================================
    // ===================== HELPERS ===========================
    // =========================================================

    /**
     * Validation rapide du résultat JSON
     */
    private boolean isValidResult(JSONObject result) {
        if (result == null)
            return false;

        JSONObject metadata = result.optJSONObject("metadata");
        if (metadata == null)
            return false;

        JSONArray articles = result.optJSONArray("articles");
        if (articles == null || articles.length() == 0)
            return false;

        // Vérification basique des métadonnées
        String lawDate = metadata.optString("lawDate", "");
        String lawNumber = metadata.optString("lawNumber", "");

        // Au moins un champ de métadonnée ET au moins un article requis
        boolean hasMetadata = !lawDate.isEmpty() || !lawNumber.isEmpty();
        boolean hasArticles = articles.length() > 0;
        return hasMetadata && hasArticles;
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
}
