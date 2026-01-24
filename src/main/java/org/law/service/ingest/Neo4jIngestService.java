package org.law.service.ingest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.law.config.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.Neo4jException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Service d'ingestion des données juridiques dans Neo4j.
 * - Lit les fichiers JSON de la structure hiérarchique des nœuds
 * (data/node/*.json)
 * - Lit les embeddings (data/vector/*.json)
 * - Crée les nœuds Loi, Livre, Titre, Chapitre, Article avec les relations
 * hiérarchiques
 * - Indexe les embeddings pour la recherche sémantique
 *
 * MODES D'INGESTION :
 * - DROP_CREATE = true : Supprime tout et recrée (rechargement complet)
 * - DROP_CREATE = false : Ingestion incrémentale (pas de doublons)
 */
public class Neo4jIngestService {

    /**
     * Contrôle le mode d'ingestion :
     * - true : DROP_CREATE - Supprime toutes les données existantes avant d'ingérer
     * - false : INCRÉMENTAL - Ajoute/met à jour sans supprimer (par défaut)
     */
    private static final boolean DROP_CREATE = true;

    private final String neo4jUri;
    private final String neo4jUser;
    private final String neo4jPassword;
    private final String nodeDir;
    private final String vectorDir;
    private Driver driver;
    private Session session;
    private final java.util.Set<String> ingestedLaws = new java.util.HashSet<>();

    /**
     * Initialise le service avec les paramètres de Neo4j depuis la configuration.
     */
    public Neo4jIngestService() {
        this.neo4jUri = Config.getProperty("neo4j.uri", "neo4j://localhost:7687");
        this.neo4jUser = Config.getProperty("neo4j.user", "neo4j");
        this.neo4jPassword = Config.getProperty("neo4j.password", "password");
        this.nodeDir = "src/main/resources/data/node";
        this.vectorDir = "src/main/resources/data/vector";
    }

    /**
     * Initialise le service avec les paramètres fournis.
     */
    public Neo4jIngestService(String uri, String user, String password) {
        this.neo4jUri = uri;
        this.neo4jUser = user;
        this.neo4jPassword = password;
        this.nodeDir = "src/main/resources/data/node";
        this.vectorDir = "src/main/resources/data/vector";
    }

    /**
     * Établit la connexion à Neo4j.
     */
    public void connect() {
        this.driver = GraphDatabase.driver(neo4jUri,
                org.neo4j.driver.AuthTokens.basic(neo4jUser, neo4jPassword));
        this.session = driver.session();
        System.out.println("✅ Connexion établie à Neo4j");
    }

    /**
     * Ferme la connexion à Neo4j.
     */
    public void disconnect() {
        if (session != null) {
            session.close();
        }
        if (driver != null) {
            driver.close();
        }
        System.out.println("✅ Déconnexion de Neo4j");
    }

    /**
     * Ingère un seul fichier de nœud de manière idempotente.
     * 
     * @param nodeFilePath chemin vers le fichier JSON du nœud
     * @throws IOException en cas d'erreur de lecture
     */
    public void ingestNodeFile(Path nodeFilePath) throws IOException {
        if (!Files.exists(nodeFilePath)) {
            System.err.println("❌ Fichier nœud non trouvé: " + nodeFilePath.toAbsolutePath());
            return;
        }

        String fileName = nodeFilePath.getFileName().toString();
        String vectorFileName = fileName.replace(".json", "_vectors.json");
        Path vectorDirPath = Paths.get(vectorDir);
        Path vectorPath = vectorDirPath.resolve(vectorFileName);

        if (!Files.exists(vectorPath)) {
            System.out.println("⚠️  Fichier vecteur manquant: " + vectorFileName);
            System.out.println("   Ingestion du nœud sans vecteurs...");
        }

        try {
            String nodeData = Files.readString(nodeFilePath);
            String vectorData = Files.exists(vectorPath) ? Files.readString(vectorPath) : "{}";

            JSONObject rootNode = new JSONObject(nodeData);
            JSONObject vectorJson = new JSONObject(vectorData);

            System.out.println("📄 Ingestion de la structure hiérarchique: " + fileName);
            ingestNodeStructure(rootNode, vectorJson);
            System.out.println("✅ Ingestion réussie: " + fileName);
        } catch (Exception e) {
            System.err.println("❌ Erreur lors de l'ingestion de " + fileName + ": " + e.getMessage());
            throw new IOException(e);
        }
    }

    /**
     * Ingère la structure hiérarchique complète des nœuds.
     */
    private void ingestNodeStructure(JSONObject rootNode, JSONObject vectorJson) {
        JSONArray vectorNodes = vectorJson.optJSONArray("nodes");
        Map<String, double[]> vectorMap = new HashMap<>();

        // Construire une map des vecteurs par (type, number, index)
        if (vectorNodes != null) {
            for (int i = 0; i < vectorNodes.length(); i++) {
                JSONObject vectorNode = vectorNodes.getJSONObject(i);
                String type = vectorNode.optString("type", "");
                String number = vectorNode.optString("number", "");
                String index = vectorNode.optString("index", "");
                JSONArray vectorArray = vectorNode.optJSONArray("vector");

                if (vectorArray != null) {
                    double[] vector = new double[vectorArray.length()];
                    for (int j = 0; j < vectorArray.length(); j++) {
                        vector[j] = vectorArray.getDouble(j);
                    }
                    String key = type + "_" + number + "_" + index;
                    vectorMap.put(key, vector);
                }
            }
        }

        // Créer le nœud Loi et traverser la hiérarchie
        ingestNodeRecursive(rootNode, null, vectorMap);
    }

    /**
     * Traverse récursivement la structure et crée les nœuds avec relations.
     */
    private void ingestNodeRecursive(JSONObject node, String parentId, Map<String, double[]> vectorMap) {
        String type = node.optString("type", "");
        JSONObject metadata = node.optJSONObject("metadata");

        if (metadata == null) {
            return;
        }

        String nodeNumber = metadata.optString("number", "");
        String nodeIndex = metadata.optString("index", "");
        String nodeText = metadata.optString("text", "");

        if (type.isEmpty() || nodeText.isEmpty()) {
            // Si pas de contenu, parcourir les enfants
            JSONArray children = node.optJSONArray("children");
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.getJSONObject(i);
                    ingestNodeRecursive(child, parentId, vectorMap);
                }
            }
            return;
        }

        // Créer l'ID du nœud
        String nodeId = type + "_" + nodeNumber + "_" + nodeIndex;

        // Récupérer le vecteur
        String vectorKey = type + "_" + nodeNumber + "_" + nodeIndex;
        double[] embedding = vectorMap.get(vectorKey);

        // Créer le nœud Neo4j
        Map<String, Object> params = new HashMap<>();
        params.put("nodeId", nodeId);
        params.put("type", type);
        params.put("number", nodeNumber);
        params.put("index", nodeIndex);
        params.put("text", nodeText);
        if (embedding != null) {
            List<Double> embeddingList = new ArrayList<>();
            for (double v : embedding) {
                embeddingList.add(v);
            }
            params.put("embedding", embeddingList);
        }

        String query = String.format("""
                MERGE (n:%s {id: $nodeId})
                SET n.number = $number,
                    n.index = $index,
                    n.text = $text
                %s
                RETURN n
                """, type, embedding != null ? ", n.embedding = $embedding" : "");

        session.run(query, params);

        // Créer la relation avec le parent
        if (parentId != null) {
            session.run(String.format("""
                    MATCH (parent {id: $parentId})
                    MATCH (child {id: $childId})
                    MERGE (child)-[:ENFANT_DE]->(parent)
                    """), Map.of("parentId", parentId, "childId", nodeId));
        }

        // Traiter les enfants
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                ingestNodeRecursive(child, nodeId, vectorMap);
            }
        }
    }

    /**
     * Supprime tous les articles, lois et relations de la base de données.
     * Utile pour un rechargement complet.
     */
    public void clearDatabase() {
        System.out.println("\n🗑️  Suppression de toutes les données Neo4j...");

        try {
            // Supprimer les relations
            session.run("MATCH ()-[r:APPARTIENT_A]->() DELETE r");

            // Supprimer les articles
            session.run("MATCH (a:Article) DELETE a");

            // Supprimer les lois
            session.run("MATCH (l:Loi) DELETE l");

            System.out.println("✅ Base de données nettoyée");
        } catch (Neo4jException e) {
            System.err.println("❌ Erreur lors du nettoyage: " + e.getMessage());
        }
    }

    /**
     * Crée les contraintes et index Neo4j pour garantir l'unicité.
     */
    public void createConstraintsAndIndexes() {
        System.out.println("\n🔍 Création des contraintes et index Neo4j...");

        try (var tx = session.beginTransaction()) {
            // Contraintes d'unicité sur les IDs
            String[] nodeTypes = { "Loi", "Livre", "Titre", "Chapitre", "Article" };
            for (String nodeType : nodeTypes) {
                tx.run(String.format(
                        "CREATE CONSTRAINT %s_id_unique IF NOT EXISTS FOR (n:%s) REQUIRE n.id IS UNIQUE",
                        nodeType.toLowerCase(), nodeType));
            }

            // Détecter dimension des embeddings
            var dimRes = tx.run(
                    "MATCH (n) WHERE n.embedding IS NOT NULL RETURN size(n.embedding) AS dim LIMIT 1");
            if (!dimRes.hasNext()) {
                System.out.println("⚠️  Aucun embedding trouvé; index vectoriel non créé.");
            } else {
                int dim = dimRes.next().get("dim").asInt();
                System.out.println("➡️  Dimension embeddings détectée: " + dim);

                // Supprimer index existant cible (si présent)
                tx.run("DROP INDEX node_embeddings IF EXISTS");

                // Créer l’index vectoriel global
                tx.run(String.format("""
                            CREATE VECTOR INDEX node_embeddings
                            FOR (n) ON (n.embedding)
                            OPTIONS {
                              indexConfig: {
                                `vector.similarity_function`: 'cosine',
                                `vector.dimensions`: %d
                              }
                            }
                        """, dim));
                System.out.println("✅ Index vectoriel 'node_embeddings' créé (" + dim + " dims)");
            }

            tx.commit();
            System.out.println("✅ Contraintes et index configurés");
        } catch (org.neo4j.driver.exceptions.Neo4jException e) {
            System.err.println("❌ Erreur lors de la configuration: " + e.getMessage());
        }
    }

    /**
     * Ingère une loi avec ses articles et embeddings de manière incrémentale.
     * Ne crée pas de doublons grâce aux contraintes d'unicité et au tracking
     * intra-session.
     * 
     * @param lawData    le JSON des articles et métadonnées
     * @param vectorData le JSON des vecteurs
     * @return le lawNumber de la loi ingérée
     */
    public String ingestLaw(String lawData, String vectorData) throws IOException {
        JSONObject lawJson = new JSONObject(lawData);
        JSONObject vectorJson = new JSONObject(vectorData);

        JSONObject metadata = lawJson.getJSONObject("metadata");
        String lawNumber = metadata.getString("lawNumber");
        String lawDate = metadata.getString("lawDate");
        String lawObject = metadata.getString("lawObject");
        String source = metadata.optString("source", ""); // Récupérer la source si elle existe

        // Vérifier si la loi a déjà été ingérée dans cette session (idempotence
        // intra-session)
        if (ingestedLaws.contains(lawNumber)) {
            System.out.println("\n⏭️  Loi déjà ingérée dans cette session: " + lawNumber + " - " + lawObject);
            System.out.println("   (Fichiers dupliqués détectés: loi-2025-2.json et loi-2025-02.json)");
            return lawNumber;
        }

        System.out.println("\n📋 Ingestion: " + lawNumber + " - " + lawObject);

        // Vérifier si la loi existe déjà en BD
        var existingLaw = session.run(
                "MATCH (loi:Loi {id: $lawNumber}) RETURN loi",
                Map.of("lawNumber", lawNumber));

        boolean lawExists = existingLaw.hasNext();
        if (lawExists) {
            System.out.println("   ℹ️  Loi existante, mise à jour...");
        }

        // MERGE sur l'ID uniquement, puis SET des autres propriétés
        session.run(
                """
                        MERGE (loi:Loi {id: $lawNumber})
                        SET loi.titre = $lawObject,
                            loi.code = $lawNumber,
                            loi.date_promulgation = $lawDate,
                            loi.source = $source,
                            loi.statut = 'En vigueur'
                        RETURN loi
                        """,
                Map.of(
                        "lawNumber", lawNumber,
                        "lawObject", lawObject,
                        "lawDate", lawDate,
                        "source", source));

        // Créer les nœuds Article et les relations APPARTIENT_A
        JSONArray articles = lawJson.getJSONArray("articles");
        JSONArray vectorArticles = vectorJson.optJSONArray("articles");

        // Si DROP_CREATE est activé, supprimer les articles existants de cette loi
        if (DROP_CREATE) {
            session.run(
                    "MATCH (article:Article)-[:APPARTIENT_A]->(loi:Loi {id: $lawNumber}) DELETE article",
                    Map.of("lawNumber", lawNumber));
            System.out.println("   🗑️  Articles existants supprimés (mode DROP_CREATE)");
        }

        int articleCount = 0;
        int articleUpdated = 0;
        int articleSkipped = 0;

        for (int i = 0; i < articles.length(); i++) {
            JSONObject article = articles.getJSONObject(i);
            String articleIndex = article.getString("index");
            String articleContent = article.getString("content");
            String articleId = lawNumber + "_ART_" + articleIndex;

            // Récupérer l'embedding
            List<Double> embedding = null;
            if (vectorArticles != null) {
                for (int j = 0; j < vectorArticles.length(); j++) {
                    JSONObject vectorArticle = vectorArticles.getJSONObject(j);
                    if (vectorArticle.getString("index").equals(articleIndex)) {
                        JSONArray vectorArray = vectorArticle.getJSONArray("vector");
                        embedding = new ArrayList<>();
                        for (int k = 0; k < vectorArray.length(); k++) {
                            embedding.add(vectorArray.getDouble(k));
                        }
                        break;
                    }
                }
            }

            if (embedding == null) {
                System.out.println("⚠️  Pas d'embedding trouvé pour Article " + articleIndex);
                articleSkipped++;
                continue;
            }

            // Vérifier si l'article existe déjà
            var existingArticle = session.run(
                    "MATCH (article:Article {id: $articleId}) RETURN article",
                    Map.of("articleId", articleId));

            boolean articleExists = existingArticle.hasNext();

            // Créer l'Article (MERGE sur ID uniquement, puis SET)
            Map<String, Object> params = new HashMap<>();
            params.put("articleId", articleId);
            params.put("numeroArticle", "Article " + articleIndex);
            params.put("titreLoi", lawObject);
            params.put("contenu", articleContent);
            params.put("embedding", embedding);
            params.put("lawNumber", lawNumber);
            params.put("metadata", String.format("""
                    {
                        "lawNumber": "%s",
                        "lawDate": "%s",
                        "source": "%s"
                    }
                    """, lawNumber, lawDate, source));

            session.run("""
                    MERGE (article:Article {id: $articleId})
                    SET article.numero_article = $numeroArticle,
                        article.titre_loi = $titreLoi,
                        article.contenu = $contenu,
                        article.embedding = $embedding,
                        article.metadata = $metadata
                    WITH article
                    MATCH (loi:Loi {id: $lawNumber})
                    MERGE (article)-[:APPARTIENT_A]->(loi)
                    RETURN article
                    """, params);

            if (articleExists) {
                articleUpdated++;
            } else {
                articleCount++;
            }
        }

        if (lawExists) {
            System.out.println("✅ Loi mise à jour: " + articleCount + " nouveau(x) article(s), "
                    + articleUpdated + " mis à jour, " + articleSkipped + " ignoré(s)");
        } else {
            System.out.println("✅ " + articleCount + " article(s) créé(s) pour " + lawNumber);
        }

        // Tracker cette loi comme ingérée dans la session courante
        ingestedLaws.add(lawNumber);

        return lawNumber;
    }

    /**
     * Ingère tous les fichiers de loi disponibles.
     */
    public void ingestAll() throws IOException {
        // Réinitialiser le tracking des lois ingérées
        ingestedLaws.clear();

        // Nettoyer la base si DROP_CREATE est activé
        if (DROP_CREATE) {
            System.out.println("\n⚠️  Mode DROP_CREATE activé");
            clearDatabase();
        } else {
            System.out.println("\n✅ Mode INCRÉMENTAL activé");
        }

        loadData();
    }

    private void loadData() throws IOException {
        Path nodeDirPath = Paths.get(nodeDir);
        Path vectorDirPath = Paths.get(vectorDir);

        if (!Files.exists(nodeDirPath)) {
            System.err.println("❌ Dossier nœuds non trouvé: " + nodeDirPath.toAbsolutePath());
            return;
        }

        try (Stream<Path> jsonFiles = Files.list(nodeDirPath)
                .filter(p -> p.toString().endsWith(".json"))) {
            List<Path> nodeFiles = jsonFiles.toList();

            System.out.println("\n📂 Trouvé " + nodeFiles.size() + " fichier(s) de structure");

            int filesProcessed = 0;
            int filesDuplicated = 0;

            for (Path nodePath : nodeFiles) {
                String fileName = nodePath.getFileName().toString();
                String vectorFileName = fileName.replace(".json", "_vectors.json");
                Path vectorPath = vectorDirPath.resolve(vectorFileName);

                if (!Files.exists(vectorPath)) {
                    System.out.println("⚠️  Fichier vecteur manquant: " + vectorFileName);
                    continue;
                }

                String nodeData = Files.readString(nodePath);
                String vectorData = Files.readString(vectorPath);

                try {
                    ingestNodeStructure(new JSONObject(nodeData), new JSONObject(vectorData));
                    filesProcessed++;
                } catch (Exception e) {
                    System.err.println("❌ Erreur lors de l'ingestion de " + fileName + ": ");
                    e.printStackTrace();
                }
            }

            System.out.println("\n📊 Résumé de l'ingestion:");
            System.out.println("   Fichiers traités: " + filesProcessed);
        }

        System.out.println("\n🎉 Ingestion complétée!");
        printStats();
    }

    /**
     * Affiche les statistiques de la base de données.
     */
    public void printStats() {
        System.out.println("\n📊 Stats Neo4j:");

        try {
            var nodeStats = session.run("MATCH (n) RETURN count(n) as totalNodes");
            if (nodeStats.hasNext()) {
                long totalNodes = nodeStats.next().get("totalNodes").asLong();
                System.out.println("   Nœuds totaux: " + totalNodes);
            }

            var relationStats = session.run("MATCH ()-[r]->() RETURN count(r) as totalRelations");
            if (relationStats.hasNext()) {
                long totalRelations = relationStats.next().get("totalRelations").asLong();
                System.out.println("   Relations: " + totalRelations);
            }

            var loiCount = session.run("MATCH (l:Loi) RETURN count(l) as loiCount");
            if (loiCount.hasNext()) {
                long count = loiCount.next().get("loiCount").asLong();
                System.out.println("   Lois: " + count);
            }

            var articleCount = session.run("MATCH (a:Article) RETURN count(a) as articleCount");
            if (articleCount.hasNext()) {
                long count = articleCount.next().get("articleCount").asLong();
                System.out.println("   Articles: " + count);
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur lors de la récupération des stats: " + e.getMessage());
        }
    }

    /**
     * Point d'entrée pour lancer l'ingestion.
     */
    public static void main(String[] args) {
        Neo4jIngestService service = new Neo4jIngestService();

        try {
            service.connect();
            service.createConstraintsAndIndexes();
            service.ingestAll();
        } catch (Exception e) {
            System.err.println("❌ Erreur: " + e.getMessage());
            e.printStackTrace();
        } finally {
            service.disconnect();
        }
    }
}
