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
 * - Lit les fichiers JSON des articles (data/article/*.json)
 * - Lit les embeddings (data/vector/*.json)
 * - Crée les nœuds Article et Loi avec les relations
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
    private final String articleDir;
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
        this.articleDir = "src/main/resources/data/article";
        this.vectorDir = "src/main/resources/data/vector";
    }

    /**
     * Initialise le service avec les paramètres fournis.
     */
    public Neo4jIngestService(String uri, String user, String password) {
        this.neo4jUri = uri;
        this.neo4jUser = user;
        this.neo4jPassword = password;
        this.articleDir = "src/main/resources/data/article";
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
     * Ingère un seul article de manière idempotente (mise à jour si existe).
     * 
     * @param articleFilePath chemin vers le fichier JSON de l'article
     * @throws IOException en cas d'erreur de lecture
     */
    public void ingestArticleFile(Path articleFilePath) throws IOException {
        if (!Files.exists(articleFilePath)) {
            System.err.println("❌ Fichier article non trouvé: " + articleFilePath.toAbsolutePath());
            return;
        }

        String fileName = articleFilePath.getFileName().toString();
        String vectorFileName = fileName.replace(".json", "_vectors.json");
        Path vectorDirPath = Paths.get(vectorDir);
        Path vectorPath = vectorDirPath.resolve(vectorFileName);

        if (!Files.exists(vectorPath)) {
            System.out.println("⚠️  Fichier vecteur manquant: " + vectorFileName);
            System.out.println("   Ingestion de l'article sans vecteurs...");
        }

        try {
            String lawData = Files.readString(articleFilePath);
            String vectorData = Files.exists(vectorPath) ? Files.readString(vectorPath) : "{}";

            JSONObject lawJson = new JSONObject(lawData);
            JSONObject metadata = lawJson.getJSONObject("metadata");
            String lawNumber = metadata.getString("lawNumber");

            System.out.println("📄 Ingestion idempotente de: " + lawNumber);
            ingestLaw(lawData, vectorData);
            System.out.println("✅ Ingestion idempotente réussie: " + lawNumber);
        } catch (Exception e) {
            System.err.println("❌ Erreur lors de l'ingestion idempotente de " + fileName + ": " + e.getMessage());
            throw new IOException(e);
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

        try {
            // Contraintes d'unicité sur les IDs pour éviter les doublons
            try {
                session.run("CREATE CONSTRAINT article_id_unique IF NOT EXISTS FOR (a:Article) REQUIRE a.id IS UNIQUE");
                System.out.println("✅ Contrainte d'unicité créée pour Article.id");
            } catch (Neo4jException e) {
                System.out.println("⚠️  Contrainte Article.id déjà existante ou non supportée");
            }

            try {
                session.run("CREATE CONSTRAINT loi_id_unique IF NOT EXISTS FOR (l:Loi) REQUIRE l.id IS UNIQUE");
                System.out.println("✅ Contrainte d'unicité créée pour Loi.id");
            } catch (Neo4jException e) {
                System.out.println("⚠️  Contrainte Loi.id déjà existante ou non supportée");
            }

            // Index sur les IDs (créés automatiquement par les contraintes dans Neo4j 4.x+)
            System.out.println("✅ Index créés automatiquement via contraintes");

            // Index vectoriel pour les embeddings (Neo4j 5.x+)
            // all-MiniLM-L6-v2-q génère des vecteurs de dimension 384
            try {
                session.run("""
                        CREATE VECTOR INDEX article_embeddings IF NOT EXISTS
                        FOR (a:Article) ON (a.embedding)
                        OPTIONS {
                          indexConfig: {
                            `vector.similarity_function`: 'cosine',
                            `vector.dimensions`: 384
                          }
                        }
                        """);
                System.out.println("✅ Index vectoriel créé (all-MiniLM-L6-v2-q, dim 384)");
            } catch (Neo4jException e) {
                System.out.println("⚠️  Index vectoriel non supporté (Neo4j 5.x+ requis)");
            }
        } catch (Neo4jException e) {
            System.err.println("❌ Erreur lors de la création des contraintes/index: " + e.getMessage());
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
        Path articleDirPath = Paths.get(articleDir);
        Path vectorDirPath = Paths.get(vectorDir);

        if (!Files.exists(articleDirPath)) {
            System.err.println("❌ Dossier articles non trouvé: " + articleDirPath.toAbsolutePath());
            return;
        }

        try (Stream<Path> jsonFiles = Files.list(articleDirPath)
                .filter(p -> p.toString().endsWith(".json"))) {
            List<Path> articleFiles = jsonFiles.toList();

            System.out.println("\n📂 Trouvé " + articleFiles.size() + " fichier(s) de loi");

            int filesProcessed = 0;
            int filesDuplicated = 0;

            for (Path articlePath : articleFiles) {
                String fileName = articlePath.getFileName().toString();
                String vectorFileName = fileName.replace(".json", "_vectors.json");
                Path vectorPath = vectorDirPath.resolve(vectorFileName);

                if (!Files.exists(vectorPath)) {
                    System.out.println("⚠️  Fichier vecteur manquant: " + vectorFileName);
                    continue;
                }

                String lawData = Files.readString(articlePath);
                String vectorData = Files.readString(vectorPath);

                try {
                    JSONObject lawJson = new JSONObject(lawData);
                    JSONObject metadata = lawJson.getJSONObject("metadata");
                    String lawNumber = metadata.getString("lawNumber");

                    if (ingestedLaws.contains(lawNumber)) {
                        filesDuplicated++;
                        System.out.println("\n⏭️  Doublon détecté dans cette session: " + lawNumber);
                        System.out.println("   (Fichiers variantes: loi-2025-2.json et loi-2025-02.json)");
                        continue;
                    }

                    ingestLaw(lawData, vectorData);
                    filesProcessed++;
                } catch (Exception e) {
                    System.err.println("❌ Erreur lors de l'ingestion de " + fileName + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }

            System.out.println("\n📊 Résumé de l'ingestion:");
            System.out.println("   Fichiers traités: " + filesProcessed);
            if (filesDuplicated > 0) {
                System.out.println("   Fichiers doublons ignorés: " + filesDuplicated);
            }
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
