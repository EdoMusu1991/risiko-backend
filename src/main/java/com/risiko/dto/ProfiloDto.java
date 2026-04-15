package com.risiko.dto;

import java.util.List;

public record ProfiloDto(
        String username,
        int partiteTotali,
        int partiteCorrette,
        int partiteSbagliate,
        int punteggioTotale,

        // Per difficoltà — nomi che combaciano col frontend Angular
        int totaleFacile,
        int totaleMedia,        // "medio" nel frontend cerca totaleMedio → cambia qui o nel TS
        int totaleDifficile,
        int corretteFacile,
        int corretteMedio,
        int corretteDifficile,

        int streakCorrente,
        List<PartitaRistoDto> ultimePartite   // ← chiave usata nel frontend
) {}