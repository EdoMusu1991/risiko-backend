package com.risiko.controller;

import com.risiko.dto.ClassificaDto;
import com.risiko.dto.PartitaRequest;
import com.risiko.dto.ProfiloDto;
import com.risiko.service.PartitaService;
import com.risiko.service.UtenteService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProfiloController {

    private final PartitaService partitaService;
    private final UtenteService utenteService;

    public ProfiloController(PartitaService partitaService, UtenteService utenteService) {
        this.partitaService = partitaService;
        this.utenteService = utenteService;
    }

    /** GET /api/profilo */
    @GetMapping("/profilo")
    public ResponseEntity<ProfiloDto> getProfilo(Authentication auth) {
        return ResponseEntity.ok(partitaService.getProfilo(auth.getName()));
    }

    /** POST /api/partita */
    @PostMapping("/partita")
    public ResponseEntity<?> salvaPartita(@RequestBody PartitaRequest req,
                                          Authentication auth) {
        partitaService.salva(auth.getName(), req);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** GET /api/classifica */
    @GetMapping("/classifica")
    public ResponseEntity<List<ClassificaDto>> getClassifica() {
        return ResponseEntity.ok(partitaService.getClassifica());
    }

    /** GET /api/partite — storico ultime 20 partite */
    @GetMapping("/partite")
    public ResponseEntity<?> getStorico(Authentication auth) {
        return ResponseEntity.ok(partitaService.getStorico(auth.getName()));
    }

    /** GET /api/stats — statistiche dettagliate per difficoltà */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(Authentication auth) {
        return ResponseEntity.ok(partitaService.getStats(auth.getName()));
    }

    /** PUT /api/profilo/avatar — aggiorna avatar */
    @PutMapping("/profilo/avatar")
    public ResponseEntity<?> cambiaAvatar(@RequestBody Map<String, String> body,
                                          Authentication auth) {
        String newAvatar = body.get("avatar");
        if (newAvatar == null || newAvatar.isBlank())
            return ResponseEntity.badRequest().body(Map.of("errore", "Avatar non valido"));
        utenteService.cambiaAvatar(auth.getName(), newAvatar);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** PUT /api/profilo/password — cambia password */
    @PutMapping("/profilo/password")
    public ResponseEntity<?> cambiaPassword(@RequestBody Map<String, String> body,
                                            Authentication auth) {
        String vecchia = body.get("vecchia");
        String nuova   = body.get("nuova");
        if (vecchia == null || nuova == null || nuova.length() < 4)
            return ResponseEntity.badRequest().body(Map.of("errore", "Dati non validi"));
        try {
            utenteService.cambiaPassword(auth.getName(), vecchia, nuova);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("errore", e.getMessage()));
        }
    }
}
