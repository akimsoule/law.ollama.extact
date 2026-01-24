package org.law.model;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class Article {

    private int index;
    private String number;
    private String content;

}
