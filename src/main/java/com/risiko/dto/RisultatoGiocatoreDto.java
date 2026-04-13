package com.risiko.dto;

import java.util.List;

/** Risultato per un singolo giocatore */
public record RisultatoGiocatoreDto(
    String colore,
    boolean corretta,
    List<Integer> obiettiviCompatibili,   // tutti gli ID compatibili
    List<String> nomiCompatibili,         // nomi degli obiettivi compatibili
    boolean nessunObiettivoCorretto,      // true se la risposta corretta era "nessun obiettivo"
    int punteggio,
    String spiegazione
) {}
