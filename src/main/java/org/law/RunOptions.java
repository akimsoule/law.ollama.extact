package org.law;


import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.law.config.Config;

@Getter
@Builder
public class RunOptions {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunOptions.class);


    private final int minYear;
    private final int maxYear;
    private final boolean refreshOcr;
    private final boolean useBatchDeepSeek;

    public RunOptions(int minYear, int maxYear, boolean refreshOcr, boolean useBatchDeepSeek) {
        this.minYear = minYear;
        this.maxYear = maxYear;
        this.refreshOcr = refreshOcr;
        this.useBatchDeepSeek = useBatchDeepSeek;
    }

    public boolean includes(int year) {
        return year >= minYear && year <= maxYear;
    }

    public static RunOptions fromArgs(String[] args) {
        Config config = Config.getInstance();

        int minYear = config.getMinYear();
        int maxYear = config.getMaxYear();
        boolean refreshOcr = config.getRefreshOcr();
        boolean useBatchDeepSeek = false;

        for (String arg : args) {
            if (arg.startsWith("--minYear=")) {
                minYear = parseYearArg("minYear", arg.substring("--minYear=".length()), minYear);
            } else if (arg.startsWith("--maxYear=")) {
                maxYear = parseYearArg("maxYear", arg.substring("--maxYear=".length()), maxYear);
            } else if (arg.equals("--refreshOcr")) {
                refreshOcr = true;
            } else if (arg.equals("--batch-deepseek")) {
                useBatchDeepSeek = true;
            }
        }

        if (minYear > maxYear) {
            LOGGER.error("Intervalle d'annees invalide: minYear > maxYear. Utilisation par défaut.");
            return new RunOptions(config.getMinYear(), config.getMaxYear(), refreshOcr, false);
        }

        return new RunOptions(minYear, maxYear, refreshOcr, useBatchDeepSeek);
    }

    private static int parseYearArg(String key, String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.error("Argument --" + key + " invalide (" + value + "), valeur par defaut conservee: " + defaultValue);
            return defaultValue;
        }
    }
}
