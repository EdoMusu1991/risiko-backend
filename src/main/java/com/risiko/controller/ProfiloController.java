package com.risiko.controller;

import com.risiko.dto.ClassificaDto;
import com.risiko.dto.PartitaRequest;
import com.risiko.dto.ProfiloDto;
import com.risiko.service.PartitaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProfiloController {

    private final PartitaService partitaService;

    public ProfiloController(PartitaService partitaService) {
        this.partitaService = partitaService;
    }

    /**
     * GET /api/profilo
     * Restituisce il profilo dell'utente loggato con le sue statistiche.
     * Richiede token JWT nell'header Authorization.
     */
    @GetMapping("/profilo")
    public ResponseEntity<ProfiloDto> getProfilo(Authentication auth) {
        return ResponseEntity.ok(partitaService.getProfilo(auth.getName()));
    }

    /**
     * POST /api/partita
     * Salva una partita giocata dall'utente loggato.
     * Body: { "difficolta": 1, "corretta": true, "punteggio": 10, "obiettivoId": 3 }
     */
    @PostMapping("/partita")
    public ResponseEntity<?> salvaPartita(@RequestBody PartitaRequest req,
                                           Authentication auth) {
        partitaService.salva(auth.getName(), req);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * GET /api/classifica
     * Classifica pubblica (non richiede autenticazione).
     */
    @GetMapping("/classifica")
    public ResponseEntity<List<ClassificaDto>> getClassifica() {
        return ResponseEntity.ok(partitaService.getClassifica());
    }
}
