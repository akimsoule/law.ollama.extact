package org.law.model;

import lombok.Builder;
import lombok.Data;
import org.json.JSONObject;

import java.util.List;

@Data
@Builder
public class LawSection {

    @Builder.Default
    private String header = "";
    @Builder.Default
    private String body = "";
    @Builder.Default
    private String footer = "";

    @Builder.Default
    private String year = "";

    @Builder.Default
    private JSONObject jsonObject = new JSONObject();

    public String getFullText() {
        return String.join("\n\n", List.of(header, body, footer));
    }

}
