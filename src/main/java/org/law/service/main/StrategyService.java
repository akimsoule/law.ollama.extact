package org.law.service.main;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

public class StrategyService {

    public String getFullContent(File pdfFile) {
        String fullText = "";

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            // --- STRATÉGIE B : OCR en premier ---
            System.out.println(">>> STRATÉGIE A : OCR Haute Précision (Binarisation Leptonica)");
            OcrService ocrService = new OcrService();
            fullText = ocrService.runEnhancedOcr(document);

            // Si le texte extrait est vide ou très court, essayer l'extraction native
            if (fullText.trim().isEmpty()) {
                System.out.println(">>> INVERSION : Texte vide détecté, tentative avec Extraction Native");
                System.out.println(">>> STRATÉGIE B : Extraction Native + Template");
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
