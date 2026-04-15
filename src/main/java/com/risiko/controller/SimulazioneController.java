package com.risiko.controller;

import com.risiko.dto.*;
import com.risiko.service.SimulazioneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/simulazione")
public class SimulazioneController {

    @Autowired private SimulazioneService simulazioneService;

    /** POST /api/simulazione/nuova — genera simulazione con difficoltà scelta */
    @PostMapping("/nuova")
    public ResponseEntity<NuovaSimulazioneDto> nuovaSimulazione(
            @RequestBody(required = false) NuovaSimulazioneRequest req) {
        if (req == null) req = new NuovaSimulazioneRequest("MEDIO");
        return ResponseEntity.ok(simulazioneService.generaSimulazione(req));
    }

    /** POST /api/simulazione/{id}/hint/{colore} — indizio sull'obiettivo, costo -10pt */
    @PostMapping("/{id}/hint/{colore}")
    public ResponseEntity<HintDto> richiediHint(
            @PathVariable Long id, @PathVariable String colore) {
        String c = colore.toUpperCase();
        if (!java.util.Set.of("BLU", "ROSSO", "VERDE", "GIALLO").contains(c))
            return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(simulazioneService.getHint(id, c));
    }

    /** POST /api/simulazione/{id}/indovina — valuta le ipotesi e salva il risultato */
    @PostMapping("/{id}/indovina")
    public ResponseEntity<RisultatoSimulazioneDto> indovina(
            @PathVariable Long id,
            @RequestBody IndovinaObiettiviRequest req,
            Authentication auth) {
        String userId = auth != null ? auth.getName() : null;
        return ResponseEntity.ok(
            simulazioneService.valutaRisposta(id, req, userId, req.difficolta()));
    }

    /** GET /api/simulazione/stats — statistiche aggregate dell'utente */
    @GetMapping("/stats")
    public ResponseEntity<StatSimulazioneDto> stats(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(simulazioneService.getStats(auth.getName()));
    }

    /** GET /api/simulazione/storico — ultimi 20 risultati dell'utente */
    @GetMapping("/storico")
    public ResponseEntity<List<SimulazioneRiepilogoDto>> storico(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(simulazioneService.getStorico(auth.getName()));
    }

    /** GET /api/simulazione/classifica — top 10 globale (1 riga per utente) */
    @GetMapping("/classifica")
    public ResponseEntity<List<ClassificaSimulazioneDto>> classifica() {
        return ResponseEntity.ok(simulazioneService.getClassifica());
    }
}
