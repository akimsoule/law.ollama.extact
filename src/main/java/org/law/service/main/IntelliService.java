package org.law.service.main;

/**
 * Legal Document Restructuring Tool
 *
 * Hardware:
 * - Lenovo ThinkCentre M720q
 * - i5-8400T / 16GB RAM
 *
 * Models:
 * - Vision: moondream (post-OCR correction only)
 * - Text: mistral:7b-instruct-v0.3-q4_K_M
 *
 * Features:
 * - Page-based OCR correction
 * - Anti-hallucination prompts
 * - Forced JSON Schema validation
 * - Law-oriented extraction
 */

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.Base64;

public class IntelliService {

        private static final String PDF_PATH = "loi/loi-2022.pdf";
        private static final String OUTPUT_PATH = "output.json";

        public static void main(String[] args) {
                new IntelliService().execute();
        }

        public void execute() {

                // -----------------------------
                // 1. Vision model (OCR correction only)
                // -----------------------------
                OllamaChatModel visionModel = OllamaChatModel.builder()
                                .baseUrl("http://localhost:11434")
                                .modelName("llava:7b")
                                .temperature(0.0)
                                .timeout(Duration.ofMinutes(10))
                                .build();

                // -----------------------------
                // 2. Text model (strict JSON)
                // -----------------------------
                OllamaChatModel textModel = OllamaChatModel.builder()
                                .baseUrl("http://localhost:11434")
                                .modelName("mistral:7b-instruct-v0.3-q4_K_M")
                                .temperature(0.0)
                                .timeout(Duration.ofMinutes(10))
                                .responseFormat(ResponseFormat.builder()
                                                .type(dev.langchain4j.model.chat.request.ResponseFormatType.JSON)
                                                .jsonSchema(buildLawSchema())
                                                .build())
                                .build();

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
                                System.out.println("[OCR] Correcting page " + (i + 1));

                                String base64Image = renderPage(renderer, i);
                                String correctedPage = correctOcrPage(
                                                visionModel,
                                                ocrPages.get(i),
                                                base64Image);

                                correctedText
                                                .append("\n\n=== PAGE ")
                                                .append(i + 1)
                                                .append(" ===\n")
                                                .append(correctedPage.trim());
                        }

                        System.out.println("[LLM] Extracting structured law data...");

                        String json = textModel.chat(
                                        UserMessage.from(buildExtractionPrompt(correctedText.toString()))).aiMessage()
                                        .text();

                        Files.writeString(Paths.get(OUTPUT_PATH), json);
                        System.out.println("✔ Pipeline completed successfully.");

                } catch (Exception e) {
                        e.printStackTrace();
                }
        }

        // =========================================================
        // ===================== OCR HELPERS =======================
        // =========================================================

        private String renderPage(PDFRenderer renderer, int pageIndex) throws IOException {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                return Base64.getEncoder().encodeToString(out.toByteArray());
        }

        private String correctOcrPage(
                        OllamaChatModel model,
                        String ocrText,
                        String base64Image) {

                UserMessage msg = UserMessage.from(
                                TextContent.from(buildVisionPrompt(ocrText)),
                                ImageContent.from(base64Image, "image/png"));

                return model.chat(msg).aiMessage().text();
        }

        // =========================================================
        // ===================== PROMPTS ===========================
        // =========================================================

        private String buildVisionPrompt(String ocrText) {
                return """
                                You are correcting OCR errors using the provided image.

                                STRICT RULES:
                                - DO NOT add new content
                                - DO NOT remove content
                                - DO NOT summarize
                                - Preserve line breaks, numbering, punctuation
                                - Only fix obvious OCR mistakes (e.g. l/1, O/0, broken words)

                                OCR TEXT:
                                <<<
                                %s
                                >>>
                                """.formatted(ocrText);
        }

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
        // ===================== JSON SCHEMA =======================
        // =========================================================

        private JsonSchema buildLawSchema() {

                JsonObjectSchema signatorySchema = JsonObjectSchema.builder()
                                .addProperty("role", JsonStringSchema.builder().build())
                                .addProperty("name", JsonStringSchema.builder().build())
                                .required(List.of("role", "name"))
                                .build();

                JsonObjectSchema metadataSchema = JsonObjectSchema.builder()
                                .addProperty("lawNumber", JsonStringSchema.builder().build())
                                .addProperty("lawDate", JsonStringSchema.builder().build())
                                .addProperty("lawObject", JsonStringSchema.builder().build())
                                .addProperty("signatories",
                                                JsonArraySchema.builder().items(signatorySchema).build())
                                .required(List.of("lawNumber", "lawDate", "lawObject", "signatories"))
                                .build();

                JsonObjectSchema articleSchema = JsonObjectSchema.builder()
                                .addProperty("index", JsonStringSchema.builder().build())
                                .addProperty("content", JsonStringSchema.builder().build())
                                .required(List.of("index", "content"))
                                .build();

                return JsonSchema.builder()
                                .name("LawStructure")
                                .rootElement(JsonObjectSchema.builder()
                                                .addProperty("metadata", metadataSchema)
                                                .addProperty("articles",
                                                                JsonArraySchema.builder().items(articleSchema).build())
                                                .required(List.of("metadata", "articles"))
                                                .build())
                                .build();
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
