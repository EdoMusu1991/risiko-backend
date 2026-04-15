package com.risiko.controller;

import com.risiko.model.Badge;
import com.risiko.model.Partita;
import com.risiko.model.TerritorioErrore;
import com.risiko.model.Utente;
import com.risiko.repository.PartitaRepository;
import com.risiko.repository.TerritorioErroreRepository;
import com.risiko.repository.UtenteRepository;
import com.risiko.service.BadgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"http://localhost:4200", "https://risiko-backend-production.up.railway.app"})
@RestController
@RequestMapping("/api/statistiche")
public class StatisticheController {

    @Autowired
    private UtenteRepository utenteRepository;

    @Autowired
    private PartitaRepository partitaRepository;

    @Autowired
    private BadgeService badgeService;

    @Autowired
    private TerritorioErroreRepository territorioErroreRepository;

    // ── GRAFICO ANDAMENTO PUNTEGGIO ──────────────────────────────────────────
    @GetMapping("/andamento")
    public ResponseEntity<?> getAndamento(Authentication auth,
                                          @RequestParam(defaultValue = "30") int giorni) {
        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        if (utente == null) return ResponseEntity.notFound().build();

        List<Partita> partite = partitaRepository.findByUtenteOrderByGiocataIlDesc(utente);

        // Raggruppa per data (ultimi N giorni)
        List<Map<String, Object>> andamento = new ArrayList<>();
        partite.stream()
            .limit(50)
            .sorted(Comparator.comparing(Partita::getGiocataIl))
            .forEach(p -> {
                Map<String, Object> punto = new HashMap<>();
                punto.put("data", p.getGiocataIl().toString());
                punto.put("punteggio", p.getPunteggio());
                punto.put("corretta", p.isCorretta());
                punto.put("difficolta", p.getDifficolta());
                andamento.add(punto);
            });

        // Punteggio cumulativo
        int cumul = 0;
        for (Map<String, Object> punto : andamento) {
            cumul += (int) punto.get("punteggio");
            punto.put("punteggioCumulativo", cumul);
        }

        return ResponseEntity.ok(andamento);
    }

    // ── ACCURATEZZA PER OBIETTIVO ────────────────────────────────────────────
    @GetMapping("/obiettivi")
    public ResponseEntity<?> getAccuratezzaObiettivi(Authentication auth) {
        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        if (utente == null) return ResponseEntity.notFound().build();

        List<Partita> partite = partitaRepository.findByUtenteOrderByGiocataIlDesc(utente);

        // Conta corrette/sbagliate per obiettivoId
        Map<Integer, int[]> statsObj = new LinkedHashMap<>();
        for (int i = 1; i <= 16; i++) {
            statsObj.put(i, new int[]{0, 0}); // [corrette, sbagliate]
        }

        for (Partita p : partite) {
            if (p.getObiettivoId() != null) {
                int objId = ((Number) p.getObiettivoId()).intValue(); // ← fix cast
                int[] s = statsObj.getOrDefault(objId, new int[]{0, 0});
                if (p.isCorretta()) s[0]++;
                else s[1]++;
                statsObj.put(objId, s);
            }
        }

        String[] nomiObiettivi = {
            "Letto", "Elefante del Circo", "Ciclista", "Giraffa (buco)",
            "Granchio (diagonale)", "Formula 1", "Befana (3A)", "Elvis (guerriero)",
            "Dromedario con Mosca (eurasia)", "Piovra (nord)", "Lupo (siberiana)",
            "Tappeto", "Guerra Fredda", "Motorino (sud)", "Aragosta con Pesciolino", "Locomotiva"
        };

        List<Map<String, Object>> risultati = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            int[] s = statsObj.get(i);
            int totale = s[0] + s[1];
            Map<String, Object> r = new HashMap<>();
            r.put("id", i);
            r.put("nome", nomiObiettivi[i - 1]);
            r.put("corrette", s[0]);
            r.put("sbagliate", s[1]);
            r.put("totale", totale);
            r.put("accuratezza", totale > 0 ? (int) Math.round((double) s[0] / totale * 100) : 0);            risultati.add(r);
        }

