package org.law.service.process;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.law.service.extract.OcrService;

import java.io.File;

public class StrategyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrategyService.class);


    public String getFullContent(File pdfFile) {
        String fullText = "";

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            // --- STRATÉGIE B : OCR en premier ---
            LOGGER.info(">>> STRATÉGIE A : OCR Haute Précision (Binarisation Leptonica)");
            OcrService ocrService = new OcrService();
            fullText = ocrService.runEnhancedOcr(document);

            // Si le texte extrait est vide ou très court, essayer l'extraction native
            if (fullText.trim().isEmpty()) {
                LOGGER.info(">>> INVERSION : Texte vide détecté, tentative avec Extraction Native");
                LOGGER.info(">>> STRATÉGIE B : Extraction Native + Template");
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setEndPage(document.getNumberOfPages());
                fullText = stripper.getText(document);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return fullText;

    }

}
