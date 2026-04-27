package org.law.service.embed;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.law.service.embed.Embedder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service pour vectoriser les articles JSON et stocker les vecteurs.
 * Utilise le modèle local all-MiniLM-L6-v2-q (LangChain4J) pour générer les
 * embeddings.
 * Dimension: 384
 */
public class VectorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VectorService.class);


    private static final String ARTICLE_DIR = "src/main/resources/data/article";
    private static final String VECTOR_DIR = "src/main/resources/data/vector";
    private static final Pattern ARTICLE_JSON_PATTERN = Pattern.compile("^loi-(\\d{2}|\\d{4})-(\\d{2}|\\d{3})\\.json$", Pattern.CASE_INSENSITIVE);

    private final Embedder embedder;

    public VectorService() {
        this.embedder = new Embedder();
    }

    
    /**
     * Supprime tous les vecteurs du répertoire vector/.
     */
    public void deleteAllVectors() throws IOException {
        Path vectorDir = Paths.get(VECTOR_DIR);

        if (Files.exists(vectorDir)) {
            try (Stream<Path> paths = Files.walk(vectorDir)) {
                paths.sorted((p1, p2) -> p2.compareTo(p1)) // Tri inverse pour supprimer les fichiers avant les
                                                           // répertoires
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                LOGGER.error("Erreur lors de la suppression de " + path + " : " + e.getMessage());
                            }
                        });
            }
            LOGGER.info("Répertoire " + vectorDir.toAbsolutePath() + " supprimé.");
        } else {
            LOGGER.info("Le répertoire " + vectorDir.toAbsolutePath() + " n'existe pas.");
        }
    }

    /**
     * Vectorise tous les fichiers JSON du répertoire article/ et stocke les
     * vecteurs.
     */
    public void vectorizeAllArticles() throws IOException {
        Path articleDir = Paths.get(ARTICLE_DIR);

        try (Stream<Path> jsonFiles = Files.list(articleDir)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()) {
            vectorizeArticles(jsonFiles.collect(Collectors.toList()));
        }
    }

    public void vectorizeArticles(List<Path> jsonFiles) throws IOException {
        Path vectorDir = Paths.get(VECTOR_DIR);
        Files.createDirectories(vectorDir);

        List<Path> validJsonFiles = new ArrayList<>();
        List<Path> ignoredJsonFiles = new ArrayList<>();

        for (Path jsonPath : jsonFiles) {
            if (ARTICLE_JSON_PATTERN.matcher(jsonPath.getFileName().toString()).matches()) {
                validJsonFiles.add(jsonPath);
            } else {
                ignoredJsonFiles.add(jsonPath);
            }
        }

        for (Path ignoredPath : ignoredJsonFiles) {
            LOGGER.info("Ignore (nom JSON non conforme) : " + ignoredPath.getFileName());
        }

        validJsonFiles.forEach(jsonPath -> {
            try {
                LOGGER.info("Vectorisation de " + jsonPath.getFileName() + "...");
                vectorizeArticle(jsonPath, vectorDir);
                LOGGER.info("✓ Vectorisation réussie pour " + jsonPath.getFileName());
            } catch (IOException e) {
                LOGGER.error("✗ Erreur lors de la vectorisation de " + jsonPath + " : " + e.getMessage());
                e.printStackTrace(System.err);
            }
        });

        LOGGER.info("Resume vectorisation: " + validJsonFiles.size() + " fichiers vectorises, "
                + ignoredJsonFiles.size() + " ignores.");
    }

    /**
     * Vectorise un article JSON spécifique.
     *
     * @param jsonPath  chemin du fichier JSON
     * @param vectorDir répertoire de destination des vecteurs
     */
    private void vectorizeArticle(Path jsonPath, Path vectorDir) throws IOException {
        String jsonContent = Files.readString(jsonPath);
        JSONObject jsonObject = new JSONObject(jsonContent);

        // Créer un objet pour stocker les vecteurs
        JSONObject vectorObject = new JSONObject();
        vectorObject.put("file", jsonPath.getFileName().toString());
        vectorObject.put("timestamp", System.currentTimeMillis());

        JSONArray articlesArray = jsonObject.optJSONArray("articles");
        if (articlesArray != null) {
            JSONArray vectorizedArticles = new JSONArray();

            for (int i = 0; i < articlesArray.length(); i++) {
                JSONObject article = articlesArray.getJSONObject(i);
                String articleContent = article.getString("content");
                String articleIndex = article.getString("index");

                // Générer le vecteur pour cet article
                double[] vector = embedder.embed(articleContent);

                JSONObject vectoredArticle = new JSONObject();
                vectoredArticle.put("index", articleIndex);
                vectoredArticle.put("vector", new JSONArray(vector));

                vectorizedArticles.put(vectoredArticle);
            }

            vectorObject.put("articles", vectorizedArticles);
        }

        // Récupérer et vectoriser les métadonnées
        JSONObject metadata = jsonObject.optJSONObject("metadata");
        if (metadata != null) {
            JSONObject vectoredMetadata = new JSONObject();

            String lawNumber = metadata.optString("lawNumber", "");
            if (!lawNumber.isEmpty()) {
                double[] vector = embedder.embed(lawNumber);
                vectoredMetadata.put("lawNumber_vector", new JSONArray(vector));
            }

            String lawObject = metadata.optString("lawObject", "");
            if (!lawObject.isEmpty()) {
                double[] vector = embedder.embed(lawObject);
                vectoredMetadata.put("lawObject_vector", new JSONArray(vector));
            }

            vectorObject.put("metadata_vectors", vectoredMetadata);
        }

        // Sauvegarder les vecteurs
        String vectorFileName = jsonPath.getFileName().toString().replace(".json", "_vectors.json");
        Path vectorPath = vectorDir.resolve(vectorFileName);
        Files.writeString(vectorPath, vectorObject.toString(4));

        LOGGER.info("Vecteurs sauvegardés dans : " + vectorPath.toAbsolutePath());
    }

    /**
     * Vectorise un texte spécifique et retourne le vecteur.
     *
     * @param text le texte à vectoriser
     * @return le vecteur généré (dimension 384)
     */
    public double[] embedText(String text) {
        return embedder.embed(text);
    }
}
