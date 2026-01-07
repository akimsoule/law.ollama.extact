package org.law.service.main;

import org.law.model.LawSection;
import org.law.service.section.BodyTransImpl;
import org.law.service.section.FooterTransImpl;
import org.law.service.section.HeaderTransImpl;
import org.law.utils.BoolString;
import org.law.utils.ExtractString;
import org.law.utils.TransString;

public class ExtractorService implements BoolString, ExtractString, TransString {

    private final String fullContent;
    private final HeaderTransImpl headerTrans;
    private final BodyTransImpl bodyTrans;
    private final FooterTransImpl footerTrans;

    public ExtractorService(String fullContent) {
        this.fullContent = fullContent;
        headerTrans = new HeaderTransImpl();
        bodyTrans = new BodyTransImpl();
        footerTrans = new FooterTransImpl();
    }

    public LawSection extractLawSection() throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("Début de l'extraction des sections...");

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
        System.out.println("Temps pour nettoyage du header : " + (endCleanHeader - startCleanHeader) + " ms");

        long startCleanBody = System.currentTimeMillis();
        lawSection.setBody(bodyTrans.cleanUpBody(lawSection.getBody()));
        long endCleanBody = System.currentTimeMillis();
        System.out.println("Temps pour nettoyage du body : " + (endCleanBody - startCleanBody) + " ms");

        long startCleanFooter = System.currentTimeMillis();
        lawSection.setFooter(footerTrans.cleanUpFooter(lawSection.getFooter()));
        long endCleanFooter = System.currentTimeMillis();
        System.out.println("Temps pour nettoyage du footer : " + (endCleanFooter - startCleanFooter) + " ms");

        long endTime = System.currentTimeMillis();
        System.out.println("Temps total pour extractLawSection : " + (endTime - startTime) + " ms");

        return lawSection;
    }

}
