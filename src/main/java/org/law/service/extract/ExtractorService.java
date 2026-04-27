package org.law.service.extract;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.law.model.LawSection;
import org.law.service.parse.BodyParser;
import org.law.service.parse.FooterParser;
import org.law.service.parse.HeaderParser;
import org.law.utils.BoolString;
import org.law.utils.ExtractString;
import org.law.utils.TransString;

public class ExtractorService implements BoolString, ExtractString, TransString {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractorService.class);


    private final String fullContent;
    private final HeaderParser headerTrans;
    private final BodyParser bodyTrans;
    private final FooterParser footerTrans;

    public ExtractorService(String fullContent) {
        this.fullContent = fullContent;
        headerTrans = new HeaderParser();
        bodyTrans = new BodyParser();
        footerTrans = new FooterParser();
    }

    public LawSection extractLawSection() throws Exception {
        long startTime = System.currentTimeMillis();
        LOGGER.info("Début de l'extraction des sections...");

        LawSection lawSection = LawSection.builder().build();

        boolean headFound = true;
        boolean bodyFound = false;
        boolean footerFound = false;

        String[] lines = fullContent.split("\n");
        for (String line : lines) {
            if (headFound && stratLookLikeArticle(line)) {
                headFound = false;
                bodyFound = true;
            }
            if (!headFound && bodyFound && startsWithIgnoreCase(line, "Fait à")) {
                footerFound = true;
                bodyFound = false;
            }
            if (headFound) {
                lawSection.setHeader(lawSection.getHeader() + "\n" + line);
            }
            if (bodyFound) {
                lawSection.setBody(lawSection.getBody() + "\n" + line);
            }
            if (footerFound) {
                lawSection.setFooter(lawSection.getFooter() + "\n" + line);
            }

        }

        long startCleanHeader = System.currentTimeMillis();
        lawSection.setHeader(headerTrans.cleanUpHeader(lawSection.getHeader()));
        long endCleanHeader = System.currentTimeMillis();
        LOGGER.info("Temps pour nettoyage du header : " + (endCleanHeader - startCleanHeader) + " ms");

        long startCleanBody = System.currentTimeMillis();
        lawSection.setBody(bodyTrans.cleanUpBody(lawSection.getBody()));
        long endCleanBody = System.currentTimeMillis();
        LOGGER.info("Temps pour nettoyage du body : " + (endCleanBody - startCleanBody) + " ms");

        long startCleanFooter = System.currentTimeMillis();
        lawSection.setFooter(footerTrans.cleanUpFooter(lawSection.getFooter()));
        long endCleanFooter = System.currentTimeMillis();
        LOGGER.info("Temps pour nettoyage du footer : " + (endCleanFooter - startCleanFooter) + " ms");

        long endTime = System.currentTimeMillis();
        LOGGER.info("Temps total pour extractLawSection : " + (endTime - startTime) + " ms");

        return lawSection;
    }

}
