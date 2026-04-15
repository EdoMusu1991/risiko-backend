package com.risiko.dto;

public record ClassificaSimulazioneDto(
        String userId,
        int    punteggio,
        int    corretti,
        String difficolta,
        String data
) {}