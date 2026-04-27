package org.law.runner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.law.service.extract.OcrService;
import org.law.service.process.QAService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class LlmExtractorRunner {

    private static final Path PDF_DIR = Paths.get("src/main/resources/data/loi");
    private static final Path OUTPUT_DIR = Paths.get("src/main/resources/data/llm_output");
    private static final Path CACHE_DIR = Paths.get("src/main/resources/data/llm_cache");
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-v4-flash";
    private static final String ERRORS_LOG = "errors.log";
    private static final double TEMPERATURE = 0.1;
    private static final int MAX_TOKENS = 32000;
    private static final int MAX_RETRIES = 3;

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ DEEPSEEK_API_KEY manquante.");
            return;
        }

        Files.createDirectories(OUTPUT_DIR);
        Files.createDirectories(CACHE_DIR);

        boolean force = Arrays.asList(args).contains("--force");
        String targetPdf = null;
        for (int i = 0; i < args.length; i++) {
            if ("--pdf".equals(args[i]) && i + 1 < args.length) {
                targetPdf = args[i + 1];
            }
        }

        QAService qa = new QAService();

        try (Stream<Path> pdfs = Files.list(PDF_DIR).filter(p -> p.toString().endsWith(".pdf"))) {
            for (Path pdfPath : pdfs.toList()) {
                String pdfName = pdfPath.getFileName().toString();
                if (targetPdf != null && !pdfName.equals(targetPdf)) {
                    continue;
                }

                String baseName = pdfName.replace(".pdf", "");
                Path targetJson = OUTPUT_DIR.resolve(baseName + ".json");

                if (!force && Files.exists(targetJson)) {
                    System.out.println("⏭️ SKIP (LLM existe déjà) : " + pdfName);
                    continue;
                }

                System.out.println("🤖 TRAITEMENT LLM : " + pdfName);
                try {
                    String ocrText = new OcrService().getOcrText(pdfPath.toFile());
                    JSONObject json = callDeepSeekAndValidate(pdfName, ocrText, apiKey, force);

                    List<String> qaErrors = qa.validateJson(json);

                    Files.writeString(targetJson, json.toString(2));

                    if (!qaErrors.isEmpty()) {
                        StringBuilder logEntry = new StringBuilder();
                        logEntry.append("[").append(Instant.now()).append("] ERREUR QA SUR ").append(pdfName)
                                .append("\n");
                        for (String err : qaErrors) {
                            logEntry.append("  - ").append(err).append("\n");
                        }
                        logEntry.append("---\n");

                        Files.writeString(Paths.get(ERRORS_LOG), logEntry.toString(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        System.err.println("   ⚠️ QA Failed -> voir " + ERRORS_LOG);
                    } else {
                        System.out.println("✅ LLM OK & QA Validé : " + targetJson);
                    }
                } catch (Exception e) {
                    String errMsg = "[" + Instant.now() + "] CRASH " + pdfName + ": " + e.getMessage() + "\n---\n";
                    Files.writeString(Paths.get(ERRORS_LOG), errMsg, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    System.err.println("❌ Échec LLM sur " + pdfName + " (Loggé dans " + ERRORS_LOG + ")");
                }
            }
        }
    }

    private static JSONObject callDeepSeekAndValidate(String pdfName, String text, String apiKey, boolean force)
            throws Exception {
        Path cacheFile = CACHE_DIR.resolve(pdfName.replace(".pdf", ".json"));
        boolean useCache = !force && Files.exists(cacheFile);
        String rawJson;

        if (useCache) {
            System.out.println("💾 Cache Hit : " + pdfName);
            rawJson = Files.readString(cacheFile);
        } else {
            rawJson = callDeepSeekAPI(text, pdfName, apiKey);
            Files.writeString(cacheFile, rawJson);
        }

        JSONObject json = new JSONObject(rawJson);
        validateStrict(json, pdfName);
        return json;
    }

    private static String callDeepSeekAPI(String text, String pdfName, String apiKey) throws Exception {
        String system = """
                Tu es un expert juridique. Retourne UNIQUEMENT un JSON valide.
                SCHÉMA REQUIS : {"metadata":{"lawNumber":"","lawDate":"","lawObject":"","signatories":[{"name":"","role":""}]} ,"articles":[{"index":"","content":""}]}
                Règles : pas d'invention, préserve l'ordre, champs obligatoires lawNumber & articles.
                """;

        JSONObject payload = new JSONObject();
        payload.put("model", MODEL);
        payload.put("temperature", TEMPERATURE);
        payload.put("response_format", new JSONObject().put("type", "json_object"));
        payload.put("max_tokens", MAX_TOKENS);
        payload.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content",
                        "FICHIER SOURCE : " + pdfName + "\nTEXTE OCR :\n<<<\n" + text + "\n>>>")));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("API Error " + response.statusCode() + ": " + response.body());
        }

        JSONObject jsonResp = new JSONObject(response.body());
        if (jsonResp.has("error")) {
            throw new RuntimeException(jsonResp.getJSONObject("error").optString("message", "Erreur DeepSeek"));
        }

        if (jsonResp.has("usage")) {
            JSONObject usage = jsonResp.getJSONObject("usage");
            System.out.printf("   📊 Tokens: %d (In) + %d (Out) = %d%n",
                    usage.optInt("prompt_tokens"),
                    usage.optInt("completion_tokens"),
                    usage.optInt("total_tokens"));
        }

        return jsonResp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    }

    private static void validateStrict(JSONObject json, String pdfName) {
        if (!json.has("metadata") || !json.getJSONObject("metadata").has("lawNumber")
                || json.getJSONObject("metadata").optString("lawNumber").isBlank()) {
            throw new RuntimeException("lawNumber manquant pour " + pdfName);
        }

        if (!json.has("articles") || json.getJSONArray("articles").length() < 2) {
            throw new RuntimeException("Moins de 2 articles détectés pour " + pdfName);
        }

        JSONObject metadata = json.getJSONObject("metadata");
        metadata.put("source_pdf", pdfName);
        metadata.put("extracted_by", "LLM_DeepSeek");
        metadata.put("extracted_at", Instant.now().toString());
    }
}
