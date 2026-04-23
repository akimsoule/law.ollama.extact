package org.law;

import org.json.JSONObject;
import org.law.model.LawSection;
import org.law.model.Stat;
import org.law.service.extract.*;
import org.law.service.process.*;
import org.law.service.embed.*;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {

    private static final Pattern LAW_FILE_PATTERN = Pattern.compile("^([^-]+)-(\\d{2}|\\d{4})-(\\d{2}|\\d{3})\\.pdf$", Pattern.CASE_INSENSITIVE);

    private static final class RunOptions {
        private final int minYear;
        private final int maxYear;
        private final boolean refreshOcr;

        private RunOptions(int minYear, int maxYear, boolean refreshOcr) {
            this.minYear = minYear;
            this.maxYear = maxYear;
            this.refreshOcr = refreshOcr;
        }

        private boolean includes(int year) {
            return year >= minYear && year <= maxYear;
        }

        private static RunOptions fromArgs(String[] args) {
            int minYear = 0;
            int maxYear = 9999;
            boolean refreshOcr = false;

            for (String arg : args) {
                if (arg.startsWith("--minYear=")) {
                    minYear = parseYearArg("minYear", arg.substring("--minYear=".length()), minYear);
                } else if (arg.startsWith("--maxYear=")) {
                    maxYear = parseYearArg("maxYear", arg.substring("--maxYear=".length()), maxYear);
                } else if (arg.equals("--refreshOcr")) {
                    refreshOcr = true;
                }
            }

            if (minYear > maxYear) {
                System.err.println("Intervalle d'annees invalide: minYear > maxYear. Utilisation de 0..9999.");
                return new RunOptions(0, 9999, refreshOcr);
            }

            return new RunOptions(minYear, maxYear, refreshOcr);
        }

        private static int parseYearArg(String key, String value, int defaultValue) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.err.println("Argument --" + key + " invalide (" + value + "), valeur par defaut conservee: " + defaultValue);
                return defaultValue;
            }
        }
    }

    private static int normalizeYearForFiltering(String yearToken) {
        int value = Integer.parseInt(yearToken);
        if (yearToken.length() == 4) {
            return value;
        }

        // Pivot adapte au corpus attendu (1960-2026).
        if (value >= 60) {
            return 1900 + value;
        }
        return 2000 + value;
    }

    public static void main(String[] args) {
        Path loiDir = Path.of("src/main/resources/data/loi");
        Path errorsFile = Path.of("errors.log");
        RunOptions runOptions = RunOptions.fromArgs(args);
        int successCount = 0;
        int skippedCount = 0;
        List<Path> generatedJsonFiles = new java.util.ArrayList<>();

        try (PrintWriter errorWriter = new PrintWriter(Files.newBufferedWriter(errorsFile));
                Stream<Path> pdfFiles = Files.list(loiDir)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))) {

                List<Path> paths = pdfFiles
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());

            for (Path path : paths) {
                File pdfFile = path.toFile();
                String pdfName = pdfFile.getName();
                Matcher matcher = LAW_FILE_PATTERN.matcher(pdfName);

                if (!matcher.matches()) {
                    skippedCount++;
                    errorWriter.println("Ignore (nom inattendu): " + pdfName);
                    errorWriter.println("---");
                    errorWriter.flush();
                } else {
                    String type = matcher.group(1);
                    String year = matcher.group(2);
                    int normalizedYear = normalizeYearForFiltering(year);

                    if (!runOptions.includes(normalizedYear)) {
                        skippedCount++;
                        errorWriter.println("Ignore (hors intervalle): " + pdfName + " (annee=" + normalizedYear + ") [" + runOptions.minYear + ", " + runOptions.maxYear + "]");
                        errorWriter.println("---");
                        errorWriter.flush();
                    } else {
                        errorWriter.println("Traitement de : " + pdfName);
                        errorWriter.flush();

                        try {
                    long startTime = System.currentTimeMillis();
                    System.out.println("Début du traitement de " + pdfName + "...");
                    String pdfBaseName = pdfName.replaceFirst("(?i)\\.pdf$", "");

                    // ── Étape 1 : OCR (avec cache idempotent) ────────────────────────────
                    OcrService ocrService = new OcrService();
                    long startExtract = System.currentTimeMillis();
                    String fullText = ocrService.getOcrText(pdfFile, runOptions.refreshOcr);
                    long endExtract = System.currentTimeMillis();
                    System.out.println("Temps pour extraction du texte : " + (endExtract - startExtract) + " ms");

                    // ── Étape 2 : Pré-traitement OCR ─────────────────────────────────────
                    ProcessLineImpl processLine = new ProcessLineImpl();
                    long startProcess = System.currentTimeMillis();
                    String fullTextCorr = processLine.preProcessOcrLines(fullText);
                    long endProcess = System.currentTimeMillis();
                    System.out.println("Temps pour prétraitement OCR : " + (endProcess - startProcess) + " ms");

                    // ── Étape 3 : Extraction regex (pipeline classique) ───────────────────
                    ExtractorService extractorService = new ExtractorService(fullTextCorr);
                    long startExtractLaw = System.currentTimeMillis();
                    LawSection lawSection = extractorService.extractLawSection();
                    lawSection.setYear(String.valueOf(normalizedYear));
                    lawSection.setType(type);
                    long endExtractLaw = System.currentTimeMillis();
                    System.out.println(
                            "Temps pour extraction des sections : " + (endExtractLaw - startExtractLaw) + " ms");

                    // ── Étape 4 : Construction JSON ───────────────────────────────────────
                    JsonService jsonService = new JsonService();
                    long startJson = System.currentTimeMillis();
                    JSONObject jsonObject = jsonService.buildJson(lawSection);
                    long endJson = System.currentTimeMillis();
                    System.out.println("Temps pour construction du JSON : " + (endJson - startJson) + " ms");

                    // ── Étape 5 : QA du pipeline classique ───────────────────────────────
                    QAService qaService = new QAService();
                    List<String> qaErrors = qaService.validateJson(jsonObject);

                    // ── Étape 6 : Fallback LLM si QA échoue ─────────────────────────────
                    if (!qaErrors.isEmpty()) {
                        System.out.println("[QA] " + qaErrors.size() + " erreur(s) détectée(s) pour " + pdfName
                                + " — tentative de correction via IntelliService...");
                        for (String err : qaErrors) {
                            System.out.println("  - " + err);
                        }
                        try {
                            IntelliService intelliService = new IntelliService();
                            long startLlm = System.currentTimeMillis();
                            JSONObject llmJson = intelliService.processLaw(pdfFile, fullText, pdfBaseName);
                            long endLlm = System.currentTimeMillis();
                            System.out.println("[IntelliService] Terminé en " + (endLlm - startLlm) + " ms");

                            // Re-QA sur le résultat LLM
                            List<String> llmQaErrors = qaService.validateJson(llmJson);
                            if (llmQaErrors.size() < qaErrors.size()) {
                                System.out.println("[IntelliService] Qualité améliorée: "
                                        + qaErrors.size() + " → " + llmQaErrors.size() + " erreur(s).");
                                jsonObject = llmJson;
                                qaErrors = llmQaErrors;

                                if (!qaErrors.isEmpty() || IntelliService.hasEmptySignatoryName(jsonObject)) {
                                    try {
                                        JSONObject repairedJson = intelliService.repairJson(fullText, jsonObject, qaErrors,
                                                pdfBaseName);
                                        List<String> repairedQaErrors = qaService.validateJson(repairedJson);
                                        if (repairedQaErrors.size() < qaErrors.size()
                                                || (qaErrors.size() == repairedQaErrors.size()
                                                        && IntelliService.hasEmptySignatoryName(jsonObject)
                                                        && !IntelliService.hasEmptySignatoryName(repairedJson))) {
                                            System.out.println("[IntelliService] Réparation ciblée réussie: "
                                                    + qaErrors.size() + " → " + repairedQaErrors.size()
                                                    + " erreur(s).");
                                            jsonObject = repairedJson;
                                            qaErrors = repairedQaErrors;
                                        }
                                    } catch (Exception repairEx) {
                                        errorWriter.println("[IntelliService] Erreur réparation LLM pour " + pdfName
                                                + " : " + repairEx.getMessage());
                                        System.err.println("[IntelliService] Erreur réparation: " + repairEx.getMessage());
                                    }
                                }
                            } else {
                                System.out.println("[IntelliService] Pas d'amélioration détectée; conservation du résultat regex.");
                            }
                        } catch (Exception llmEx) {
                            errorWriter.println("[IntelliService] Erreur fallback LLM pour " + pdfName + " : " + llmEx.getMessage());
                            System.err.println("[IntelliService] Erreur: " + llmEx.getMessage());

                            try {
                                IntelliService intelliService = new IntelliService();
                                JSONObject repairedJson = intelliService.repairJson(fullText, jsonObject, qaErrors,
                                        pdfBaseName);
                                List<String> repairedQaErrors = qaService.validateJson(repairedJson);
                                if (repairedQaErrors.size() < qaErrors.size()) {
                                    System.out.println("[IntelliService] Réparation directe réussie après échec d'extraction: "
                                            + qaErrors.size() + " → " + repairedQaErrors.size() + " erreur(s).");
                                    jsonObject = repairedJson;
                                    qaErrors = repairedQaErrors;
                                }
                            } catch (Exception repairEx) {
                                errorWriter.println("[IntelliService] Echec reparation directe pour " + pdfName
                                        + " : " + repairEx.getMessage());
                                System.err.println("[IntelliService] Erreur réparation directe: " + repairEx.getMessage());
                            }
                        }
                    } else {
                        System.out.println("[QA] OK pour " + pdfName);
                    }

                    // ── Étape 7 : Export JSON ─────────────────────────────────────────────
                    String jsonOutput = jsonObject.toString(4);
                    try {
                        String jsonName = pdfBaseName + ".json";
                        Path articleDir = Path.of("src/main/resources/data/article");
                        Files.createDirectories(articleDir);
                        Path jsonFile = articleDir.resolve(jsonName);
                        Files.writeString(jsonFile, jsonOutput);
                        generatedJsonFiles.add(jsonFile);
                        System.out.println("JSON exporté dans : " + jsonFile.toAbsolutePath());
                    } catch (IOException e) {
                        errorWriter.println("Erreur lors de l'export du JSON pour " + pdfName + " : " + e.getMessage());
                    }

                    // ── Étape 8 : Rapport QA final ────────────────────────────────────────
                    if (!qaErrors.isEmpty()) {
                        errorWriter.println("Erreurs QA résiduelles pour " + pdfName + " :");
                        for (String error : qaErrors) {
                            errorWriter.println("- " + error);
                        }
                    }

                    long endTime = System.currentTimeMillis();
                    System.out.println(
                            "Temps total de traitement pour " + pdfName + " : " + (endTime - startTime) + " ms");
                    errorWriter.println("Traitement réussi pour " + pdfName);
                    successCount++;

                        } catch (Exception e) {
                            errorWriter.println("Erreur lors du traitement de " + pdfName + " : " + e.getMessage());
                            e.printStackTrace(errorWriter);
                        }
                        errorWriter.println("---");
                        errorWriter.flush();
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("Erreur lors de l'accès au répertoire ou au fichier d'erreurs : " + e.getMessage());
        }

        try {
            VectorService vectorService = new VectorService();
            long startVector = System.currentTimeMillis();
            vectorService.vectorizeArticles(generatedJsonFiles);
            long endVector = System.currentTimeMillis();
            System.out.println("Temps pour vectorisation globale : " + (endVector - startVector) + " ms");
        } catch (Exception e) {
            System.err.println("Erreur lors de la vectorisation globale : " + e.getMessage());
        }

        System.out.println("Resume traitement: " + successCount + " fichiers traites, " + skippedCount + " ignores.");

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
