package org.law.runner;

import org.json.JSONObject;
import org.law.model.LawSection;
import org.law.service.extract.ExtractorService;
import org.law.service.extract.OcrService;
import org.law.service.process.JsonService;
import org.law.service.process.ProcessLineImpl;
import org.law.service.process.QAService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

public class ScriptExtractorRunner {

    private static final Path PDF_DIR = Paths.get("src/main/resources/data/loi");
    private static final Path OUTPUT_DIR = Paths.get("src/main/resources/data/script_output");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        boolean force = args.length > 0 && "--force".equals(args[0]);
        String targetPdf = null;
        QAService qa = new QAService();

        for (int i = 0; i < args.length; i++) {
            if ("--pdf".equals(args[i]) && i + 1 < args.length) {
                targetPdf = args[i + 1];
            }
        }

        try (Stream<Path> pdfs = Files.list(PDF_DIR).filter(p -> p.toString().endsWith(".pdf"))) {
            for (Path pdfPath : pdfs.toList()) {
                String pdfName = pdfPath.getFileName().toString();
                if (targetPdf != null && !pdfName.equals(targetPdf)) {
                    continue;
                }

                String baseName = pdfName.replace(".pdf", "");
                Path targetJson = OUTPUT_DIR.resolve(baseName + ".json");

                if (!force && Files.exists(targetJson)) {
                    System.out.println("⏭️ SKIP (existe déjà) : " + pdfName);
                    continue;
                }

                System.out.println("🛠️ TRAITEMENT SCRIPT : " + pdfName);
                try {
                    String fullText = new OcrService().getOcrText(pdfPath.toFile());
                    String correctedText = new ProcessLineImpl().preProcessOcrLines(fullText);

                    ExtractorService extractor = new ExtractorService(correctedText);
                    LawSection lawSection = extractor.extractLawSection();

                    String[] parts = pdfName.split("[-|.]");
                    if (parts.length > 1) {
                        lawSection.setYear(parts[1]);
                        lawSection.setType(parts[0]);
                    }

                    JSONObject json = new JsonService().buildJson(lawSection);

                    List<String> qaErrors = qa.validateJson(json);
                    if (!qaErrors.isEmpty()) {
                        StringBuilder logEntry = new StringBuilder();
                        logEntry.append("[").append(Instant.now()).append("] ERREUR QA SCRIPT SUR ").append(pdfName)
                                .append("\n");
                        for (String err : qaErrors) {
                            logEntry.append("  - ").append(err).append("\n");
                        }
                        logEntry.append("---\n");
                        Files.writeString(Paths.get("errors.log"), logEntry.toString(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        System.err.println("   ⚠️ [SCRIPT QA FAIL] " + pdfName + " -> voir errors.log");
                    }

                    Files.writeString(targetJson, json.toString(2));
                    System.out.println("✅ Script OK : " + targetJson);
                } catch (Exception e) {
                    System.err.println("❌ Échec Script sur " + pdfName + " : " + e.getMessage());
                }
            }
        }
    }
}
