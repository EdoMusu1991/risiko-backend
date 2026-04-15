package com.risiko.controller;

import com.risiko.dto.PlanciaDto;
import com.risiko.model.*;
import com.risiko.repository.*;
import com.risiko.service.QuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"http://localhost:4200", "https://risiko-backend-production.up.railway.app"})
@RestController
@RequestMapping("/api/giornaliera")
public class SfidaGiornalieraController {

    @Autowired
    private SfidaGiornalieraRepository sfidaRepository;

    @Autowired
    private RisultatoGiornalieroRepository risultatoRepository;

    @Autowired
    private UtenteRepository utenteRepository;

    @Autowired
    private QuizService quizService;

    // ── Ottieni sfida del giorno ──────────────────────────────────────────────
    @GetMapping("/oggi")
    public ResponseEntity<?> getSfidaOggi(Authentication auth) {
        LocalDate oggi = LocalDate.now();

        // Ottieni o crea sfida del giorno
        SfidaGiornaliera sfida = sfidaRepository.findByGiorno(oggi)
            .orElseGet(() -> {
                // Crea sfida per oggi con seed basato sulla data
                long seed = oggi.toEpochDay();
                int diff = (int)(seed % 3) + 1; // rotazione 1,2,3
                SfidaGiornaliera nuova = new SfidaGiornaliera(oggi, seed, diff);
                return sfidaRepository.save(nuova);
            });

        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        boolean giaGiocata = utente != null && risultatoRepository.existsByUtenteAndGiorno(utente, oggi);

        Map<String, Object> risposta = new HashMap<>();
        risposta.put("giorno", oggi.toString());
        risposta.put("difficolta", sfida.getDifficolta());
        risposta.put("giaGiocata", giaGiocata);
        risposta.put("seed", sfida.getSeed());

        // Se già giocata, includi il risultato
        if (giaGiocata && utente != null) {
            risultatoRepository.findByUtenteAndGiorno(utente, oggi).ifPresent(r -> {
                risposta.put("punteggio", r.getPunteggio());
                risposta.put("corrette", r.getCorrette());
                risposta.put("tuttiCorretti", r.isTuttiCorretti());
                risposta.put("tempoSecondi", r.getTempoSecondi());
            });
        }

        // Classifica del giorno
        List<RisultatoGiornaliero> classifica = risultatoRepository
            .findByGiornoOrderByPunteggioDescTempoSecondiAsc(oggi);

        List<Map<String, Object>> classificaDto = classifica.stream().limit(10).map(r -> {
            Map<String, Object> row = new HashMap<>();
            row.put("username", r.getUtente().getUsername());
            row.put("punteggio", r.getPunteggio());
            row.put("corrette", r.getCorrette());
            row.put("tuttiCorretti", r.isTuttiCorretti());
            row.put("tempoSecondi", r.getTempoSecondi());
            return row;
        }).collect(Collectors.toList());

        risposta.put("classifica", classificaDto);
        return ResponseEntity.ok(risposta);
    }

    // ── Avvia sessioni per sfida giornaliera ─────────────────────────────────
    @PostMapping("/inizia")
    public ResponseEntity<?> iniziaSfida(Authentication auth) {
        LocalDate oggi = LocalDate.now();
        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        if (utente == null) return ResponseEntity.notFound().build();

        if (risultatoRepository.existsByUtenteAndGiorno(utente, oggi)) {
            return ResponseEntity.badRequest().body(Map.of("errore", "Hai già giocato la sfida di oggi!"));
        }

        SfidaGiornaliera sfida = sfidaRepository.findByGiorno(oggi)
                .orElseThrow(() -> new RuntimeException("Sfida non trovata"));

        PlanciaDto plancia = quizService.generaPlancia(sfida.getDifficolta());
        return ResponseEntity.ok(plancia);
    }

    // ── Salva risultato sfida giornaliera ─────────────────────────────────────
    @PostMapping("/risultato")
    public ResponseEntity<?> salvaRisultato(Authentication auth,
                                             @RequestBody Map<String, Object> body) {
        LocalDate oggi = LocalDate.now();
        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        if (utente == null) return ResponseEntity.notFound().build();

        if (risultatoRepository.existsByUtenteAndGiorno(utente, oggi)) {
            return ResponseEntity.badRequest().body(Map.of("errore", "Già registrato"));
        }

        int punteggio    = (int) body.getOrDefault("punteggio", 0);
        int corrette     = (int) body.getOrDefault("corrette", 0);
        boolean tutti    = (boolean) body.getOrDefault("tuttiCorretti", false);
        int tempo        = (int) body.getOrDefault("tempoSecondi", 0);

        RisultatoGiornaliero risultato = new RisultatoGiornaliero(
            utente, oggi, punteggio, corrette, tutti, tempo);
        risultatoRepository.save(risultato);

        // Classifica aggiornata
        List<RisultatoGiornaliero> classifica = risultatoRepository
            .findByGiornoOrderByPunteggioDescTempoSecondiAsc(oggi);

        int posizione = classifica.stream()
            .map(r -> r.getUtente().getId())
            .collect(Collectors.toList())
            .indexOf(utente.getId()) + 1;

        return ResponseEntity.ok(Map.of(
            "salvato", true,
            "posizione", posizione,
            "totalePartecipanti", classifica.size()
        ));
    }

    // ── Classifica settimanale ────────────────────────────────────────────────
    @GetMapping("/classifica-settimanale")
    public ResponseEntity<?> classificaSettimanale() {
        LocalDate startDate = LocalDate.now().minusDays(7);
        List<Object[]> rows = risultatoRepository.classificaSettimanale(startDate);

        List<Map<String, Object>> risultato = rows.stream().limit(20).map(row -> {
            Utente u = (Utente) row[0];
            Long tot = (Long) row[1];
            Map<String, Object> r = new HashMap<>();
            r.put("username", u.getUsername());
            r.put("punteggioSettimanale", tot);
            return r;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(risultato);
    }
}
