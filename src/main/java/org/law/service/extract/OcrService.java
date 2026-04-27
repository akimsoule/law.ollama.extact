package org.law.service.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.TessBaseAPI;
import org.bytedeco.tesseract.global.tesseract;
import org.apache.pdfbox.text.PDFTextStripper;
import org.law.service.process.StrategyService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.bytedeco.leptonica.global.leptonica.pixConvertRGBToGray;
import static org.bytedeco.leptonica.global.leptonica.pixDestroy;
import static org.bytedeco.leptonica.global.leptonica.pixReadMem;
import static org.bytedeco.leptonica.global.leptonica.pixThresholdToBinary;

/**
 * Service dédié à l'OCR avec Tesseract.
 */
public class OcrService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OcrService.class);

    private static final String TESSDATA_PATH = "src/main/resources/tessdata";

    /**
     * Effectue l'OCR amélioré sur l'ensemble du document PDF.
     *
     * @param document le document PDF déjà chargé
     * @return le texte extrait via OCR
     * @throws Exception en cas d'erreur d'initialisation ou de traitement
     */
    public String runEnhancedOcr(PDDocument document) throws Exception {
        StringBuilder sb = new StringBuilder();

        // Combine all simple word files (ending with "words") and pattern files (ending
        // with "pattern")
        Path combinedWords = combineFiles("words", "combined_words.txt");
        Path combinedPatterns = combineFiles("pattern", "combined_patterns.txt");

        try (TessBaseAPI api = new TessBaseAPI()) {
            if (api.Init(TESSDATA_PATH, "fra") != 0) {
                throw new IllegalStateException("Impossible d'initialiser Tesseract.");
            }

            // Set combined files for Tesseract
            api.SetVariable("user_words_file", combinedWords.toAbsolutePath().toString());
            api.SetVariable("user_patterns_file", combinedPatterns.toAbsolutePath().toString());

            api.SetPageSegMode(tesseract.PSM_AUTO);
            api.SetVariable("tessedit_char_whitelist",
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,:-'()°/àâéèêëîïôûùçœŒ ");

            PDFRenderer renderer = new PDFRenderer(document);

            int numPages = document.getNumberOfPages();
            for (int i = 0; i < numPages; i++) {
                BufferedImage bImg = renderer.renderImageWithDPI(i, 300);

                // Traitement d'image avec Leptonica
                PIX pixSource = this.convertToPix(bImg);
                PIX pixGray = pixConvertRGBToGray(pixSource, 0.3f, 0.5f, 0.2f);
                PIX pixBinarized = pixThresholdToBinary(pixGray, 115);

                api.SetImage(pixBinarized);

                String pageText = null;
                BytePointer outText = null;

                if (i == numPages - 1) {
                    // Dernière page : on choisit le bloc avec le plus de caractères non espaces
                    String bestText = null;
                    int bestLength = 0;

                    // 1. PSM_AUTO
                    api.SetPageSegMode(tesseract.PSM_AUTO);
                    outText = api.GetUTF8Text();
                    String textAuto = outText.getString(StandardCharsets.UTF_8);
                    int lenAuto = (textAuto != null) ? textAuto.replaceAll("[ \t\n\r]", "").length() : 0;
                    if (lenAuto > bestLength && textAuto != null && !textAuto.isBlank()) {
                        bestText = textAuto;
                        bestLength = lenAuto;
                    }
                    outText.deallocate();

                    // 2. PSM_SINGLE_BLOCK
                    api.SetPageSegMode(tesseract.PSM_SINGLE_BLOCK);
                    outText = api.GetUTF8Text();
                    String textBlock = outText.getString(StandardCharsets.UTF_8);
                    int lenBlock = (textBlock != null) ? textBlock.replaceAll("[ \t\n\r]", "").length() : 0;
                    if (lenBlock > bestLength && textBlock != null && !textBlock.isBlank()) {
                        bestText = textBlock;
                        bestLength = lenBlock;
                    }
                    outText.deallocate();

                    // 3. PSM_SPARSE_TEXT
                    api.SetPageSegMode(tesseract.PSM_SPARSE_TEXT);
                    outText = api.GetUTF8Text();
                    String textSparse = outText.getString(StandardCharsets.UTF_8);
                    int lenSparse = (textSparse != null) ? textSparse.replaceAll("[ \t\n\r]", "").length() : 0;
                    if (lenSparse > bestLength && textSparse != null && !textSparse.isBlank()) {
                        bestText = textSparse;
                        bestLength = lenSparse;
                    }
                    outText.deallocate();

                    // 4. PDFTextStripper
                    String textPdf = null;
                    int lenPdf = 0;
                    try {
                        PDFTextStripper stripper = new PDFTextStripper();
                        stripper.setStartPage(i + 1);
                        stripper.setEndPage(i + 1);
                        textPdf = stripper.getText(document);
                        if (textPdf != null && !textPdf.isBlank()) {
                            lenPdf = textPdf.replaceAll("[ \t\n\r]", "").length();
                            if (lenPdf > bestLength) {
                                bestText = textPdf;
                                bestLength = lenPdf;
                            }
                        }
                    } catch (Exception e) {
                        // Si PDFTextStripper échoue, on ignore
                    }

                    if (bestText == null || bestText.isBlank()) {
                        throw new IllegalStateException("Page " + (i + 1)
                                + " : texte OCR vide même après tous les fallbacks. Vérifiez l'image de la page.");
                    }
                    pageText = bestText;
                } else {
                    // Pour toutes les autres pages : on prend le premier fallback non vide
                    // 1. PSM_AUTO
                    api.SetPageSegMode(tesseract.PSM_AUTO);
                    outText = api.GetUTF8Text();
                    pageText = outText.getString(StandardCharsets.UTF_8);
                    outText.deallocate();

                    // 2. PSM_SINGLE_BLOCK
                    if (pageText == null || pageText.isBlank()) {
                        api.SetPageSegMode(tesseract.PSM_SINGLE_BLOCK);
                        outText = api.GetUTF8Text();
                        pageText = outText.getString(StandardCharsets.UTF_8);
                        outText.deallocate();
                    }

                    // 3. PSM_SPARSE_TEXT
                    if (pageText == null || pageText.isBlank()) {
                        api.SetPageSegMode(tesseract.PSM_SPARSE_TEXT);
                        outText = api.GetUTF8Text();
                        pageText = outText.getString(StandardCharsets.UTF_8);
                        outText.deallocate();
                    }

                    // 4. PDFTextStripper
                    if (pageText == null || pageText.isBlank()) {
                        try {
                            PDFTextStripper stripper = new PDFTextStripper();
                            stripper.setStartPage(i + 1);
                            stripper.setEndPage(i + 1);
                            pageText = stripper.getText(document);
                            if (pageText != null) {
                                pageText = pageText.trim();
                            }
                        } catch (Exception e) {
                            // Si PDFTextStripper échoue, pageText reste vide
                        }
                    }

                    if (pageText == null || pageText.isBlank()) {
                        throw new IllegalStateException("Page " + (i + 1)
                                + " : texte OCR vide même après tous les fallbacks. Vérifiez l'image de la page.");
                    }
                }

                sb.append("=== PAGE ").append(i + 1).append(" ===\n").append(pageText).append("\n");

                // Libération mémoire native
                pixDestroy(pixSource);
                pixDestroy(pixGray);
                pixDestroy(pixBinarized);

                if (pageText.toUpperCase().contains("AMPLIATIONS")) {
                    break;
                }
            }
            api.End();
        }
        return sb.toString();
    }

    /**
     * Convertit une {@link BufferedImage} en {@link PIX} utilisable par Leptonica.
     */
    private PIX convertToPix(BufferedImage bi) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", baos);
        byte[] bytes = baos.toByteArray();
        return pixReadMem(bytes, bytes.length);
    }

    /**
     * Combine all files in the tessdata directory whose name ends with the given
     * suffix
     * (e.g., "words" or "pattern") into a single temporary file.
     *
     * @param suffix       the suffix to filter files (without the leading dot)
     * @param tempFileName name of the temporary combined file (without extension)
     * @return Path to the temporary combined file
     * @throws IOException if file operations fail
     */
    private Path combineFiles(String suffix, String tempFileName) throws IOException {
        Path tessDataDir = Path.of(TESSDATA_PATH);
        if (!Files.isDirectory(tessDataDir)) {
            throw new IOException("Tessdata directory not found: " + TESSDATA_PATH);
        }
        List<String> lines;
        try (java.util.stream.Stream<Path> stream = Files.list(tessDataDir)) {
            lines = stream
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .flatMap(p -> {
                        try {
                            LOGGER.info("Loading ... " + p.getFileName());
                            return Files.readAllLines(p).stream();
                        } catch (IOException e) {
                            return List.<String>of().stream();
                        }
                    })
                    .toList();
        }
        Path tempFile = Files.createTempFile(tempFileName, ".txt");
        Files.write(tempFile, lines);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    /**
     * Obtient le texte OCR pour un fichier PDF, en utilisant un cache pour
     * l'idempotence.
     *
     * @param pdfFile le fichier PDF
     * @return le texte extrait
     * @throws IOException en cas d'erreur
     */
    public String getOcrText(java.io.File pdfFile) throws IOException {
        return getOcrText(pdfFile, false);
    }

    public String getOcrText(java.io.File pdfFile, boolean forceRefresh) throws IOException {
        String pdfName = pdfFile.getName();
        String ocrName = pdfName.replace(".pdf", ".txt");
        Path ocrDir = Path.of("src/main/resources/data/ocr");
        Path ocrFile = ocrDir.resolve(ocrName);

        if (!forceRefresh && Files.exists(ocrFile)) {
            LOGGER.info("Texte OCR chargé depuis : " + ocrFile.toAbsolutePath());
            return Files.readString(ocrFile);
        } else {
            if (forceRefresh && Files.exists(ocrFile)) {
                LOGGER.info("Rafraichissement OCR force pour : " + ocrFile.toAbsolutePath());
            }
            StrategyService strategyService = new StrategyService();
            String fullText = strategyService.getFullContent(pdfFile);
            Files.createDirectories(ocrDir);
            Files.writeString(ocrFile, fullText);
            LOGGER.info("Texte OCR sauvegardé dans : " + ocrFile.toAbsolutePath());
            return fullText;
        }
    }
}
