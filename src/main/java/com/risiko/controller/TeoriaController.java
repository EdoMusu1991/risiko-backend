package com.risiko.controller;

import com.risiko.dto.ObiettivoDto;
import com.risiko.service.TeoriaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller per la sezione Teoria.
 * Espone i 16 obiettivi del Risiko Torneo.
 */
@RestController
@RequestMapping("/api/obiettivi")
@CrossOrigin(origins = "*")
public class TeoriaController {

    private final TeoriaService service;

    public TeoriaController(TeoriaService service) {
        this.service = service;
    }

    /**
     * GET /api/obiettivi
     * Restituisce la lista completa dei 16 obiettivi.
     */
    @GetMapping
    public ResponseEntity<List<ObiettivoDto>> getTutti() {
        return ResponseEntity.ok(service.getTutti());
    }

    /**
     * GET /api/obiettivi/{id}
     * Restituisce il dettaglio di un singolo obiettivo.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ObiettivoDto> getById(@PathVariable int id) {
        return service.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
