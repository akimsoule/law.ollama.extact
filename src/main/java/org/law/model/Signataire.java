package org.law.model;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class Signataire {

    private final String name;
    private final String role;

}
