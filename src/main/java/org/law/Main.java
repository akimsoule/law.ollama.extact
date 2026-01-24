package org.law;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.law.model.LawNode;
import org.law.model.LawSection;
import org.law.model.Stat;
import org.law.service.extract.*;
import org.law.service.parse.HeaderParser;
import org.law.service.parse.LawNodeParser;
import org.law.service.process.*;
import org.law.service.embed.*;
import org.law.service.ingest.Neo4jIngestService;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {

    public static void main(String[] args) {
        Path loiDir = Path.of("src/main/resources/data/loi");
        Path errorsFile = Path.of("errors.log");

        String pdf = "";

        try (PrintWriter errorWriter = new PrintWriter(Files.newBufferedWriter(errorsFile));
             Stream<Path> pdfFiles = Files.list(loiDir)
                     .filter(p -> p.toString().endsWith(pdf + ".pdf"))) {

            Set<Path> paths = pdfFiles.collect(Collectors.toSet());

            for (Path path : paths) {
                File pdfFile = path.toFile();
                String pdfName = pdfFile.getName();
                String[] parts = pdfName.split("[-|.]");
                String year = parts[1];
                int yearInt = Integer.parseInt(year);
                String type = parts[0];
                if (yearInt < 2025) {
                    continue;
                }
                String baseName = parts[1] + "-" + parts[2];
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
                    lawSection.setType(type);
                    lawSection.setBaseName(baseName);
                    long endExtractLaw = System.currentTimeMillis();
                    System.out.println(
                            "Temps pour extraction des sections : " + (endExtractLaw - startExtractLaw) + " ms");

                    // Construire le JSON
//                    JSONObject jsonObject = buildJSONObject(lawSection, errorWriter, pdfName);

                    // Build Node
                    LawNode root = buildNodeTree(lawSection, errorWriter, pdfName);

                    // Vectorisation des json contenus dans article/
                    VectorService vectorService = new VectorService();
                    long startVector = System.currentTimeMillis();
                    vectorService.vectorizeAllArticles();
                    long endVector = System.currentTimeMillis();
                    System.out.println("Temps pour vectorisation : " + (endVector - startVector) + " ms");

                    // Validation QA
//                    QAService qaService = new QAService();
//                    List<String> qaErrors = qaService.validateJson(jsonObject);
//                    if (!qaErrors.isEmpty()) {
//                        errorWriter.println("Erreurs QA pour " + pdfName + " :");
//                        for (String error : qaErrors) {
//                            errorWriter.println("- " + error);
//                        }
//                    }

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
            }

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

    @NotNull
    private static JSONObject buildJSONObject(LawSection lawSection, PrintWriter errorWriter, String pdfName) {
        JsonService jsonService = new JsonService();
        long startJson = System.currentTimeMillis();
        JSONObject jsonObject = jsonService.buildJson(lawSection);
        String jsonOutput = jsonObject.toString(4);
        long endJson = System.currentTimeMillis();
        System.out.println("Temps pour construction du JSON : " + (endJson - startJson) + " ms");

        // Exporter le JSON dans le répertoire src/main/resources/data/article
        try {
            String jsonName = lawSection.getBaseName() + ".json";
            Path articleDir = Path.of("src/main/resources/data/article");
            Files.createDirectories(articleDir);
            Path jsonFile = articleDir.resolve(jsonName);
            Files.writeString(jsonFile, jsonOutput);
            System.out.println("JSON exporté dans : " + jsonFile.toAbsolutePath());

            // Ingestion idempotente du fichier JSON généré dans Neo4j
            Neo4jIngestService ingestService = new Neo4jIngestService();
            long startIngest = System.currentTimeMillis();
            try {
                ingestService.connect();
                ingestService.createConstraintsAndIndexes();
                // Ingestion via les fichiers de nœuds au lieu des articles
                // (ingestNodeFile gère maintenant la structure hiérarchique)
                ingestService.printStats();
                long endIngest = System.currentTimeMillis();
                System.out.println("Temps pour ingestion Neo4j : " + (endIngest - startIngest) + " ms");
                errorWriter.println("Ingestion Neo4j réussie pour " + pdfName);
            } catch (Exception e) {
                errorWriter.println(
                        "Erreur lors de l'ingestion Neo4j pour " + pdfName + " : " + e.getMessage());
                e.printStackTrace(errorWriter);
            } finally {
                ingestService.disconnect();
            }
        } catch (IOException e) {
            errorWriter.println("Erreur lors de l'export du JSON pour " + pdfName + " : " + e.getMessage());
        }
        return jsonObject;
    }

    @NotNull
    private static LawNode buildNodeTree(LawSection lawSection, PrintWriter errorWriter, String pdfName) {
        LawNodeParser lawNodeParser = new LawNodeParser();
        long startNode = System.currentTimeMillis();
        LawNode root = lawNodeParser.parse(lawSection);
        // Ajouter la source dans les métadonnées du nœud racine
        HeaderParser headerParser = new HeaderParser();
        String lawNumber = headerParser.extractLawNumber(lawSection.getHeader());
        String source = new JsonService().generateAndValidateSource(lawSection, lawNumber);
        if (source != null && !source.isEmpty()) {
            root.addMetadata("source", source);
        }
        
        long endNode = System.currentTimeMillis();
        System.out.println("Temps pour construction du node : " + (endNode - startNode) + " ms");
        System.out.println("Articles trouvés : " + root.countArticles());
        errorWriter.println("Construction du node réussie pour " + pdfName + " - Articles: " + root.countArticles());
        errorWriter.flush();
        
        // Exporter le node en JSON dans le répertoire src/main/resources/data/node
        try {
            String jsonName = lawSection.getBaseName() + ".json";
            Path nodeDir = Path.of("src/main/resources/data/node");
            Files.createDirectories(nodeDir);
            Path jsonFile = nodeDir.resolve(jsonName);
            
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), root);
            
            System.out.println("JSON exporté dans : " + jsonFile.toAbsolutePath());
            errorWriter.println("Export JSON réussi pour " + pdfName);
        } catch (IOException e) {
            errorWriter.println("Erreur lors de l'export du JSON pour " + pdfName + " : " + e.getMessage());
            e.printStackTrace(errorWriter);
        }
        
        return root;
    }

}
