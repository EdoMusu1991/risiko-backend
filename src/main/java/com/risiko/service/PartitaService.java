package com.risiko.service;

import com.risiko.dto.ClassificaDto;
import com.risiko.dto.PartitaRequest;
import com.risiko.dto.ProfiloDto;
import com.risiko.model.Partita;
import com.risiko.model.Utente;
import com.risiko.repository.PartitaRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
        Object[] stats = partitaRepo.getStatsByUtenteId(u.getId());

        long tot  = stats[0] != null ? ((Number) stats[0]).longValue() : 0L;
        long cor  = stats[1] != null ? ((Number) stats[1]).longValue() : 0L;
        long pts  = stats[2] != null ? ((Number) stats[2]).longValue() : 0L;
        long pct  = tot > 0 ? Math.round((cor * 100.0) / tot) : 0L;

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
}
