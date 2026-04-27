package org.law.service.ingest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.law.config.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.Neo4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
public class Neo4jIngestService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jIngestService.class);

    private final boolean dropCreate;

    private final String neo4jUri;
    private final String neo4jUser;
    private final String neo4jPassword;
    private final String articleDir;
    private final String vectorDir;

    private Driver driver;
    private Session session;
    private final java.util.Set<String> ingestedLaws = new java.util.HashSet<>();

    // Compteurs pour les logs
    private int createdLaws = 0;
    private int createdArticles = 0;
    private int deletedArticles = 0;
    private int deletedNodes = 0;

    public Neo4jIngestService() {
        Config config = Config.getInstance();
        this.neo4jUri = config.getProperty("neo4j.uri", "neo4j://localhost:7687");
        this.neo4jUser = config.getProperty("neo4j.user", "neo4j");
        this.neo4jPassword = config.getProperty("neo4j.password", "password");
        this.articleDir = "src/main/resources/data/article";
        this.vectorDir = "src/main/resources/data/vector";
        this.dropCreate = config.getBooleanProperty("neo4j.drop.create", true);
    }

    public void connect() {
        this.driver = GraphDatabase.driver(neo4jUri,
                org.neo4j.driver.AuthTokens.basic(neo4jUser, neo4jPassword));
        this.session = driver.session();
        logger.info("Connexion Neo4j établie");
    }

    public void disconnect() {
        if (session != null)
            session.close();
        if (driver != null)
            driver.close();
        logger.info("Déconnexion Neo4j");
    }

    /** Nettoyage complet */
    public void clearDatabase() {
        try {
            // Compter les nœuds avant suppression
            var countResult = session.run("MATCH (n) RETURN count(n) as count");
            deletedNodes = countResult.single().get("count").asInt();

            session.run("MATCH (n) DETACH DELETE n");
            logger.info("Base vidée : {} nœuds supprimés", deletedNodes);
        } catch (Neo4jException e) {
            logger.error("Erreur clearDatabase: {}", e.getMessage());
        }
    }

    /** Contraintes & index */
    public void createConstraintsAndIndexes() {
        try {
            session.run("CREATE CONSTRAINT article_id_unique IF NOT EXISTS FOR (a:ARTICLE) REQUIRE a.id IS UNIQUE");
            session.run("CREATE CONSTRAINT loi_id_unique IF NOT EXISTS FOR (l:LOI) REQUIRE l.id IS UNIQUE");

            // Index vectoriel Neo4j 5.x
            session.run("""
                        CREATE VECTOR INDEX article_embeddings IF NOT EXISTS
                        FOR (a:ARTICLE) ON (a.embedding)
                        OPTIONS { indexConfig: { `vector.similarity_function`: 'cosine', `vector.dimensions`: 384 } }
                    """);

            logger.info("Contraintes et index créés");
        } catch (Neo4jException e) {
            logger.error("Erreur contraintes/index: {}", e.getMessage());
        }
    }

    /** Ingestion d'une loi + ses articles et embeddings */
    public String ingestLaw(String lawData, String vectorData) {
        JSONObject lawJson = new JSONObject(lawData);
        JSONObject vectorJson = new JSONObject(vectorData);

        JSONObject metadata = lawJson.getJSONObject("metadata");
        String lawNumber = metadata.getString("lawNumber");
        String lawDate = metadata.optString("lawDate", "");
        String lawObject = metadata.optString("lawObject", "");
        String source = metadata.optString("source", "");

        if (ingestedLaws.contains(lawNumber))
            return lawNumber;
        ingestedLaws.add(lawNumber);

        // MERGE loi
        session.run("""
                    MERGE (loi:LOI {id: $lawNumber})
                    SET loi.titre = $lawObject,
                        loi.code = $lawNumber,
                        loi.date_promulgation = $lawDate,
                        loi.source = $source,
                        loi.statut = 'En vigueur'
                """, Map.of(
                "lawNumber", lawNumber,
                "lawObject", lawObject,
                "lawDate", lawDate,
                "source", source));

        createdLaws++;

        JSONArray articles = lawJson.getJSONArray("articles");
        JSONArray vectorArticles = vectorJson.optJSONArray("articles");

        if (dropCreate) {
            // Compter les articles à supprimer
            var countResult = session.run(
                    "MATCH (a:ARTICLE)-[:APPARTIENT_A]->(l:LOI {id: $lawNumber}) RETURN count(a) as count",
                    Map.of("lawNumber", lawNumber));
            int articlesToDelete = countResult.single().get("count").asInt();
            deletedArticles += articlesToDelete;

            session.run("MATCH (a:ARTICLE)-[:APPARTIENT_A]->(l:LOI {id: $lawNumber}) DELETE a",
                    Map.of("lawNumber", lawNumber));
        }

        for (int i = 0; i < articles.length(); i++) {
            JSONObject article = articles.getJSONObject(i);
            String articleIndex = article.getString("index");
            String articleContent = article.optString("content", "");
            String articleId = lawNumber + "_ART_" + articleIndex;

            // Embedding safe
            List<Double> embedding = new ArrayList<>();
            if (vectorArticles != null) {
                for (int j = 0; j < vectorArticles.length(); j++) {
                    JSONObject vectorArticle = vectorArticles.getJSONObject(j);
                    if (vectorArticle.getString("index").equals(articleIndex)) {
                        JSONArray vec = vectorArticle.getJSONArray("vector");
                        for (int k = 0; k < vec.length(); k++)
                            embedding.add(vec.getDouble(k));
                        break;
                    }
                }
            }

            if (embedding.isEmpty()) {
                logger.warn("Article {} sans embedding - ignoré", articleIndex);
                continue;
            }

            Map<String, Object> params = Map.of(
                    "articleId", articleId,
                    "numeroArticle", "Article " + articleIndex,
                    "titreLoi", lawObject,
                    "contenu", articleContent,
                    "embedding", embedding,
                    "lawNumber", lawNumber,
                    "metadata", String.format("""
                                {"lawNumber":"%s","lawDate":"%s","source":"%s"}
                            """, lawNumber, lawDate, source));

            session.run("""
                        MERGE (a:ARTICLE {id:$articleId})
                        SET a.numero_article=$numeroArticle,
                            a.titre_loi=$titreLoi,
                            a.contenu=$contenu,
                            a.embedding=$embedding,
                            a.metadata=$metadata
                        WITH a
                        MATCH (l:LOI {id:$lawNumber})
                        MERGE (a)-[:APPARTIENT_A]->(l)
                    """, params);

            createdArticles++;
        }

        return lawNumber;
    }

    /** Ingestion de tous les fichiers */
    public void ingestAll() throws IOException {
        ingestedLaws.clear();
        // Réinitialiser les compteurs
        createdLaws = 0;
        createdArticles = 0;
        deletedArticles = 0;
        deletedNodes = 0;
        if (dropCreate)
            clearDatabase();
        loadData();
        logSummary();
    }

    private void logSummary() {
        logger.info(
                "Résumé de l'ingestion : Lois créées: {}, Articles créés: {}, Articles supprimés: {}, Nœuds supprimés: {}",
                createdLaws, createdArticles, deletedArticles, deletedNodes);
    }

    private void loadData() throws IOException {
        Path articleDirPath = Paths.get(articleDir);
        Path vectorDirPath = Paths.get(vectorDir);

        if (!Files.exists(articleDirPath))
            return;

        try (Stream<Path> files = Files.list(articleDirPath).filter(p -> p.toString().endsWith(".json"))) {
            for (Path articlePath : files.toList()) {
                String vectorFileName = articlePath.getFileName().toString().replace(".json", "_vectors.json");
                Path vectorPath = vectorDirPath.resolve(vectorFileName);

                String lawData = Files.readString(articlePath);
                String vectorData = Files.exists(vectorPath) ? Files.readString(vectorPath) : "{}";

                JSONObject metadata = new JSONObject(lawData).getJSONObject("metadata");
                String lawNumber = metadata.getString("lawNumber");
                if (ingestedLaws.contains(lawNumber))
                    continue;

                ingestLaw(lawData, vectorData);
            }
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    public static void main(String[] args) throws IOException {
        try (Neo4jIngestService service = new Neo4jIngestService()) {
            service.connect();
            service.createConstraintsAndIndexes();
            service.ingestAll();
        }
    }
}
