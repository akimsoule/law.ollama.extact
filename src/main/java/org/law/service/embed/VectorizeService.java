package org.law.service.embed;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Service de vectorisation des articles juridiques.
 * Point d'entrée principal pour la vectorisation.
 */
public class VectorizeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VectorizeService.class);


    private static final String VECTOR_DIR = "src/main/resources/data/vector";
    private static final String ARTICLE_DIR = "src/main/resources/data/article";

    /**
     * Point d'entrée pour vectoriser/re-vectoriser les articles.
     * Supprime les vecteurs existants puis en crée de nouveaux.
     */
    public static void main(String[] args) {
        try {
            VectorService vectorService = new VectorService();

            LOGGER.info("Suppression des vecteurs existants...");
            vectorService.deleteAllVectors();
            LOGGER.info("Vecteurs supprimés avec succès.");

            LOGGER.info("Début de la vectorisation des articles...");
            long startTime = System.currentTimeMillis();
            vectorService.vectorizeAllArticles();
            long endTime = System.currentTimeMillis();

            LOGGER.info("Vectorisation terminée en " + (endTime - startTime) + " ms");
        } catch (IOException e) {
            LOGGER.error("Erreur lors de la vectorisation : " + e.getMessage());
            e.printStackTrace();
        }
    }
}
