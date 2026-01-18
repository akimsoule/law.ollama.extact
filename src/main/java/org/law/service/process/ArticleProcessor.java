package org.law.service.process;

import org.law.service.parse.Constant;
import org.law.utils.BoolString;
import org.law.utils.ExtractString;
import org.law.utils.TransString;

import java.util.Arrays;

/**
 * Classe responsable du traitement d'une ligne d'article.
 * Encapsule la logique complexe de normalisation et de formatage des articles.
 */
public class ArticleProcessor implements BoolString, ExtractString, TransString {

    private static final String ARTICLE_WORD = "Article";

    /**
     * Traite une ligne qui ressemble à un article.
     * Orchestre toutes les étapes de normalisation.
     */
    public String process(String line) {
        if (!stratLookLikeArticle(line)) {
            return line;
        }

        String articleLabel = "";
        String number = "";
        String delimiter = "";
        String content = "";

        // Étape 1: Normaliser le label "Article"
        String normalizedLine = normalizeArticleWord(line);
        articleLabel = ARTICLE_WORD;

        // Étape 2: Trouver le délimiteur
        String delimiterFound = findDelimiter(normalizedLine);
        delimiter = ":"; // On normalise toujours vers ":"

        // Étape 3: Nettoyer les artefacts
        String cleanedLine = applyDelimiterReplacement(normalizedLine, delimiterFound);
        cleanedLine = cleanArtifacts(cleanedLine);

        // Étape 4: Extraire le numéro
        number = extractNumber(cleanedLine);

        // Étape 5: Appliquer le formatage du numéro et extraire le contenu
        content = applyNumberFormattingAndExtractContent(cleanedLine, number, delimiterFound);

        // Étape 6: Normaliser les délimiteurs finaux
        content = normalizeDelimitersOnPrefix(content, 25);
        content = normalizeColonSpacingOnPrefix(content, 25);

        // Nettoyer les ":" en début de contenu
        content = content.replaceAll("^:+", "").trim();

        // Reconstruction finale
        return articleLabel + " " + number + " " + delimiter + " " + content;
    }

    private String normalizeArticleWord(String line) {
        if (validateWithLevenshtein(line, ARTICLE_WORD, 10, 3)) {
            return ARTICLE_WORD + line.substring(ARTICLE_WORD.length());
        }
        return line;
    }

    private String findDelimiter(String line) {
        return Arrays.stream(Constant.DELIMITERS_ALLOWED)
                .filter(delimiter -> containsInFirstChars(line, delimiter, 25))
                .findFirst()
                .orElse("");
    }

    private String applyDelimiterReplacement(String line, String delimiterFound) {
        if (!delimiterFound.isEmpty()) {
            return line.replace(delimiterFound, ":");
        }
        return line;
    }

    private String cleanArtifacts(String line) {
        String nFirstChar = line.substring(0, Math.min(25, line.length()));
        String result = line;

        if (nFirstChar.contains("::")) {
            String nFirstCharCorr = nFirstChar.replace("::", ":");
            result = result.replace(nFirstChar, nFirstCharCorr);
        }
        if (nFirstChar.contains(",")) {
            String nFirstCharCorr = nFirstChar.replace(",", "");
            result = result.replace(nFirstChar, nFirstCharCorr);
        }

        return result;
    }

    private String extractNumber(String line) {
        return getNumberInStartV2(line, 24).orElse("");
    }

    private String applyNumberFormattingAndExtractContent(String line, String number, String delimiterFound) {
        String result = line;

        if (delimiterFound.isEmpty() && !number.isEmpty()) {
            String[] parts = result.split(number, 3);
            if (parts.length >= 2) {
                result = parts[0] + number + " : " + parts[1];
            }
        }

        if (!number.isEmpty()) {
            String numberNormalized = number.replace(" ", "_");
            String[] parts = result.split(":", 2);
            if (parts.length >= 2) {
                result = "";
                for (int i = 0; i < parts.length; i++) {
                    if (i == 0) {
                        result += parts[i].trim().replace(number, numberNormalized) + " : ";
                    } else {
                        result += parts[i];
                    }
                }
            } else {
                // pas de :
                result = replaceInNFirstChar(result, number, numberNormalized, 25);
            }
        }

        // Extraire le contenu après "Article numéro :"
        int colonIndex = result.indexOf(":");
        if (colonIndex != -1) {
            return result.substring(colonIndex + 1).trim();
        }
        return result;
    }
}