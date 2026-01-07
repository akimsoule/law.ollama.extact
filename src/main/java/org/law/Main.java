package org.law;

import org.json.JSONObject;
import org.law.model.LawSection;
import org.law.model.Stat;
import org.law.service.main.*;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class Main {

    public static void main(String[] args) {
        Path loiDir = Path.of("src/main/resources/loi");
        Path errorsFile = Path.of("errors.log");

        try (PrintWriter errorWriter = new PrintWriter(Files.newBufferedWriter(errorsFile));
                Stream<Path> pdfFiles = Files.list(loiDir)
                        .filter(p -> p.toString().endsWith(".pdf"))) {

            pdfFiles.forEach(pdfPath -> {
                File pdfFile = pdfPath.toFile();
                String pdfName = pdfFile.getName();
                String year = pdfName.split("[-|.]")[1];
                int yearInt = Integer.parseInt(year);
                if (yearInt < 1970) {
                    return;
                }
                errorWriter.println("Traitement de : " + pdfName);
                errorWriter.flush();

                try {
                    long startTime = System.currentTimeMillis();
                    System.out.println("Début du traitement de " + pdfName + "...");

                    // Gestion de l'OCR avec idempotence
                    OcrService ocrService = new OcrService();
                    long startExtract = System.currentTimeMillis();
                    String fullText = ocrService.getOcrText(pdfFile);
                    long endExtract = System.currentTimeMillis();
                    System.out.println("Temps pour extraction du texte : " + (endExtract - startExtract) + " ms");

                    ProcessLineImpl processLine = new ProcessLineImpl();
                    long startProcess = System.currentTimeMillis();
                    String fullTextCorr = processLine.preProcessOcrLines(fullText);
                    long endProcess = System.currentTimeMillis();
                    System.out.println("Temps pour prétraitement OCR : " + (endProcess - startProcess) + " ms");

                    ExtractorService extractorService = new ExtractorService(fullTextCorr);
                    long startExtractLaw = System.currentTimeMillis();
                    LawSection lawSection = extractorService.extractLawSection();
                    lawSection.setYear(year);
                    long endExtractLaw = System.currentTimeMillis();
                    System.out.println(
                            "Temps pour extraction des sections : " + (endExtractLaw - startExtractLaw) + " ms");

                    // Construire le JSON
                    JsonService jsonService = new JsonService();
                    long startJson = System.currentTimeMillis();
                    JSONObject jsonObject = jsonService.buildJson(lawSection);
                    String jsonOutput = jsonObject.toString(4);
                    long endJson = System.currentTimeMillis();
                    System.out.println("Temps pour construction du JSON : " + (endJson - startJson) + " ms");

                    // Exporter le JSON dans le répertoire src/main/resources/article
                    try {
                        String jsonName = pdfName.replace(".pdf", ".json");
                        Path articleDir = Path.of("src/main/resources/article");
                        Files.createDirectories(articleDir);
                        Path jsonFile = articleDir.resolve(jsonName);
                        Files.writeString(jsonFile, jsonOutput);
                        System.out.println("JSON exporté dans : " + jsonFile.toAbsolutePath());
                    } catch (IOException e) {
                        errorWriter.println("Erreur lors de l'export du JSON pour " + pdfName + " : " + e.getMessage());
                    }

                    // Validation QA
                    QAService qaService = new QAService();
                    List<String> qaErrors = qaService.validateJson(jsonObject);
                    if (!qaErrors.isEmpty()) {
                        errorWriter.println("Erreurs QA pour " + pdfName + " :");
                        for (String error : qaErrors) {
                            errorWriter.println("- " + error);
                        }
                    }

                    long endTime = System.currentTimeMillis();
                    System.out.println(
                            "Temps total de traitement pour " + pdfName + " : " + (endTime - startTime) + " ms");
                    errorWriter.println("Traitement réussi pour " + pdfName);

                } catch (Exception e) {
                    errorWriter.println("Erreur lors du traitement de " + pdfName + " : " + e.getMessage());
                    e.printStackTrace(errorWriter);
                }
                errorWriter.println("---");
                errorWriter.flush();
            });

        } catch (IOException e) {
            System.err.println("Erreur lors de l'accès au répertoire ou au fichier d'erreurs : " + e.getMessage());
        }

        try {
            StringBuilder stats = new StringBuilder();
            for (Map.Entry<String, Integer> entry : Stat.getInstance().getArticleCounts().entrySet()) {
                stats.append(entry.getKey()).append("||").append(entry.getValue()).append("\n");
            }
            Files.write(Paths.get("article_debug.txt"), stats.toString().getBytes(), StandardOpenOption.CREATE);
        } catch (IOException e) {
            System.err.println("Erreur lors de l'écriture des statistiques : " + e.getMessage());
        }
    }

}
