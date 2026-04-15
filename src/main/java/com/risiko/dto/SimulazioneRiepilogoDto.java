package com.risiko.dto;

public record SimulazioneRiepilogoDto(
        int    punteggio,
        int    corretti,
        String difficolta,
        int    hintUsati,
        String giocataIl
) {}