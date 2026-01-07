package org.law.utils;

import org.law.service.section.Constant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public interface ExtractString {

    default String extractFromFirstDigit(String line) {
        String result = "";
        for (int i = 0; i < line.length(); i++) {
            if (Character.isDigit(line.charAt(i))) {
                result = line.substring(i).trim();
                break;
            }
        }
        return result;
    }

    default String extractAfterFirstRegex(String text, String regex) {
        String result = "";
        if (text == null || regex == null) {
            result = "";
        } else {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                result = text.substring(matcher.end()).trim();
            }
        }

        return result;
    }

    default String extractBeforeFirstRegex(String text, String regex) {
        String result = "";

        if (text == null || regex == null) {
            result = "";
        } else {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                result = text.substring(0, matcher.start()).trim();
            }
        }

        return result;
    }

    default LocalDate extractFrenchDate(String text) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
        return LocalDate.parse(text.toLowerCase(Locale.FRENCH), formatter);
    }

    default Optional<String> getNumberInStartV2(String line, int n) {
        if (line == null || line.isBlank() || n <= 0) {
            return Optional.empty();
        }

        int limit = Math.min(n, line.length());
        String prefix = line.substring(0, limit);

        String delimitersRegex = Stream.of(Constant.DELIMITERS_ALLOWED)
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");

        /*
         * - Capture : (\d+[a-z0-9]*|premier|un)
         *   - \d+[a-z0-9]* : 1, 3bis, 25w4
         *   - premier|un : mots-clés spécifiques
         * - Lookahead : stop avant espace, ponctuation ou délimiteur
         */
        Pattern pattern = Pattern.compile(
                "(?i).*?(\\d+[a-z0-9]*|premier|un)(?=\\s|[.:,-]|(?:" + delimitersRegex + ")|$)"
        );

        Matcher matcher = pattern.matcher(prefix);

        if (matcher.find()) {
            String result = matcher.group(1).toLowerCase();
            // Transformation optionnelle : convertir "premier" en "1"
            if (result.equals("premier") || result.equals("un")) {
                return Optional.of("1");
            }
            return Optional.of(result);
        }

        return Optional.empty();
    }





    default Optional<String> getNumberInStart(String line, int n) {
        if (line == null || line.isEmpty() || n <= 0) {
            return Optional.empty();
        }

        int longueur = line.length();
        int debutRecherche = Math.min(longueur, n);
        int indexPremierChiffre = -1;

        // 1. Chercher le premier chiffre dans les n premiers caractères
        for (int i = 0; i < debutRecherche; i++) {
            if (Character.isDigit(line.charAt(i))) {
                indexPremierChiffre = i;
                break;
            }
        }

        // Si aucun chiffre n'est trouvé dans les n caractères
        if (indexPremierChiffre == -1) {
            return Optional.empty();
        }

        // Si le nombre de caractères est inférieur à 25,
        // prendre le reste des caractères et remplacer les espaces par _
        if (longueur < n) {
            String number = line.replace(":", "").trim()
                    .substring(indexPremierChiffre);
            return Optional.of(number);
        }

        // 2. Concaténer à partir de ce chiffre jusqu'à un délimiteur complet
        // mais avant tout si sur les 25 premiers caractères, le délimiteur n'est pas
        // trouvé, utiliser " " comme délimiteur.
        int limit = Math.min(longueur, indexPremierChiffre + n);
        int indexFin = -1;

        // Chercher d'abord un délimiteur complet dans la limite
        for (int i = indexPremierChiffre; i < limit; i++) {
            for (String delimiter : Constant.DELIMITERS_ALLOWED) {
                if (line.startsWith(delimiter, i)) {
                    indexFin = i;
                    break;
                }
            }
            if (indexFin != -1)
                break;
        }

        // Si aucun délimiteur trouvé, chercher un espace
        if (indexFin == -1) {
            for (int i = indexPremierChiffre; i < limit; i++) {
                if (line.charAt(i) == ' ') {
                    indexFin = i;
                    break;
                }
            }
        }

        // Si rien trouvé, prendre jusqu'à la limite
        if (indexFin == -1) {
            indexFin = limit;
        }

        String number = line.substring(indexPremierChiffre, indexFin).trim();

        return Optional.of(number);
    }

}
