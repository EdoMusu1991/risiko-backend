package com.risiko.dto;

import java.util.List;

public record RisultatoSimulazioneDto(
        int corretti,
        int punteggio,
        int hintUsati,
        int decurtaHint,
        List<DettaglioRisposta> dettagli
) {}