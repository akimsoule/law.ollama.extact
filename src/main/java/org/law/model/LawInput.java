package org.law.model;

import java.io.File;

/**
 * Classe représentant les données d'entrée pour le traitement d'une loi.
 * Contient le fichier PDF, le contenu OCR et le nom de base du fichier.
 */
public class LawInput {
    public final File pdfFile;
    public final String rawOcr;
    public final String pdfBaseName;

    public LawInput(File pdfFile, String rawOcr, String pdfBaseName) {
        this.pdfFile = pdfFile;
        this.rawOcr = rawOcr;
        this.pdfBaseName = pdfBaseName;
    }

    @Override
    public String toString() {
        return "LawInput{" +
                "pdfFile=" + pdfFile.getName() +
                ", pdfBaseName='" + pdfBaseName + '\'' +
                ", rawOcrLength=" + (rawOcr != null ? rawOcr.length() : 0) +
                '}';
    }
}
