package org.law.service.process;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.law.model.LawSection;
import org.law.model.Signataire;
import org.law.service.parse.BodyParser;
import org.law.service.parse.FooterParser;
import org.law.service.parse.HeaderParser;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Service pour construire l'objet JSON à partir des données extraites.
 */
public class JsonService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonService.class);


    /**
     * Construit l'objet JSON à partir d'une LawSection.
     *
     * @param lawSection la section de loi extraite
     * @return l'objet JSON
     */
    public JSONObject buildJson(LawSection lawSection) {
        JSONObject jsonObject = new JSONObject();

        // Ajouter les articles
        JSONArray articlesArray = extractArticlesToJson(lawSection);
        jsonObject.put("articles", articlesArray);

        // Ajouter les métadonnées
        JSONObject metadata = new JSONObject();
        extractMetadataToJson(lawSection, metadata);
        extractSignatoriesToJson(lawSection, metadata);
        jsonObject.put("metadata", metadata);

        return jsonObject;
    }

    private JSONArray extractArticlesToJson(LawSection lawSection) {
        JSONArray articlesArray = new JSONArray();
        BodyParser bodyTrans = new BodyParser();
        boolean articleModified = lawSection.getHeader().contains("modifiant");
        // 1. Extraction simple des blocs (déjà synchronisés en amont)
        List<String> articles = bodyTrans.extractArticles(lawSection.getBody());
        int articleNumberSupposedToFind = 1;
        String currentArticle = "";
        String currentArticleNumber = "";
        if (articleModified) {
            LOGGER.info("Le nombre d'articles est " + articles.size());
        }

        for (String article : articles) {
            // 2. Récupérer le numéro qui est au début : "Article X : "
            // int num = parseNumberFromStandardizedArticle(article);
            String articleNumber = parseNumberFromStandardizedArticle(article);
            if (articleModified) {
                int articleNumberInt = getArticleNumber(articleNumber);
                if (articleNumberInt == articleNumberSupposedToFind) {
                    // Nouvel article trouvé
                    // Sauvegarder l'article précédent s'il existe
                    if (!currentArticle.isEmpty() && !currentArticleNumber.isEmpty()) {
                        JSONObject artObj = new JSONObject();
                        artObj.put("index", currentArticleNumber);
                        artObj.put("content", currentArticle);
                        articlesArray.put(artObj);
                    }
                    // Démarrer un nouvel article
                    currentArticleNumber = articleNumber;
                    currentArticle = article;
                    articleNumberSupposedToFind += 1;
                } else {
                    // C'est la continuation du même article
                    currentArticle += "\n" + article;
                }
            } else {
                JSONObject artObj = new JSONObject();
                artObj.put("index", articleNumber);
                artObj.put("content", article);
                articlesArray.put(artObj);
            }
        }

        // Ajouter le dernier article accumulé
        if (articleModified && !currentArticle.isEmpty() && !currentArticleNumber.isEmpty()) {
            JSONObject artObj = new JSONObject();
            artObj.put("index", currentArticleNumber);
            artObj.put("content", currentArticle);
            articlesArray.put(artObj);
        }

        return articlesArray;
    }

    private int getArticleNumber(String articleNumber) {
        int articleNumberResult = 999999;
        if (articleNumber.contains("pre") ||
                articleNumber.contains("er")) {
            return 1;
        } else {
            try {
                articleNumberResult = Integer.parseInt(articleNumber);
            } catch (NumberFormatException ignored) {
            }
        }
        return articleNumberResult;
    }

    /**
     * Extrait le numéro d'un article normalisé par le pré-processeur.
     * Format attendu : "Article 10 : contenu..."
     */
    private String parseNumberFromStandardizedArticle(String article) {
        try {
            if (article == null || !article.toLowerCase().startsWith("article")) {
                return "UNKNOWN";
            }

            // On isole la partie entre "Article " et le premier ":"
            String head = article.split(":", 2)[0]; // "Article 10 "
            String numStr = head.replaceAll("(?i)article", "").trim(); // "10"

            return numStr;
        } catch (Exception e) {
            LOGGER.error("[ERROR] Impossible d'extraire l'index pour l'article : "
                    + article.substring(0, Math.min(20, article.length())));
            return "UNKNOWN";
        }
    }

    private void extractMetadataToJson(LawSection lawSection, JSONObject metadata) {
        HeaderParser headerTrans = new HeaderParser();
        FooterParser footerTrans = new FooterParser();
        // Extraire lawNumber, lawObject, lawDate depuis le header
        String lawNumber = headerTrans.extractLawNumber(lawSection.getHeader());
        String lawObject = headerTrans.extractLawObject(lawSection.getHeader());
        String lawDate = "";
        try {
            lawDate = headerTrans.extractLawDate(lawSection);
        } catch (DateTimeParseException dateTimeParseException) {
            LOGGER.info("Échoué => 1ère Tentative de récupération de la date dans le header => Échoué");
            try {
                lawDate = footerTrans.extractLawDate(lawSection);
            } catch (DateTimeParseException ignored) {
                LOGGER.info("Échoué => 2ème Tentative de récupération de la date dans le footer => Échoué");

            }
        }

        if (lawDate.isEmpty()) {
            LOGGER.info("LawDate is empty ...");
        }

        metadata.put("lawNumber", lawNumber);
        metadata.put("lawObject", lawObject);
        metadata.put("lawDate", lawDate);

        // Générer et valider l'URL source
        String source = generateAndValidateSource(lawSection, lawNumber);
        if (!source.isEmpty()) {
            metadata.put("source", source);
        }
    }

    /**
     * Génère et valide l'URL source à partir du numéro de loi.
     * Format attendu : loi-XXXX-XX → https://sgg.gouv.bj/doc/loi-XXXX-XX/
     */
    private String generateAndValidateSource(LawSection lawSection, String lawNumber) {
        if (lawNumber == null || lawNumber.isEmpty()) {
            LOGGER.info("Warning: lawNumber is empty, cannot generate source URL");
            return "";
        }

        String result;

        try {
            // Valider que lawNumber match le pattern "loi-XXXX-XX"
            // Utiliser regex pour split avec ignore case sur "DU"
            result = lawSection.getType() + "-" + lawNumber.split("(?i)DU")[0]
                    .trim()
                    .replace(" ", "");
            lawSection.setBaseName(result);
        } catch (NullPointerException | IndexOutOfBoundsException e) {
            LOGGER.info("Warning: Failed to process lawNumber: " + e.getMessage());
            result = "";
        }

        String source = "https://sgg.gouv.bj/doc/" + result + "/";

        // Valider l'URL (vérification basique)
        if (isValidUrl(source)) {
            LOGGER.info("Generated source URL: " + source);
            return source;
        } else {
            LOGGER.info("Warning: Generated source URL is invalid: " + source);
            return "";
        }
    }

    /**
     * Valide qu'une URL est bien formée.
     */
    private boolean isValidUrl(String urlString) {
        try {
            new java.net.URL(urlString);
            return true;
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    private void extractSignatoriesToJson(LawSection lawSection, JSONObject metadata) {
        JSONArray signatoriesArray = new JSONArray();
        FooterParser footerTrans = new FooterParser();
        Set<Signataire> signataires = footerTrans.extractSignataires(lawSection.getFooter());

        if (signataires.isEmpty()) {
            signataires = footerTrans.extractSignataires(lawSection.getBody());
        }

        if (signataires.isEmpty()) {
            signataires = footerTrans.extractSignataires(lawSection.getFullText());
        }

        for (Signataire sig : signataires) {
            JSONObject sigObj = new JSONObject();
            sigObj.put("name", sig.getName());
            sigObj.put("role", sig.getRole());
            signatoriesArray.put(sigObj);
        }

        metadata.put("signatories", signatoriesArray);
    }
}