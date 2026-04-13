package com.risiko.dto;

import java.util.List;

/** Risultato conferma completa della plancia */
public record RisultatoPlanciaDto(
    List<RisultatoGiocatoreDto> risultati,
    boolean tuttiCorretti,
    int punteggioTotale
) {}
