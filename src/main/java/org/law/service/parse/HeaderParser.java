package org.law.service.parse;

import org.law.model.LawSection;
import org.law.service.process.LangToolService;
import org.law.utils.BoolString;
import org.law.utils.ExtractString;
import org.law.utils.TemplateReader;
import org.law.utils.TransString;

import static org.law.service.parse.Constant.*;

public class HeaderParser implements BoolString, ExtractString, TransString, TemplateReader {

    public String cleanUpHeader(String header) throws Exception {
        if (header == null)
            return "";

        String republicName = containsIgnoreCase(header, "DAHOMEY") ? "DAHOMEY" : "BENIN";

        StringBuilder lawNumberRaw = new StringBuilder();
        StringBuilder lawObjectRaw = new StringBuilder();
        StringBuilder lawDelibRaw = new StringBuilder();
        StringBuilder lawPresRaw = new StringBuilder();

        String[] headerSplit = header.split("LOI");
        if (headerSplit.length > 1) {
            header = headerSplit[0] + "\n" + "LOI" + headerSplit[1];
        }
        header = header.replace(" , ", "");

        String[] lines = header.split("\n");

        // On utilise un état simple pour savoir ce qu'on capture
        String state = "NONE";

        for (String line : lines) {
            String cleanLine = line.trim();
            if (cleanLine.length() <= 2)
                continue; // Ignore le bruit (D, z, X, ')

            // Transition : Détection du numéro de Loi
            if (startsWithIgnoreCase(cleanLine, "LOI")) {
                state = "NUMBER";
            }
            // Transition : Détection de l'objet (Portant, abrogeant, relative...)
            else if (START_OBJECT_LIST.stream().anyMatch(start -> startsWithIgnoreCase(cleanLine, start))) {
                state = "OBJECT";
            }
            // Transition : Détection de la délibération (Assemblée nationale)
            else if (ASSEMBLEE_NATIONALE.stream().anyMatch(token -> containTarget(cleanLine, token, 3))) {
                state = "DELIBERATION";
            }
            // Transition : Détection du président (Le Président promulgue)
            else if (containsIgnoreCase(cleanLine, "promulgue")) {
                state = "PRESIDENT";
            }

            // Accumulation selon l'état
            switch (state) {
                case "NUMBER" -> lawNumberRaw.append(cleanLine).append(" ");
                case "OBJECT" -> lawObjectRaw.append(cleanLine).append(" ");
                case "DELIBERATION" -> lawDelibRaw.append(cleanLine).append(" ");
                case "PRESIDENT" -> lawPresRaw.append(cleanLine).append(" ");
            }
        }

        // --- REPARATION DES DONNEES ---
        // Utilise tes méthodes de réparation sur le numéro de loi (qui contient la
        // date)
        String rawNumberStr = lawNumberRaw.toString().trim();

        String template = readTemplate("head.txt");
        template = template.replace("[BENIN/DAHOMEY]", republicName);

        // Remplissage avec nettoyage
        template = template.replace("[TO_FILL_LAW_NUMBER]", extractFromFirstDigit(rawNumberStr));
        template = template.replace("[TO_FILL_OBJECT]", toSingleLine(lawObjectRaw.toString()));
        template = template.replace("[TO_FILL_ASSEMBLY]",
                extractAfterFirstRegex(toSingleLine(lawDelibRaw.toString()), "nationale"));
        template = template.replace("[TO_FILL_PRESIDENT]",
                extractBeforeFirstRegex(toSingleLine(lawPresRaw.toString()), "promulgue"));

        // Correction orthographique finale
        return LangToolService.getInstance().getCorrectedText(template);
    }

    public String extractLawNumber(String header) {
        // Logique similaire à cleanUpHeader
        StringBuilder lawNumber = new StringBuilder();
        boolean lawNumberFound = false;
        String[] lines = header.split("\n");
        for (String line : lines) {
            if (startsWithIgnoreCase(line, "LOI")) {
                lawNumberFound = true;
            }
            if (lawNumberFound &&
                    START_OBJECT_LIST.stream().anyMatch(start -> startsWithIgnoreCase(line, start))) {
                break;
            }
            if (lawNumberFound) {
                lawNumber.append(line).append("\n");
            }
        }
        return extractFromFirstDigit(toSingleLine(lawNumber.toString()));
    }

    public String extractLawObject(String header) {
        StringBuilder lawObject = new StringBuilder();
        boolean lawObjectFound = false;
        String[] lines = header.split("\n");

        for (String line : lines) {
            if (START_OBJECT_LIST.stream().anyMatch(start -> startsWithIgnoreCase(line, start))) {
                lawObjectFound = true;
            }
            if (lawObjectFound && ASSEMBLEE_NATIONALE.stream()
                    .anyMatch(token -> containsIgnoreCase(line, token))) {
                break;
            }
            if (lawObjectFound) {
                lawObject.append(line).append("\n");
            }
        }

        return toSingleLine(lawObject.toString());
    }

    public String extractLawDate(LawSection lawSection) {
        String lawNumberText = extractLawNumber(lawSection.getHeader());
        // Assuming the date is after "DU" in the law number
        String datePart = extractAfterFirstRegex(lawNumberText, "DU");
        String dateRepaired = repairDatePart(datePart, lawSection);
        return extractFrenchDate(dateRepaired).toString(); // or return as string
    }

    public static void main(String[] args) throws Exception {
        HeaderParser headerTrans = new HeaderParser();
        String input = """
                AECK/ICG

                RPUBLIQUE DU BENIN
                Fraternité-Justice-Travail

                LOI N° 2025 - 08 DU 24 MARS 2025

                portant modification de la loi n° 2022-06 du 27 juin
                2022 portant staiut des magistrats de la Cour des
                comptes.

                L'Assemblée nationale a délibéré et adopté en sa séance du 12 mars
                2025

                Le Président de la République promulgue la loi dont la teneur suit""";

        String cleanupHeader = headerTrans.cleanUpHeader(input);
        String lawNumber = headerTrans.extractLawNumber(cleanupHeader);
        String lawObjet = headerTrans.extractLawObject(cleanupHeader);
        String lawDate = headerTrans.extractLawDate(LawSection.builder().header(input).build());

        System.out.println("-".repeat(100));
        System.out.println("Cleaned Up Header:");
        System.out.println(cleanupHeader);
        System.out.println("-".repeat(100));
        System.out.println("-".repeat(100));
        System.out.println("Law Number:");
        System.out.println(lawNumber);
        System.out.println("-".repeat(100));
        System.out.println("-".repeat(100));
        System.out.println("Law Object: \n" + lawObjet);
        System.out.println("Law Date: \n" + lawDate);
        System.out.println("-".repeat(100));

    }

}
