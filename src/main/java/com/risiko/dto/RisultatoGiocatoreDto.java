package com.risiko.dto;

import java.util.List;

/** Risultato per un singolo giocatore */
public record RisultatoGiocatoreDto(
        String colore,
        boolean corretta,
        List<Integer> obiettiviCompatibili,
        List<String> nomiCompatibili,
        boolean nessunObiettivoCorretto,
        int punteggio,
        String spiegazione,
        List<Integer> obiettiviSceltiUtente  // ← NUOVO
) {}
