package org.law.service.process;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.law.utils.BoolString;
import org.law.utils.ExtractString;
import org.law.utils.TransString;

public class ProcessLineImpl implements BoolString, ExtractString, TransString {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessLineImpl.class);


    private static final String END_BODY_COTONOU = "Fait à Cotonou";
    private static final String END_BODY_PORTO_NOVO = "Fait à Porto-Novo";
    private final ArticleProcessor articleProcessor = new ArticleProcessor();

    public String preProcessOcrLines(String ocr) {
        if (ocr == null)
            return "";
        StringBuilder result = new StringBuilder();

        boolean inBody = false;

        LOGGER.info("--- Début du traitement OCR ---");

        String[] lines = ocr.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty())
                continue;

            line = correctionWordInLine(line);

            if (!inBody && containsIgnoreCase(line, "promulgue")) {
                inBody = true;
                LOGGER.info("[INFO] Zone 'Promulgue' détectée. Début de l'analyse des articles.");
            }

            if (inBody) {
                line = articleProcessor.process(line);
            }

            if (inBody && isEndOfBody(line)) {
                inBody = false;
                String normalized = normalizeEndOfBody(line);
                if (normalized != null) {
                    line = normalized;
                }
                LOGGER.info("[INFO] Zone de signature détectée. Fin de l'analyse des articles.");
            }

            if (inBody && isTitle(line)) {
                continue;
            }

            result.append(line).append("\n");
        }

        LOGGER.info("--- Fin du traitement ---");
        return result.toString().trim();
    }

    private boolean isEndOfBody(String line) {
        return validateWithLevenshtein(line, END_BODY_COTONOU, 20, 3) ||
                validateWithLevenshtein(line, END_BODY_PORTO_NOVO, 20, 3);
    }

    private String normalizeEndOfBody(String line) {
        if (validateWithLevenshtein(line, END_BODY_COTONOU, 20, 3)) {
            return END_BODY_COTONOU + line.substring(END_BODY_COTONOU.length());
        } else if (validateWithLevenshtein(line, END_BODY_PORTO_NOVO, 20, 3)) {
            return END_BODY_PORTO_NOVO + line.substring(END_BODY_PORTO_NOVO.length());
        }
        return null;
    }

}
