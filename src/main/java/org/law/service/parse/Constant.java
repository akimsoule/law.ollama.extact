package org.law.service.parse;

import java.util.Arrays;
import java.util.List;

public class Constant {

        public static final String ASSEMBLEE = "assemblée";
        public static final String PRESIDENT = "président";
        public static final String PROMULGUE = "promulgue";

        public static final List<String> ASSEMBLEE_NATIONALE = Arrays.asList(
                        "assemblee",
                        "nationale");
        public static final List<String> START_OBJECT_LIST = Arrays.asList(
                        "approuvent",
                        "fixant",
                        "Définissant",
                        "relative",
                        "abrogeant",
                        "portant",
                        "sur",
                        "modifiant");

        public static String[] DELIMITERS_ALLOWED = { ".-", "- ", " -", ". ", ", ", ":" };

        public static final List<String> START_FAIT = Arrays.asList(
                        "Fait à Cotonou",
                        "Fait à Porto-Novo");

        public static final List<String> MONTH_ALLOWED = List.of(
                        "janvier",
                        "février",
                        "mars",
                        "avril",
                        "mai",
                        "juin",
                        "juillet",
                        "août",
                        "septembre",
                        "octobre",
                        "novembre",
                        "décembre");

}
