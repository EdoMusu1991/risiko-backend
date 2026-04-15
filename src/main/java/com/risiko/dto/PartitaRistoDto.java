package com.risiko.dto;

public record PartitaRistoDto(
        Long id,
        boolean corretta,
        int difficolta,
        int punteggio,
        String dataOra
) {}