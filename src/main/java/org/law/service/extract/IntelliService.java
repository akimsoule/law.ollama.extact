package org.law.service.extract;

/**
 * Legal Document Restructuring Tool
 *
 * Models:
 * - Vision: N/A (using OCR extraction directly)
 * - Text: Ollama local models (e.g., mistral, llama2)
 *
 * Features:
 * - Page-based OCR correction
 * - Anti-hallucination prompts
 * - Forced JSON Schema validation
 * - Law-oriented extraction
 * - Local inference (no API calls)
 */

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.law.service.chat.OllamaChatClient;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class IntelliService {

        private static final String PDF_PATH = "loi/loi-1960.pdf";
        private static final String OUTPUT_PATH = "output.json";

        public static void main(String[] args) {
                new IntelliService().execute();
        }

        public void execute() {

                // Initialize Ollama client for local text generation
                OllamaChatClient ollamaClient = new OllamaChatClient("http://localhost:11434");
                String model = "mistral"; // ou "llama2", etc.

                // Vérifier que Ollama est disponible
                if (!ollamaClient.isAvailable()) {
                        System.err.println("Erreur : Ollama n'est pas accessible sur http://localhost:11434");
                        System.err.println("Lancez Ollama avec: ollama serve");
                        return;
                }

                try (InputStream pdfStream = getClass().getClassLoader().getResourceAsStream(PDF_PATH);
                                PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdfStream))) {

                        PDFRenderer renderer = new PDFRenderer(document);
                        OcrService ocrService = new OcrService();
                        String rawOcr = ocrService.runEnhancedOcr(document);
                        List<String> ocrPages = splitOcrByPage(rawOcr);

                        if (ocrPages.size() != document.getNumberOfPages()) {
                                throw new IllegalStateException(
                                                "OCR page count (" + ocrPages.size() +
                                                                ") does not match PDF page count (" +
                                                                document.getNumberOfPages() + ")");
                        }

                        StringBuilder correctedText = new StringBuilder();

                        for (int i = 0; i < document.getNumberOfPages(); i++) {
                                System.out.println("[OCR] Processing page " + (i + 1));

                                correctedText
                                                .append("\n\n=== PAGE ")
                                                .append(i + 1)
                                                .append(" ===\n")
                                                .append(ocrPages.get(i).trim());
                        }

                        System.out.println(
                                        "[LLM] Extracting structured law data with Ollama (model: " + model + ")...");

                        String json = ollamaClient.generate(
                                        buildExtractionPrompt(correctedText.toString()),
                                        model,
                                        0.2); // Température basse pour extraction structurée

                        Files.writeString(Paths.get(OUTPUT_PATH), json);
                        System.out.println("✔ Pipeline completed successfully.");

                } catch (Exception e) {
                        e.printStackTrace();
                }
        }

        // =========================================================
        // ===================== PROMPTS ===========================
        // =========================================================

        private String buildExtractionPrompt(String fullText) {
                String signatoriesData = loadSignatoriesData();
                return """
                                You are a legal document parser.

                                TASK:
                                Extract structured law data strictly following the provided JSON schema.

                                RULES:
                                - Extract ALL articles from the law text, including introductory articles (like Article 1er), modified articles (like Article 810-1), and final articles (like Article 2)
                                - Articles must start with "Article" followed by the article number, e.g., "Article 810-1 : ..." or "Article 2 : ..."
                                - The index must be the article number (e.g., "810-1", "2"), not a sequential number
                                - Do not include titles or section headers in the articles array (e.g., "TITRE IIl DU LIVRE V...")
                                - Only include actual articles with their full content
                                - Do not summarize articles
                                - Preserve full legal wording
                                - Articles must appear in the order they appear in the text
                                - Do not invent missing information

                                KNOWN SIGNATORIES:
                                %s

                                LAW TEXT:
                                <<<
                                %s
                                >>>
                                """
                                .formatted(signatoriesData, fullText);
        }

        private String loadSignatoriesData() {
                try (InputStream is = getClass().getClassLoader().getResourceAsStream("signatories.csv")) {
                        if (is == null) {
                                return "No signatories data available.";
                        }
                        return new String(is.readAllBytes());
                } catch (Exception e) {
                        return "Error loading signatories data: " + e.getMessage();
                }
        }

        // =========================================================
        // ===================== OCR SPLIT =========================
        // =========================================================

        /**
         * Expected OCR format:
         * === PAGE 1 ===
         * text...
         * === PAGE 2 ===
         * text...
         */
        private List<String> splitOcrByPage(String rawOcr) {

                String[] pages = rawOcr.split("===\\s*PAGE\\s*\\d+\\s*===");
                List<String> result = new ArrayList<>();

                for (String p : pages) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty()) {
                                result.add(trimmed);
                        }
                }

                return result;
        }
}
