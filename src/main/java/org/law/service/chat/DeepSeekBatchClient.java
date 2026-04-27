package org.law.service.chat;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.law.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client DeepSeek optimisé pour le traitement en batch de lois.
 */
public class DeepSeekBatchClient implements AutoCloseable {

    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String JSON_CHOICES = "choices";
    private static final String JSON_MESSAGE = "message";
    private static final String SYSTEM_PROMPT = "You are a specialized legal document parser for Beninese laws.";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekBatchClient.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final String apiKey;
    private final Config config;

    public DeepSeekBatchClient() {
        this.config = Config.getInstance();
        this.apiKey = config.getDeepSeekApiKey();
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your_deepseek_api_key_here")) {
            throw new IllegalStateException(
                    "Clé API DeepSeek non configurée. Définissez 'deepseek.api.key' dans config.properties ou DEEPSEEK_API_KEY en environnement");
        }

        // DeepSeek a des limits plus élevées, on peut utiliser un batch size plus grand
        int configuredBatchSize = config.getDeepSeekBatchSize();
        int actualBatchSize = Math.min(configuredBatchSize, 10); // DeepSeek permet plus de parallélisme

        int timeoutSeconds = config.getDeepSeekTimeoutSeconds();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newFixedThreadPool(actualBatchSize);

        LOGGER.info("[DeepSeekBatchClient] Initialisé avec modèle par défaut: {}", DEFAULT_MODEL);
        LOGGER.info("[DeepSeekBatchClient] 💰 Tarifs ultra-économiques: $0.28/input, $0.42/output par million tokens");
        LOGGER.info("[DeepSeekBatchClient] 🚀 Batch size: {}", actualBatchSize);
    }

    /**
     * Traite un batch de requêtes en parallèle pour optimiser les performances.
     */
    public List<String> processBatch(List<String> prompts) {
        return processBatch(prompts, DEFAULT_MODEL);
    }

    public List<String> processBatch(List<String> prompts, String model) {
        LOGGER.info("[DeepSeekBatchClient] Traitement batch de {} requêtes avec modèle {}", prompts.size(), model);

        List<Future<String>> futures = new ArrayList<>();
        List<String> results = new ArrayList<>(prompts.size());

        // Initialiser la liste avec des valeurs nulles pour maintenir l'ordre
        for (int i = 0; i < prompts.size(); i++) {
            results.add(null);
        }

        // Soumettre les tâches en parallèle (pas de délais pour DeepSeek)
        for (int i = 0; i < prompts.size(); i++) {
            final int index = i;
            final String prompt = prompts.get(i);

            Future<String> future = executor.submit(() -> {
                try {
                    return processSingleWithRetry(prompt, model);
                } catch (IOException | InterruptedException e) {
                    LOGGER.error("[DeepSeekBatchClient] Erreur traitement requête {}: {}", index, e.getMessage(), e);
                    Thread.currentThread().interrupt();
                    return null;
                }
            });
            futures.add(future);
        }

        // Récupérer les résultats en préservant l'ordre
        for (int i = 0; i < futures.size(); i++) {
            try {
                String result = futures.get(i).get(60, TimeUnit.SECONDS); // Timeout plus long pour DeepSeek
                results.set(i, result);
            } catch (TimeoutException e) {
                LOGGER.error("[DeepSeekBatchClient] Timeout requête {}", i, e);
                results.set(i, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("[DeepSeekBatchClient] Interruption récupération résultat {}", i, e);
                results.set(i, null);
            } catch (ExecutionException e) {
                LOGGER.error("[DeepSeekBatchClient] Erreur récupération résultat {}: {}", i, e.getMessage(), e);
                results.set(i, null);
            }
        }

        long successCount = results.stream().filter(Objects::nonNull).count();
        LOGGER.info("[DeepSeekBatchClient] Batch terminé: {}/{} succès", successCount, prompts.size());

        return results;
    }

    /**
     * Traite une requête unique avec mécanisme de retry.
     */
    private String processSingleWithRetry(String prompt, String model) throws IOException, InterruptedException {
        return processSingleWithRetry(prompt, model, config.getDeepSeekTemperature());
    }

    private String processSingleWithRetry(String prompt, String model, double temperature)
            throws IOException, InterruptedException {
        int maxRetries = config.getDeepSeekMaxRetries();
        long retryDelayMs = config.getDeepSeekRetryDelayMs();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return processSingle(prompt, model, temperature);
            } catch (IOException e) {
                LOGGER.error("[DeepSeekBatchClient] Erreur tentative {}: {}", attempt, e.getMessage(), e);
                if (attempt == maxRetries) {
                    throw e;
                }
                Thread.sleep(retryDelayMs * attempt);
            }
        }
        throw new IllegalStateException("Échec après " + maxRetries + " tentatives");
    }

    /**
     * Traite une requête unique.
     */
    private String processSingle(String prompt, String model, double temperature) throws IOException {
        // Construire le corps de la requête
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", config.getDeepSeekMaxTokens());

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(FIELD_ROLE, "system", FIELD_CONTENT, SYSTEM_PROMPT));
        messages.add(Map.of(FIELD_ROLE, "user", FIELD_CONTENT, prompt));
        requestBody.put("messages", messages);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // Créer la requête HTTP
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(DEEPSEEK_API_URL)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        // Exécuter la requête
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DeepSeek API error: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            JsonNode jsonResponse = objectMapper.readTree(responseBody);

            if (jsonResponse.has(JSON_CHOICES) && jsonResponse.get(JSON_CHOICES).size() > 0) {
                JsonNode choice = jsonResponse.get(JSON_CHOICES).get(0);
                if (choice.has(JSON_MESSAGE) && choice.get(JSON_MESSAGE).has(FIELD_CONTENT)) {
                    return choice.get(JSON_MESSAGE).get(FIELD_CONTENT).asText();
                }
            }

            throw new IOException("Invalid response format from DeepSeek API");
        }
    }

    /**
     * Traite une requête simple (compatibilité avec code existant).
     */
    public String generate(String prompt, String model, double temperature) {
        try {
            String effectiveModel = model != null ? model : DEFAULT_MODEL;
            return processSingleWithRetry(prompt, effectiveModel, temperature);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[DeepSeekBatchClient] Erreur génération: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("[DeepSeekBatchClient] Erreur génération: " + e.getMessage(), e);
        }
    }

    /**
     * Vérifie la disponibilité du service.
     */
    public boolean isAvailable() {
        try {
            String testPrompt = "Test connection - respond with 'OK'";
            String response = processSingleWithRetry(testPrompt, DEFAULT_MODEL);
            return response != null && !response.trim().isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOGGER.warn("[DeepSeekBatchClient] Vérification disponibilité échouée: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Traite un batch avec le modèle rapide pour les tâches simples.
     */
    public List<String> processBatchFast(List<String> prompts) {
        return processBatch(prompts, DEFAULT_MODEL);
    }

    /**
     * Ferme proprement les ressources.
     */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Statistiques sur les modèles disponibles.
     */
    public void printModelInfo() {
        LOGGER.info("[DeepSeekBatchClient] Modèles disponibles:");
        LOGGER.info("  - {} (modèle par défaut, ultra-économique)", DEFAULT_MODEL);
        LOGGER.info("  - deepseek-reasoner (pour raisonnement complexe)");
        LOGGER.info("  💰 Tarifs: $0.28/input, $0.42/output par million tokens");
        LOGGER.info("  🚀 Performance: Traitement parallèle optimisé");
    }
}
