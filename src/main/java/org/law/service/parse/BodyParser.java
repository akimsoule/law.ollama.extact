package org.law.service.parse;

import org.law.service.process.LangToolService;
import org.law.utils.BoolString;
import org.law.utils.TemplateReader;
import org.law.utils.TransString;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

public class BodyParser implements BoolString, TransString, TemplateReader {

    /**
     * Nettoie et corrige le corps du texte.
     * Le body est supposé déjà synchronisé par preProcessOcrLines.
     */
    public String cleanUpBody(String body) throws IOException {
        if (body == null || body.isBlank())
            return "";

        long startTime = System.currentTimeMillis();
        System.out.println("[STEP] Début du nettoyage et correction grammaticale...");

        // 1. Extraction simple (le gros du travail de numérotation est déjà fait en
        // amont)
        List<String> articles = extractArticles(body);
        List<String> correctedArticles = new LinkedList<>();

        // 2. Correction par article (pour ne pas dépasser les limites de taille de
        // LangTool)
        for (int i = 0; i < articles.size(); i++) {
            String articleText = articles.get(i);

            long startCorr = System.currentTimeMillis();
            // Correction grammaticale (LangToolService)
            articleText = LangToolService.getInstance().getCorrectedText(articleText);
            long endCorr = System.currentTimeMillis();

            System.out.println(
                    "Article " + (i + 1) + "/" + articles.size() + " corrigé en " + (endCorr - startCorr) + " ms");
            correctedArticles.add(articleText);
        }

        System.out.println("[INFO] Temps total de traitement : " + (System.currentTimeMillis() - startTime) + " ms");

        // Retourne les articles séparés par deux sauts de ligne pour votre RAG
        return String.join("\n\n", correctedArticles);
    }

    /**
     * Découpe le texte en blocs d'articles.
     * Se base sur le format "Article X : " déjà posé par preProcessOcrLines.
     */
    public List<String> extractArticles(String body) {
        List<String> articles = new LinkedList<>();
        StringBuilder currentArticle = new StringBuilder();

        String[] lines = body.split("\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty())
                continue;

            // stratLookLikeArticle détectera "Article X : "
            if (stratLookLikeArticle(trimmedLine)) {
                if (!currentArticle.isEmpty()) {
                    articles.add(toSingleLine(currentArticle.toString().trim()));
                }
                currentArticle = new StringBuilder(trimmedLine);
            } else {
                if (!currentArticle.isEmpty()) {
                    // On garde la structure de paragraphe à l'intérieur de l'article
                    currentArticle.append(" ").append(trimmedLine);
                }
            }
        }

        // Ajouter le dernier article
        if (!currentArticle.isEmpty()) {
            articles.add(toSingleLine(currentArticle.toString().trim()));
        }

        return articles;
    }
}
