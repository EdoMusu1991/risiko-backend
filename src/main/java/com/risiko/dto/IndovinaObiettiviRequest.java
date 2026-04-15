package com.risiko.dto;

public record IndovinaObiettiviRequest(
        int blu, int rosso, int verde, int giallo,
        String difficolta
) {}