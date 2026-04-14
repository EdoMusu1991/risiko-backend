package com.risiko.service;

import com.risiko.dto.ClassificaDto;
import com.risiko.dto.PartitaRequest;
import com.risiko.dto.ProfiloDto;
import com.risiko.model.Partita;
import com.risiko.model.Utente;
import com.risiko.repository.PartitaRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PartitaService {

    private final PartitaRepository partitaRepo;
    private final UtenteService utenteService;

    public PartitaService(PartitaRepository partitaRepo, UtenteService utenteService) {
        this.partitaRepo = partitaRepo;
        this.utenteService = utenteService;
    }

    /** Salva una partita giocata dall'utente autenticato */
    public void salva(String username, PartitaRequest req) {
        Utente utente = utenteService.findByUsername(username);
        Partita p = new Partita(
                utente,
                req.difficolta(),
                req.corretta(),
                req.punteggio(),
                req.obiettivoId()
        );
        partitaRepo.save(p);
    }

    /** Profilo utente con statistiche aggregate */
    public ProfiloDto getProfilo(String username) {
        Utente u = utenteService.findByUsername(username);
        List<Object[]> rawStats = partitaRepo.getStatsByUtenteId(u.getId());
        Object[] stats = (rawStats != null && !rawStats.isEmpty()) ? rawStats.get(0) : new Object[]{0L, 0L, 0L};

        long tot  = stats[0] != null ? ((Number) stats[0]).longValue() : 0L;
        long cor  = stats[1] != null ? ((Number) stats[1]).longValue() : 0L;
        long pts  = stats[2] != null ? ((Number) stats[2]).longValue() : 0L;
        long pct = tot > 0 ? Math.round((cor * 100.0) / tot) : 0L;
        
        return new ProfiloDto(
                u.getId(),
                u.getUsername(),
                u.getEmail() != null ? u.getEmail() : "",
                u.getAvatar(),
                u.getCreatoIl().toString(),
                tot, cor, pts, pct
        );
    }

    /** Classifica mondiale ordinata per punteggio */
    public List<ClassificaDto> getClassifica() {
        List<Object[]> raw = partitaRepo.getClassifica();
        List<ClassificaDto> result = new ArrayList<>();
        int pos = 1;
        for (Object[] row : raw) {
            result.add(new ClassificaDto(
                    pos++,
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    row[2] != null ? (String) row[2] : "⚔️",
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    ((Number) row[5]).longValue()
            ));
        }
        return result;
    }

    /** Storico ultime 20 partite dell'utente */
    public List<Map<String, Object>> getStorico(String username) {
        return partitaRepo.findTop20ByUtenteUsernameOrderByGiocataIlDesc(username)
                .stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          p.getId());
                    m.put("difficolta",  p.getDifficolta());
                    m.put("corretta",    p.isCorretta());
                    m.put("punteggio",   p.getPunteggio());
                    m.put("obiettivoId", p.getObiettivoId());
                    m.put("data",        p.getGiocataIl());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /** Statistiche aggregate per difficoltà + obiettivo meno indovinato + striscia */
    public Map<String, Object> getStats(String username) {
        List<Partita> tutte = partitaRepo.findByUtenteUsername(username);

        Map<Integer, Long> totPerDiff = tutte.stream()
                .collect(Collectors.groupingBy(Partita::getDifficolta, Collectors.counting()));
        Map<Integer, Long> corrPerDiff = tutte.stream()
                .filter(Partita::isCorretta)
                .collect(Collectors.groupingBy(Partita::getDifficolta, Collectors.counting()));

        List<Map<String, Object>> perDiff = List.of(1, 2, 3).stream().map(d -> {
            long tot  = totPerDiff.getOrDefault(d, 0L);
            long corr = corrPerDiff.getOrDefault(d, 0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("difficolta", d);
            m.put("label",      d == 1 ? "Facile" : d == 2 ? "Medio" : "Difficile");
            m.put("tot",        tot);
            m.put("corrette",   corr);
            m.put("perc",       tot > 0 ? Math.round(corr * 100.0 / tot) : 0);
            return m;
        }).collect(Collectors.toList());

        // Obiettivo meno indovinato
        Map<Integer, Long> sbaglPerObj = tutte.stream()
                .filter(p -> !p.isCorretta() && p.getObiettivoId() != null)
                .collect(Collectors.groupingBy(Partita::getObiettivoId, Collectors.counting()));

        Integer objPeggiore = sbaglPerObj.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // Striscia vittorie consecutive
        List<Partita> ordinate = tutte.stream()
                .sorted(Comparator.comparing(Partita::getGiocataIl).reversed())
                .collect(Collectors.toList());
        int striscia = 0;
        for (Partita p : ordinate) {
            if (p.isCorretta()) striscia++;
            else break;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("perDifficolta",   perDiff);
        result.put("objPeggiore",     objPeggiore);
        result.put("stricciaAttuale", striscia);
        return result;
    }
}
