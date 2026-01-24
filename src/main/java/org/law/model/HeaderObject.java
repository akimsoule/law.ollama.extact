package org.law.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeaderObject implements Serializable {

    @Builder.Default
    private String republic = "";

    @Builder.Default
    private String number = "";

    @Builder.Default
    private String object = "";

    @Builder.Default
    private String deliberation = "";

    @Builder.Default
    private String president = "";

}
