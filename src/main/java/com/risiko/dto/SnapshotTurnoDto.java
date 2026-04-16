package com.risiko.dto;

import java.util.List;
import java.util.Map;

public record SnapshotTurnoDto(
        int                            turno,
        Map<String, TerritoryStateDto> mappa,
        List<String>                   logAzioni,
        List<AttaccoEventoDto>         attacchi,
        Map<String, StatoCarteDto>     statoCarte,
        List<EventoCartinaDto>         cartine,
        List<EventoTrisDto>            tris,
        String                         fase,      //  "PIAZZAMENTO","TRIS","RINFORZI","ATTACCHI","SPOSTAMENTO","RIEPILOGO"
        String                         giocatore  //  "BLU","ROSSO","VERDE","GIALLO" o null
) {}