package org.law.service.extract;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilitaire de pré-traitement OCR pour les lois anciennes.
 * Centralise la logique partagée entre IntelliService, IntelliBatchService et DeepSeekBatchService.
 */
public final class OcrPreprocessor {

    private OcrPreprocessor() {}

    /**
     * Applique des corrections OCR adaptées à l'âge de la loi.
     *
     * @param rawOcr      texte OCR brut
     * @param pdfBaseName nom du fichier (ex: "loi-1965-12")
     * @return texte corrigé
     */
    public static String preprocess(String rawOcr, String pdfBaseName) {
        if (rawOcr == null || rawOcr.isBlank()) return rawOcr;

        Matcher yearMatcher = Pattern.compile("loi-(\\d{4})-").matcher(pdfBaseName);
        if (!yearMatcher.find()) return rawOcr;

        int year = Integer.parseInt(yearMatcher.group(1));

        if (year <= 1970) {
            return rawOcr
                // Corrections de caractères fréquemment mal reconnus
                .replaceAll("ü_", "u")
                .replaceAll("\\?RESIDÈNCD", "PRÉSIDENT")
                .replaceAll("§ue", "que")
                .replaceAll("RXPUBIÏQUE", "RÉPUBLIQUE")
                .replaceAll("I,A", "LA")
                .replaceAll("lnt", "int")
                .replaceAll("\\bdc\\b", "de")
                .replaceAll("\\blcs\\b", "les")
                .replaceAll("\\bdcs\\b", "des")
                // Corrections de ponctuation
                .replaceAll("\\s*\\.\\s*", ". ")
                .replaceAll("\\s*,\\s*", ", ")
                // Normalisation des espaces
                .replaceAll("\\s+", " ")
                // Corrections contextuelles lettre → chiffre
                .replaceAll("\\bl(\\d)", "1$1")
                .replaceAll("\\bI(\\d)", "1$1")
                .trim();
        }

        if (year < 1990) {
            return rawOcr
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\w\\s\\-\\.,;:'\"()\\[\\]/]", "")
                .trim();
        }

        return rawOcr;
    }
}
