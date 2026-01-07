package org.law.service.main;

import org.json.JSONArray;
import org.json.JSONObject;
import org.law.model.LawSection;
import org.law.model.Signataire;
import org.law.service.section.BodyTransImpl;
import org.law.service.section.FooterTransImpl;
import org.law.service.section.HeaderTransImpl;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Service pour construire l'objet JSON à partir des données extraites.
 */
public class JsonService {

    /**
     * Construit l'objet JSON à partir d'une LawSection.
     *
     * @param lawSection la section de loi extraite
     * @return l'objet JSON
     */
    public JSONObject buildJson(LawSection lawSection) {
        JSONObject jsonObject = new JSONObject();

        // Ajouter les articles
        JSONArray articlesArray = extractArticlesToJson(lawSection.getBody());
        jsonObject.put("articles", articlesArray);

        // Ajouter les métadonnées
        JSONObject metadata = new JSONObject();
        extractMetadataToJson(lawSection, metadata);
        extractSignatoriesToJson(lawSection, metadata);
        jsonObject.put("metadata", metadata);

        return jsonObject;
    }

    private JSONArray extractArticlesToJson(String body) {
        JSONArray articlesArray = new JSONArray();
        BodyTransImpl bodyTrans = new BodyTransImpl();

        // 1. Extraction simple des blocs (déjà synchronisés en amont)
        List<String> articles = bodyTrans.extractArticles(body);

        for (String article : articles) {
            // 2. Récupérer le numéro qui est au début : "Article X : "
            // int num = parseNumberFromStandardizedArticle(article);
            String articleNumber = parseNumberFromStandardizedArticle(article);

            JSONObject artObj = new JSONObject();
            artObj.put("index", articleNumber);
            artObj.put("content", article);
            articlesArray.put(artObj);
        }

        return articlesArray;
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
            System.err.println("[ERROR] Impossible d'extraire l'index pour l'article : "
                    + article.substring(0, Math.min(20, article.length())));
            return "UNKNOWN";
        }
    }

    private void extractMetadataToJson(LawSection lawSection, JSONObject metadata) {
        HeaderTransImpl headerTrans = new HeaderTransImpl();
        FooterTransImpl footerTrans = new FooterTransImpl();
        // Extraire lawNumber, lawObject, lawDate depuis le header
        String lawNumber = headerTrans.extractLawNumber(lawSection.getHeader());
        String lawObject = headerTrans.extractLawObject(lawSection.getHeader());
        String lawDate = "";
        try {
            lawDate = headerTrans.extractLawDate(lawSection);
        } catch (DateTimeParseException dateTimeParseException) {
            System.out.println("Échoué => 1ère Tentative de récupération de la date dans le header => Échoué");
            try {
                lawDate = footerTrans.extractLawDate(lawSection);
            } catch (DateTimeParseException ignored) {
                System.out.println("Échoué => 2ème Tentative de récupération de la date dans le footer => Échoué");

            }
        }

        if (lawDate.isEmpty()) {
            System.out.println("LawDate is empty ...");
        }

        metadata.put("lawNumber", lawNumber);
        metadata.put("lawObject", lawObject);
        metadata.put("lawDate", lawDate);
    }

    private void extractSignatoriesToJson(LawSection lawSection, JSONObject metadata) {
        JSONArray signatoriesArray = new JSONArray();
        FooterTransImpl footerTrans = new FooterTransImpl();
        Set<Signataire> signataires = footerTrans.extractSignataires(lawSection.getFooter());

        for (Signataire sig : signataires) {
            JSONObject sigObj = new JSONObject();
            sigObj.put("name", sig.getName());
            sigObj.put("role", sig.getRole());
            signatoriesArray.put(sigObj);
        }

        metadata.put("signatories", signatoriesArray);
    }
}