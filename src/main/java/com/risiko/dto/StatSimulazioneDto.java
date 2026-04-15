package com.risiko.dto;

public record StatSimulazioneDto(
        int    totaleSimulazioni,
        int    totaleCorretti4su4,
        int    punteggioMassimo,
        double mediaCorretti
) {}