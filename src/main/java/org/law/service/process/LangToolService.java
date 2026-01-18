package org.law.service.process;

import com.google.common.base.Strings;
import org.languagetool.JLanguageTool;
import org.languagetool.language.French;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;
import org.law.utils.TransString;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service de correction linguistique en Singleton pour traitement Batch.
 */
public class LangToolService implements TransString {

    private static final boolean CORRECTION_ACTIVE = true;
    private static final String TESSDATA_PATH = "src/main/resources/tessdata";

    // Instance unique du Singleton via holder pattern pour thread-safety
    private static class Holder {
        static final LangToolService INSTANCE = new LangToolService();
    }

    // ThreadLocal pour isoler les instances JLanguageTool par thread
    private final ThreadLocal<JLanguageTool> languageToolThreadLocal;
    private final List<String> dictionaryTokens;

    private LangToolService() {
        this.dictionaryTokens = loadDictionaryTokens();
        // Initialisation du ThreadLocal avec la configuration spécifique
        this.languageToolThreadLocal = ThreadLocal.withInitial(() -> {
            JLanguageTool tool = new JLanguageTool(new French());
            applyDictionaryToInstance(tool);
            return tool;
        });
    }

    public static LangToolService getInstance() {
        return Holder.INSTANCE;
    }

    private List<String> loadDictionaryTokens() {
        List<String> tokens = new ArrayList<>();
        List<String> targetFiles = Arrays.asList(
                "fra.user-minister-pattern",
                "fra.user-president-pattern",
                "fra.user-region-words");
        try {
            Path tessDataDir = Path.of(TESSDATA_PATH);
            if (Files.isDirectory(tessDataDir)) {
                try (var paths = Files.list(tessDataDir)) {
                    tokens = paths
                            .filter(p -> targetFiles.contains(p.getFileName().toString()))
                            .flatMap(p -> {
                                try {
                                    return Files.readAllLines(p).stream()
                                            .flatMap(line -> Arrays.stream(line.split(" ")))
                                            .filter(s -> !Strings.isNullOrEmpty(s));
                                } catch (IOException e) {
                                    return Collections.<String>emptyList().stream();
                                }
                            })
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                }
            }
        } catch (IOException e) {
            System.err.println("Erreur chargement dictionnaire : " + e.getMessage());
        }
        return tokens;
    }

    private void applyDictionaryToInstance(JLanguageTool tool) {
        for (Rule rule : tool.getAllActiveRules()) {
            if (rule instanceof SpellingCheckRule spellingcheckrule) {
                spellingcheckrule.acceptPhrases(dictionaryTokens);
            }
        }
    }

    public String getCorrectedText(String text) throws IOException {
        // String corrected = correctWordWithDict(text);
        // text = corrected;
        if (!CORRECTION_ACTIVE) {
            return text;
        }

        // Récupère l'instance dédiée au thread actuel
        JLanguageTool tool = languageToolThreadLocal.get();
        List<RuleMatch> matches = tool.check(text);

        // Correction de la fin vers le début pour préserver les indices
        List<RuleMatch> sortedMatches = new ArrayList<>(matches);
        sortedMatches.sort(Comparator.comparingInt(RuleMatch::getFromPos).reversed());

        StringBuilder sb = new StringBuilder(text);
        for (RuleMatch match : sortedMatches) {
            if (!match.getSuggestedReplacements().isEmpty()) {
                String suggestion = match.getSuggestedReplacements().get(0);

                // Récupère le mot original
                String originalWord = text.substring(match.getFromPos(), match.getToPos());

                // Évite la correction si le mot commence par une majuscule (mot propre)
                if (originalWord.isEmpty() || Character.isUpperCase(originalWord.charAt(0))) {
                    continue;
                }

                // Sécurité : évite les remplacements trop courts ou vides
                if (suggestion != null && (match.getToPos() - match.getFromPos() > 2)) {
                    System.out.println("Remplacement de " + originalWord +
                            " par " + suggestion);
                    sb.replace(match.getFromPos(), match.getToPos(), suggestion);
                }
            }
        }
        return sb.toString();
    }
}