package com.risiko.controller;

import com.risiko.dto.ImpostazioniDto;
import com.risiko.service.ImpostazioniService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller per la sezione Impostazioni.
 */
@RestController
@RequestMapping("/api/impostazioni")
@CrossOrigin(origins = "*")
public class ImpostazioniController {

    private final ImpostazioniService service;

    public ImpostazioniController(ImpostazioniService service) {
        this.service = service;
    }

    /**
     * GET /api/impostazioni/{userId}
     * Recupera le preferenze dell'utente.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<ImpostazioniDto> getImpostazioni(@PathVariable String userId) {
        return ResponseEntity.ok(service.getImpostazioni(userId));
    }

    /**
     * PUT /api/impostazioni/{userId}
     * Salva le preferenze dell'utente.
     *
     * Body JSON:
     * {
     *   "difficolta": 2,
     *   "tema": "dark",
     *   "suggerimentiAttivi": true,
     *   "highlightMappa": true
     * }
     */
    @PutMapping("/{userId}")
    public ResponseEntity<ImpostazioniDto> salvaImpostazioni(
            @PathVariable String userId,
            @RequestBody ImpostazioniDto dto) {
        return ResponseEntity.ok(service.salvaImpostazioni(userId, dto));
    }
}