        // Ordina per accuratezza crescente (peggiori prima)
        risultati.sort(Comparator.comparingInt(r -> ((Number) r.get("accuratezza")).intValue()));
        return ResponseEntity.ok(risultati);
    }

    // ── BADGES UTENTE ────────────────────────────────────────────────────────
    @GetMapping("/badges")
    public ResponseEntity<?> getBadges(Authentication auth) {
        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        if (utente == null) return ResponseEntity.notFound().build();

        List<Badge> badges = badgeService.getBadgeUtente(utente);
        Set<String> codiciOttenuti = badges.stream()
            .map(Badge::getCodice)
            .collect(Collectors.toSet());

        // Restituisce tutti i badge con stato sbloccato/bloccato
        List<Map<String, Object>> risultato = new ArrayList<>();
        BadgeService.BADGE_DEFINITIONS.forEach((codice, info) -> {
            Map<String, Object> b = new HashMap<>();
            b.put("codice", codice);
            b.put("icona", info[0]);
            b.put("nome", info[1]);
            b.put("descrizione", info[2]);
            b.put("sbloccato", codiciOttenuti.contains(codice));
            Optional<Badge> earned = badges.stream()
                .filter(bg -> bg.getCodice().equals(codice)).findFirst();
            earned.ifPresent(badge -> b.put("dataOttenuto", badge.getDataOttenuto().toString()));
            risultato.add(b);
        });

        // Ordina: sbloccati prima
        risultato.sort((a, b) -> {
            boolean sA = (boolean) a.get("sbloccato");
            boolean sB = (boolean) b.get("sbloccato");
            return Boolean.compare(sB, sA);
        });

        return ResponseEntity.ok(risultato);
    }

    // ── MAPPA CALORE TERRITORI ───────────────────────────────────────────────
    @GetMapping("/heatmap")
    public ResponseEntity<?> getHeatmap(Authentication auth) {
        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        if (utente == null) return ResponseEntity.notFound().build();

        List<TerritorioErrore> errori = territorioErroreRepository
            .findByUtenteOrderByErroriDesc(utente);

        List<Map<String, Object>> risultato = errori.stream().map(te -> {
            Map<String, Object> r = new HashMap<>();
            r.put("territorioId", te.getTerritorioId());
            r.put("errori", te.getErrori());
            r.put("totale", te.getTotaleApparizioni());
            r.put("percentuale", Math.round(te.getPercentualeErrore()));
            return r;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(risultato);
    }

    // ── SALVA ERRORI TERRITORIO (chiamato da PartitaController) ─────────────
    @PostMapping("/heatmap/aggiorna")
    public ResponseEntity<?> aggiornaHeatmap(Authentication auth,
                                              @RequestBody Map<String, Object> body) {
        Utente utente = utenteRepository.findByUsername(auth.getName()).orElse(null);
        if (utente == null) return ResponseEntity.notFound().build();

        @SuppressWarnings("unchecked")
        List<String> territoriSbagliati = (List<String>) body.getOrDefault("territoriSbagliati", List.of());
        @SuppressWarnings("unchecked")
        List<String> territoriGiocati = (List<String>) body.getOrDefault("territoriGiocati", List.of());

        // Aggiorna apparizioni per tutti i territori giocati
        for (String tid : territoriGiocati) {
            TerritorioErrore te = territorioErroreRepository
                .findByUtenteAndTerritorioId(utente, tid)
                .orElse(new TerritorioErrore(utente, tid));
            te.incrementaApparizione();
            territorioErroreRepository.save(te);
        }

        // Aggiorna errori per i territori sbagliati
        for (String tid : territoriSbagliati) {
            TerritorioErrore te = territorioErroreRepository
                .findByUtenteAndTerritorioId(utente, tid)
                .orElse(new TerritorioErrore(utente, tid));
            te.incrementaErrori();
            territorioErroreRepository.save(te);
        }

        return ResponseEntity.ok(Map.of("aggiornato", true));
    }
}
