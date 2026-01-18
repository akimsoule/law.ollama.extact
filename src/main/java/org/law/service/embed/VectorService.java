package org.law.service.embed;

import org.json.JSONArray;
import org.json.JSONObject;
import org.law.service.embed.Embedder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Service pour vectoriser les articles JSON et stocker les vecteurs.
 * Utilise le modèle local all-MiniLM-L6-v2-q (LangChain4J) pour générer les
 * embeddings.
 * Dimension: 384
 */
public class VectorService {

    private static final String ARTICLE_DIR = "src/main/resources/data/article";
    private static final String VECTOR_DIR = "src/main/resources/data/vector";

    private final Embedder embedder;

    public VectorService() {
        this.embedder = new Embedder();
    }

    /**
     * Point d'entrée pour vectoriser/re-vectoriser les articles.
     * Supprime les vecteurs existants puis en crée de nouveaux.
     */
    public static void main(String[] args) {
        try {
            VectorService vectorService = new VectorService();

            System.out.println("Suppression des vecteurs existants...");
            vectorService.deleteAllVectors();
            System.out.println("Vecteurs supprimés avec succès.");

            System.out.println("Début de la vectorisation des articles...");
            long startTime = System.currentTimeMillis();
            vectorService.vectorizeAllArticles();
            long endTime = System.currentTimeMillis();

            System.out.println("Vectorisation terminée en " + (endTime - startTime) + " ms");
        } catch (IOException e) {
            System.err.println("Erreur lors de la vectorisation : " + e.getMessage());
            e.printStackTrace();
        }
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
                                System.err.println("Erreur lors de la suppression de " + path + " : " + e.getMessage());
                            }
                        });
            }
            System.out.println("Répertoire " + vectorDir.toAbsolutePath() + " supprimé.");
        } else {
            System.out.println("Le répertoire " + vectorDir.toAbsolutePath() + " n'existe pas.");
        }
    }

    /**
     * Vectorise tous les fichiers JSON du répertoire article/ et stocke les
     * vecteurs.
     */
    public void vectorizeAllArticles() throws IOException {
        Path articleDir = Paths.get(ARTICLE_DIR);
        Path vectorDir = Paths.get(VECTOR_DIR);

        // Créer le répertoire vector s'il n'existe pas
        Files.createDirectories(vectorDir);

        try (Stream<Path> jsonFiles = Files.list(articleDir)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()) {
            jsonFiles.forEachOrdered(jsonPath -> {
                try {
                    System.out.println("Vectorisation de " + jsonPath.getFileName() + "...");
                    vectorizeArticle(jsonPath, vectorDir);
                    System.out.println("✓ Vectorisation réussie pour " + jsonPath.getFileName());
                } catch (IOException e) {
                    System.err.println("✗ Erreur lors de la vectorisation de " + jsonPath + " : " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            });
        }
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

        System.out.println("Vecteurs sauvegardés dans : " + vectorPath.toAbsolutePath());
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
