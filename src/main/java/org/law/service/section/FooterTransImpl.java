package org.law.service.section;

import org.law.model.LawSection;
import org.law.model.Signataire;
import org.law.service.main.LangToolService;
import org.law.utils.*;

import java.io.IOException;
import java.text.Normalizer;
import java.util.*;

import static org.law.service.section.Constant.START_FAIT;

public class FooterTransImpl implements BoolString, ExtractString, TransString, TemplateReader, UtilsString {

    private final Map<String, Signataire> signatoriesMapping = new LinkedHashMap<>();

    public FooterTransImpl() {
        try {
            // Chargement du mapping via la méthode de l'interface TemplateReader
            List<String> lines = readSignatoriesCsv();
            if (lines.size() > 1) {
                for (String line : lines) {
                    String[] parts = line.split(";", 2);
                    if (parts.length >= 2) {
                        String name = parts[0].trim();
                        String roles = parts[1].trim();
                        // Clé normalisée pour le match (Majuscules + sans accent)
                        signatoriesMapping.put(stripAccents(name.toUpperCase()), new Signataire(name, roles));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[ERREUR] Impossible de charger le fichier signatories.csv : " + e.getMessage());
        }
    }

    public String extractFaitLine(String footer) {
        // Logique similaire à cleanUpHeader
        StringBuilder faitLine = new StringBuilder();
        String[] lines = footer.split("\n");
        for (String line : lines) {
            if (START_FAIT.stream().anyMatch(start -> startsWithIgnoreCase(line, start))) {
                faitLine.append(line);
                break;
            }
        }
        return faitLine.toString();
    }

    public String cleanUpFooter(String footer) throws Exception {
        if (footer == null || footer.isBlank())
            return "";

        String cityName = containsIgnoreCase(footer, "PORTO-NOVO") ? "Porto-Novo" : "Cotonou";
        String signatoryDate = "";
        StringBuilder signatoriesText = new StringBuilder();

        boolean inSignatories = false;
        String[] lines = footer.split("\\R");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;

            if (containsIgnoreCase(trimmed, "Fait à") && containTarget(trimmed, cityName, 2)) {
                signatoryDate = extractFromFirstDigit(trimmed);
                inSignatories = true;
                continue;
            }

            if (containsIgnoreCase(trimmed, "AMPLIATIONS") || containsIgnoreCase(trimmed, "JOURNAL OFFICIEL")) {
                inSignatories = false;
            }

            if (inSignatories) {
                signatoriesText.append(trimmed).append("\n");
            }
        }

        // Suppose readTemplate et LangToolService.getCorrectedText existent
        String template = readTemplate("footer.txt");
        template = template.replace("[Cotonou/PORTO-NOVO]", cityName);
        template = template.replace("[TO_FILL_DATE]", signatoryDate);
        template = template.replace("[TO_FILL_SIGNATORIES]", signatoriesText.toString().trim());

        return LangToolService.getInstance().getCorrectedText(template);
    }

    public Set<Signataire> extractSignataires(String input) {
        if (input == null || input.isBlank())
            return new HashSet<>();

        Set<Signataire> signataires = new LinkedHashSet<>();
        StringBuilder currentRole = new StringBuilder();

        String[] lines = input.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || isGarbage(trimmed))
                continue;

            // 1. Tentative de matching avec le référentiel CSV
            Optional<Signataire> mapped = findInMapping(trimmed);

            if (mapped.isPresent()) {
                signataires.add(mapped.get());
                currentRole.setLength(0); // On privilégie le rôle officiel du CSV
            }
            // 2. Détection OCR classique si non trouvé dans le CSV
//            else if (looksLikeRole(trimmed)) {
//                if (!currentRole.isEmpty()) {
//                    signataires.add(new Signataire(
//                            normalizeName(trimmed),
//                            normalizeRole(currentRole.toString())));
//                    currentRole.setLength(0);
//                }
//            }
            // 3. Sinon, accumulation du rôle
            else {
                if (!currentRole.isEmpty())
                    currentRole.append(" ");
                currentRole.append(trimmed);
            }
        }
        return signataires;
    }

    private Optional<Signataire> findInMapping(String text) {
        String search = stripAccents(text.toUpperCase());
        for (Map.Entry<String, Signataire> entry : signatoriesMapping.entrySet()) {
            // Match flexible avec fuzzy matching basé sur n-grams
            if (fuzzyMatch(search, entry.getKey(),
                    Math.max(search.length(), entry.getKey().length()), 3, 3)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private String stripAccents(String s) {
        if (s == null)
            return "";
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    private boolean isGarbage(String line) {
        return line.equalsIgnoreCase("AMPLIATIONS") ||
                (line.matches(".*[0-9]{5,}.*") && !line.contains(" ")) ||
                startsWithIgnoreCase(line, "fait");
    }

    public String extractLawDate(LawSection lawSection) {
        String faitLine = extractFaitLine(lawSection.getFooter());
        String datePart = extractAfterFirstRegex(faitLine, "le|du");
        String dateRepaired = repairDatePart(datePart, lawSection);
        return extractFrenchDate(dateRepaired).toString();
    }

    // Méthode main pour le test
    public static void main(String[] args) {
        FooterTransImpl footerTrans = new FooterTransImpl();

        // Input exemple avec erreurs OCR
        String footerInput = "Par ie Président de la République,\n" +
                "Chef de l'Etat, Chef du Gouvernement,\n" +
                "Patrice TALON.-\n" +
                "Le Garde des Sceaux, Ministre de Le Ministre de l'Eau et des Mines,\n" +
                "la Justice et de la Légisiation,\n" +
                "M ue 45M/7\n" +
                "Séverin Maxime QUEN\n" + // Nom tronqué
                "\n" +
                "\n" +
                "AMPLIATIONS";

        System.out.println("--- Résultat de l'extraction ---");
        Set<Signataire> signataires = footerTrans.extractSignataires(footerInput);

        for (Signataire s : signataires) {
            System.out.println("Nom: " + s.getName());
            System.out.println("Rôle(s): " + s.getRole());
            System.out.println("-".repeat(100));
        }
    }
}
