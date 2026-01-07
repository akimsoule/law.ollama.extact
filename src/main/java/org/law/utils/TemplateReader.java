package org.law.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public interface TemplateReader {

    Path TEMPLATE_DIR = Path.of("src/main/resources/template");
    Path SIGNATORIES_CSV = Paths.get("src/main/resources/signatories.csv");

    default String readTemplate(String fileName) throws IOException {
        try (Stream<String> lines = Files.lines(TEMPLATE_DIR.resolve(fileName))) {
            return lines
                    .filter(line -> !line.trim().startsWith("#"))
                    .reduce("", (a, b) -> a + "\n" + b)
                    .trim();
        }
    }

    /**
     * Lit le fichier CSV des signataires en respectant le format avec Stream et
     * filter #
     */
    default List<String> readSignatoriesCsv() throws IOException {
        try (Stream<String> lines = Files.lines(SIGNATORIES_CSV)) {
            String content = lines
                    .filter(line -> !line.trim().startsWith("#")) // Filtre tout ce qui commence par #
                    .filter(line -> line.contains(";")) // Ne prend que les lignes de données
                    .reduce("", (a, b) -> a + "\n" + b)
                    .trim();

            if (content.isEmpty())
                return List.of();
            return Arrays.asList(content.split("\n"));
        }
    }
}
