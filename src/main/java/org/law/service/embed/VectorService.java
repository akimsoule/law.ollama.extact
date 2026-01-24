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
 * Service pour vectoriser les articles extraits de la structure JSON des nœuds.
 * Utilise le modèle local all-MiniLM-L6-v2-q (LangChain4J) pour générer les
 * embeddings.
 * Dimension: 384
 */
public class VectorService {

    private static final String NODE_DIR = "src/main/resources/data/node";
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
     * Vectorise tous les fichiers JSON du répertoire node/ et stocke les
     * vecteurs.
     */
    public void vectorizeAllArticles() throws IOException {
        Path nodeDir = Paths.get(NODE_DIR);
        Path vectorDir = Paths.get(VECTOR_DIR);

        // Créer le répertoire vector s'il n'existe pas
        Files.createDirectories(vectorDir);

        try (Stream<Path> jsonFiles = Files.list(nodeDir)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()) {
            jsonFiles.forEachOrdered(jsonPath -> {
                try {
                    System.out.println("Vectorisation de " + jsonPath.getFileName() + "...");
                    vectorizeNodeFile(jsonPath, vectorDir);
                    System.out.println("✓ Vectorisation réussie pour " + jsonPath.getFileName());
                } catch (IOException e) {
                    System.err.println("✗ Erreur lors de la vectorisation de " + jsonPath + " : " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            });
        }
    }

    /**
     * Vectorise un fichier de nœud JSON spécifique.
     * Traverse la structure hiérarchique et vectorise tous les nœuds trouvés.
     *
     * @param nodePath  chemin du fichier JSON de nœud
     * @param vectorDir répertoire de destination des vecteurs
     */
    private void vectorizeNodeFile(Path nodePath, Path vectorDir) throws IOException {
        String jsonContent = Files.readString(nodePath);
        JSONObject rootNode = new JSONObject(jsonContent);

        // Créer un objet pour stocker les vecteurs
        JSONObject vectorObject = new JSONObject();
        vectorObject.put("file", nodePath.getFileName().toString());
        vectorObject.put("timestamp", System.currentTimeMillis());

        JSONArray vectorizedNodes = new JSONArray();

        // Parcourir la structure hiérarchique et extraire tous les nœuds
        extractAndVectorizeNodes(rootNode, vectorizedNodes);

        vectorObject.put("nodes", vectorizedNodes);

        // Sauvegarder les vecteurs
        String vectorFileName = nodePath.getFileName().toString().replace(".json", "_vectors.json");
        Path vectorPath = vectorDir.resolve(vectorFileName);
        Files.writeString(vectorPath, vectorObject.toString(4));

        System.out.println("Vecteurs sauvegardés dans : " + vectorPath.toAbsolutePath());
    }

    /**
     * Traverse récursivement la structure du nœud et extrait tous les nœuds
     * pour les vectoriser.
     */
    private void extractAndVectorizeNodes(JSONObject node, JSONArray vectorizedNodes) {
        String type = node.optString("type", "");

        // Vectoriser tous les nœuds qui ont du texte dans les métadonnées
        JSONObject metadata = node.optJSONObject("metadata");
        if (metadata != null) {
            String nodeText = metadata.optString("text", "");
            String nodeNumber = metadata.optString("number", "");
            String nodeIndex = metadata.optString("index", "");

            if (!nodeText.isEmpty()) {
                // Générer le vecteur pour ce nœud
                double[] vector = embedder.embed(nodeText);

                JSONObject vectoredNode = new JSONObject();
                vectoredNode.put("type", type);
                vectoredNode.put("number", nodeNumber);
                vectoredNode.put("index", nodeIndex);
                vectoredNode.put("vector", new JSONArray(vector));

                vectorizedNodes.put(vectoredNode);
            }
        }

        // Parcourir les enfants
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                extractAndVectorizeNodes(child, vectorizedNodes);
            }
        }
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
