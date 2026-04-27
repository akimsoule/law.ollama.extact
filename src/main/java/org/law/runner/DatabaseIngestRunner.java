package org.law.runner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.law.service.embed.Embedder;
import org.law.service.ingest.Neo4jIngestService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DatabaseIngestRunner {

    private static final Path LLM_DIR = Paths.get("src/main/resources/data/llm_output");
    private static final Path SCRIPT_DIR = Paths.get("src/main/resources/data/script_output");
    private static final Path VECTOR_DIR = Paths.get("src/main/resources/data/vector");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(VECTOR_DIR);

        Neo4jIngestService neo4j = new Neo4jIngestService();
        neo4j.connect();
        neo4j.createConstraintsAndIndexes();

        Map<String, Path> lawFiles = new LinkedHashMap<>();

        if (Files.exists(LLM_DIR)) {
            try (Stream<Path> s = Files.list(LLM_DIR).filter(p -> p.toString().endsWith(".json"))) {
                lawFiles.putAll(s.collect(Collectors.toMap(DatabaseIngestRunner::extractLawNumber, p -> p)));
            }
        }

        if (Files.exists(SCRIPT_DIR)) {
            try (Stream<Path> s = Files.list(SCRIPT_DIR).filter(p -> p.toString().endsWith(".json"))) {
                s.forEach(p -> {
                    String lawNumber = extractLawNumber(p);
                    lawFiles.putIfAbsent(lawNumber, p);
                });
            }
        }

        System.out.println("📊 À ingérer : " + lawFiles.size() + " lois");

        Embedder embedder = new Embedder();
        for (Map.Entry<String, Path> entry : lawFiles.entrySet()) {
            String lawNumber = entry.getKey();
            Path jsonPath = entry.getValue();
            try {
                JSONObject lawJson = new JSONObject(Files.readString(jsonPath));
                JSONObject vectorJson = vectorizeLaw(lawJson, jsonPath.getFileName().toString(), embedder);
                neo4j.ingestLaw(lawJson.toString(2), vectorJson.toString(2));
                System.out.println("✅ Ingesté : " + lawNumber);
            } catch (Exception e) {
                System.err.println("❌ Échec ingestion " + lawNumber + " : " + e.getMessage());
            }
        }

        neo4j.disconnect();
        System.out.println("🏁 Ingestion terminée.");
    }

    private static String extractLawNumber(Path jsonPath) {
        try {
            JSONObject j = new JSONObject(Files.readString(jsonPath));
            return j.getJSONObject("metadata").optString("lawNumber", jsonPath.getFileName().toString());
        } catch (Exception e) {
            return jsonPath.getFileName().toString();
        }
    }

    private static JSONObject vectorizeLaw(JSONObject lawJson, String fileName, Embedder embedder) throws IOException {
        JSONObject vec = new JSONObject();
        vec.put("file", fileName);
        vec.put("timestamp", Instant.now().toString());

        JSONArray vecArticles = new JSONArray();
        for (int i = 0; i < lawJson.getJSONArray("articles").length(); i++) {
            JSONObject article = lawJson.getJSONArray("articles").getJSONObject(i);
            String index = article.optString("index", "");
            String content = article.optString("content", "");
            if (content.isBlank()) {
                continue;
            }
            double[] embedding = embedder.embed(content);
            JSONArray vectorArray = new JSONArray();
            for (double v : embedding) {
                vectorArray.put(v);
            }
            vecArticles.put(new JSONObject()
                    .put("index", index)
                    .put("vector", vectorArray));
        }

        vec.put("articles", vecArticles);
        Path cacheFile = VECTOR_DIR.resolve(fileName.replace(".json", "_vectors.json"));
        Files.writeString(cacheFile, vec.toString(2));
        return vec;
    }
}
