package org.law.service.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Client pour communiquer avec le serveur Ollama pour la génération de texte.
 * Permet d'effectuer du chat/completion avec les modèles Ollama.
 */
public class OllamaChatClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private static final String GENERATE_ENDPOINT = "/api/generate";

    public OllamaChatClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Envoie un prompt au modèle Ollama et retourne la réponse générée.
     *
     * @param prompt le prompt à envoyer
     * @param model  le modèle Ollama à utiliser (ex: "llama2", "mistral")
     * @return la réponse générée par le modèle
     * @throws IOException en cas d'erreur de communication
     */
    public String generate(String prompt, String model) throws IOException {
        return generate(prompt, model, 0.7);
    }

    /**
     * Envoie un prompt au modèle Ollama avec contrôle de température.
     *
     * @param prompt      le prompt à envoyer
     * @param model       le modèle Ollama à utiliser
     * @param temperature contrôle la créativité (0.0 = déterministe, 1.0 = très
     *                    créatif)
     * @return la réponse générée par le modèle
     * @throws IOException en cas d'erreur de communication
     */
    public String generate(String prompt, String model, double temperature) throws IOException {
        String endpoint = baseUrl + GENERATE_ENDPOINT;

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("temperature", temperature);
        requestBody.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Erreur Ollama : " + response.statusCode() + " - " + response.body());
            }

            JSONObject responseBody = new JSONObject(response.body());
            String generatedText = responseBody.getString("response");

            if (generatedText == null || generatedText.isEmpty()) {
                throw new IOException("Aucune réponse générée par Ollama");
            }

            return generatedText;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requête Ollama interrompue", e);
        }
    }

    /**
     * Vérifie la connexion au serveur Ollama.
     *
     * @return true si Ollama est accessible
     */
    public boolean isAvailable() {
        try {
            String endpoint = baseUrl + "/api/tags";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Retourne la liste des modèles disponibles sur le serveur Ollama.
     *
     * @return liste des noms de modèles disponibles
     * @throws IOException en cas d'erreur de communication
     */
    public String[] listModels() throws IOException {
        try {
            String endpoint = baseUrl + "/api/tags";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Erreur Ollama : " + response.statusCode());
            }

            JSONObject responseBody = new JSONObject(response.body());
            JSONArray modelsArray = responseBody.getJSONArray("models");

            String[] models = new String[modelsArray.length()];
            for (int i = 0; i < modelsArray.length(); i++) {
                models[i] = modelsArray.getJSONObject(i).getString("name");
            }

            return models;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requête Ollama interrompue", e);
        }
    }
}
