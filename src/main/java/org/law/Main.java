package org.law;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONObject;
import org.law.model.LawInput;
import org.law.service.embed.VectorService;
import org.law.service.extract.BatchService;
import org.law.service.extract.IntelliService;
import org.law.service.extract.OcrService;
import org.law.config.Config;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final Pattern LAW_FILE_PATTERN = Pattern.compile("^([^-]+)-(\\d{2}|\\d{4})-(\\d{2}|\\d{3})\\.pdf$",
            Pattern.CASE_INSENSITIVE);

    // Pivot adapte au corpus attendu (1960-2026).
    private static int normalizeYearForFiltering(int value) {
        // Les noms de fichiers dans votre corpus semblent avoir des années sur 2 ou 4
        // chiffres.
        if (value >= 60) {
            return 1900 + value;
        }
        return 2000 + value;
    }

    public static void main(String[] args) {
        LOGGER.info("Démarrage du traitement avec DeepSeek...");

        // Initialiser la configuration
        Config config = Config.getInstance();
        config.printConfiguration();

        Path loiDir = Path.of("src/main/resources/data/loi");
        Path errorsFile = Path.of("errors.log");
//        RunOptions runOptions = RunOptions.fromArgs(args);
        RunOptions runOptions = RunOptions.builder()
                .minYear(2025)
                .maxYear(2026)
                .refreshOcr(false)
                .useBatchDeepSeek(false)
                .build();
        int successCount = 0;
        int skippedCount = 0;
        List<Path> generatedJsonFiles = new ArrayList<>();

        try (PrintWriter errorWriter = new PrintWriter(Files.newBufferedWriter(errorsFile));
                Stream<Path> pdfFiles = Files.list(loiDir)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))) {

            List<Path> paths = pdfFiles
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());

            // Mode batch DeepSeek ou séquentiel
            if (runOptions.isUseBatchDeepSeek()) {
                LOGGER.info("[BATCH MODE] Utilisation du traitement batch DeepSeek");
                skippedCount = processBatchDeepSeek(paths, runOptions, errorWriter, generatedJsonFiles);
            } else {
                LOGGER.info("[SEQUENTIAL MODE] Utilisation du traitement séquentiel classique");
                skippedCount = processSequential(paths, runOptions, errorWriter, generatedJsonFiles);
            }

            successCount = generatedJsonFiles.size();

        } catch (IOException e) {
            LOGGER.error("Erreur lors de l'accès au répertoire ou au fichier d'erreurs : " + e.getMessage());
        }

        // Vectorisation et statistiques
        try {
            VectorService vectorService = new VectorService();
            vectorService.vectorizeArticles(generatedJsonFiles);
            LOGGER.info("\n=== STATISTIQUES ===");
            LOGGER.info("Total fichiers traités : " + generatedJsonFiles.size());
            LOGGER.info("Succès : " + successCount);
            LOGGER.info("Échecs : " + skippedCount);
            LOGGER.info("Taux de réussite : "
                    + String.format("%.2f", (successCount * 100.0 / (successCount + skippedCount))) + "%");
            LOGGER.info("=====================\n");
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la vectorisation : " + e.getMessage());
        }

        LOGGER.info("Traitement terminé. " + successCount + " fichiers sur " + (successCount + skippedCount)
                + " fichiers traités.");
    }

    /**
     * Traite les fichiers en batch avec DeepSeek.
     * 
     * @return le nombre de fichiers ignorés
     */
    private static int processBatchDeepSeek(List<Path> paths, RunOptions runOptions, PrintWriter errorWriter,
            List<Path> generatedJsonFiles) throws IOException {
        LOGGER.info("[BATCH DEEPSEEK MODE] Début traitement batch avec DeepSeek ultra-économique");

        int localSkippedCount = 0;
        BatchService batchService = new BatchService();
        try {
            // Préparer les inputs pour le batch
            List<LawInput> lawInputs = new ArrayList<>();

            for (Path path : paths) {
                File pdfFile = path.toFile();
                String pdfBaseName = pdfFile.getName().replaceFirst("(?i)\\.pdf$", "");

                // Lire le contenu OCR avec le vrai OcrService
                String rawOcr = "";
                try {
                    OcrService ocrService = new OcrService();
                    rawOcr = ocrService.getOcrText(pdfFile, runOptions.isRefreshOcr());
                } catch (Exception e) {
                    errorWriter.println("Erreur lecture OCR pour " + pdfBaseName + ": " + e.getMessage());
                    localSkippedCount++;
                    continue;
                }

                lawInputs.add(new LawInput(pdfFile, rawOcr, pdfBaseName));
            }

            // Traiter le batch avec DeepSeek
            Map<String, JSONObject> results = batchService.processBatch(lawInputs);

            // Exporter les résultats
            int successCount = 0;
            for (Map.Entry<String, JSONObject> entry : results.entrySet()) {
                String pdfBaseName = entry.getKey();
                JSONObject result = entry.getValue();

                if (result != null && result.has("metadata")) {
                    try {
                        String jsonOutput = result.toString(4);
                        String jsonName = pdfBaseName + ".json";
                        Path articleDir = Path.of("src/main/resources/data/article");
                        Files.createDirectories(articleDir);
                        Path jsonFile = articleDir.resolve(jsonName);
                        Files.writeString(jsonFile, jsonOutput);
                        generatedJsonFiles.add(jsonFile);

                        LOGGER.info("[DEEPSEEK] JSON exporté: " + jsonFile.toAbsolutePath());
                        errorWriter.println("Succès DeepSeek pour " + pdfBaseName);
                        successCount++;
                    } catch (Exception e) {
                        errorWriter.println("Erreur export JSON pour " + pdfBaseName + ": " + e.getMessage());
                    }
                } else {
                    errorWriter.println("Échec DeepSeek pour " + pdfBaseName);
                }
            }

            LOGGER.info("[BATCH DEEPSEEK] Terminé: " + successCount + "/" + lawInputs.size() + " succès");
        } catch (Exception e) {
            errorWriter.println("Erreur batch DeepSeek: " + e.getMessage());
            throw new IOException("Erreur traitement batch DeepSeek", e);
        }

        return localSkippedCount;
    }

    /**
     * Traite les fichiers en mode séquentiel.
     * 
     * @return le nombre de fichiers ignorés
     */
    private static int processSequential(List<Path> paths, RunOptions runOptions, PrintWriter errorWriter,
            List<Path> generatedJsonFiles) {
        LOGGER.info("[SEQUENTIAL MODE] Début traitement séquentiel");

        int localSkippedCount = 0;
        for (Path path : paths) {
            File pdfFile = path.toFile();
            String pdfName = pdfFile.getName();
            Matcher matcher = LAW_FILE_PATTERN.matcher(pdfName);

            if (!matcher.matches()) {
                errorWriter.println("Ignore (nom inattendu): " + pdfName);
                errorWriter.println("---");
                errorWriter.flush();
                localSkippedCount++;
                continue;
            } else {
                String type = matcher.group(1);
                String year = matcher.group(2);
                int intYear = Integer.parseInt(year);

                if (!runOptions.includes(intYear)) {
                    errorWriter.println("Ignore (hors intervalle): " + pdfName + " (année=" + intYear + ") ["
                            + runOptions.getMinYear() + ", " + runOptions.getMaxYear() + "]");
                    errorWriter.println("---");
                    errorWriter.flush();
                    localSkippedCount++;
                    continue;
                } else {
                    errorWriter.println("Traitement de : " + pdfName);
                    errorWriter.flush();

                    try {
                        long startTime = System.currentTimeMillis();
                        LOGGER.info("Début du traitement de " + pdfName + "...");
                        String pdfBaseName = pdfName.replaceFirst("(?i)\\.pdf$", "");

                        // Utiliser le service IntelliService amélioré
                        IntelliService intelliService = new IntelliService();
                        JSONObject jsonObject = intelliService.processLaw(pdfFile, "", pdfBaseName);

                        // Exporter le JSON
                        String jsonOutput = jsonObject.toString(4);
                        String jsonName = pdfBaseName + ".json";
                        Path articleDir = Path.of("src/main/resources/data/article");
                        Files.createDirectories(articleDir);
                        Path jsonFile = articleDir.resolve(jsonName);
                        Files.writeString(jsonFile, jsonOutput);
                        generatedJsonFiles.add(jsonFile);
                        LOGGER.info("JSON exporté dans : " + jsonFile.toAbsolutePath());

                        long endTime = System.currentTimeMillis();
                        LOGGER.info(
                                "Temps total de traitement pour " + pdfName + " : " + (endTime - startTime) + " ms");
                        errorWriter.println("Traitement réussi pour " + pdfName);

                    } catch (Exception e) {
                        errorWriter.println("Erreur lors du traitement de " + pdfName + " : " + e.getMessage());
                        e.printStackTrace(errorWriter);
                    }
                    errorWriter.println("---");
                    errorWriter.flush();
                }
            }
        }

        return localSkippedCount;
    }
}
