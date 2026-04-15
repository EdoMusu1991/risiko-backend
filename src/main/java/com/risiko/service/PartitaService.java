package com.risiko.service;

import com.risiko.dto.ClassificaDto;
import com.risiko.dto.PartitaRequest;
import com.risiko.dto.PartitaRistoDto;
import com.risiko.dto.ProfiloDto;
import com.risiko.model.Partita;
import com.risiko.model.Utente;
import com.risiko.repository.PartitaRepository;
import com.risiko.repository.UtenteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PartitaService {

    private final PartitaRepository partitaRepo;
    private final UtenteService utenteService;
    @Autowired
    private UtenteRepository utenteRepo;

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

    public ProfiloDto getProfilo(String username) {
        Utente utente = utenteRepo.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Utente non trovato"));

        List<Partita> partite = partitaRepo.findByUtenteOrderByGiocataIlAsc(utente);

        int totale    = partite.size();
        int corrette  = (int) partite.stream().filter(Partita::isCorretta).count();
        int sbagliate = totale - corrette;
        int punteggio = partite.stream().mapToInt(Partita::getPunteggio).sum();

        // Per difficoltà
        int totaleFacile    = (int) partite.stream().filter(p -> p.getDifficolta() == 1).count();
        int totaleMedio     = (int) partite.stream().filter(p -> p.getDifficolta() == 2).count();
        int totaleDifficile = (int) partite.stream().filter(p -> p.getDifficolta() == 3).count();

        int corretteFacile    = (int) partite.stream().filter(p -> p.getDifficolta() == 1 && p.isCorretta()).count();
        int corretteMedio     = (int) partite.stream().filter(p -> p.getDifficolta() == 2 && p.isCorretta()).count();
        int corretteDifficile = (int) partite.stream().filter(p -> p.getDifficolta() == 3 && p.isCorretta()).count();

        // Streak corrente
        int streak = 0;
        List<Partita> invertita = new ArrayList<>(partite);
        Collections.reverse(invertita);
        for (Partita p : invertita) {
            if (p.isCorretta()) streak++;
            else break;
        }

        // Ultime 20 partite (ordine inverso)
        List<PartitaRistoDto> ultime = invertita.stream()
                .limit(20)
                .map(p -> new PartitaRistoDto(
                        p.getId(),
                        p.isCorretta(),
                        p.getDifficolta(),
                        p.getPunteggio(),
                        p.getGiocataIl() != null ? p.getGiocataIl().toString() : null
                ))
                .toList();

        return new ProfiloDto(
                utente.getUsername(),
                totale, corrette, sbagliate, punteggio,
                totaleFacile, totaleMedio, totaleDifficile,
                corretteFacile, corretteMedio, corretteDifficile,
                streak,
                ultime
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
