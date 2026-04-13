package com.risiko.service;

import com.risiko.dto.StatisticheDto;
import com.risiko.model.StatisticheUtente;
import com.risiko.repository.StatisticheRepository;
import org.springframework.stereotype.Service;

@Service
public class StatisticheService {

    private final StatisticheRepository repo;

    public StatisticheService(StatisticheRepository repo) {
        this.repo = repo;
    }

    /** Restituisce le statistiche dell'utente (crea un record vuoto se non esiste) */
    public StatisticheDto getStatistiche(String userId) {
        StatisticheUtente stat = repo.findByUserId(userId)
                .orElseGet(() -> repo.save(new StatisticheUtente(userId)));
        return toDto(stat);
    }

    /** Reset delle statistiche dell'utente */
    public StatisticheDto resetStatistiche(String userId) {
        StatisticheUtente stat = repo.findByUserId(userId)
                .orElseGet(() -> new StatisticheUtente(userId));
        stat.setTotaleRisposte(0);
        stat.setRisposteCorrette(0);
        return toDto(repo.save(stat));
    }

    private StatisticheDto toDto(StatisticheUtente s) {
        return new StatisticheDto(
                s.getUserId(),
                s.getTotaleRisposte(),
                s.getRisposteCorrette(),
                s.getPercentualeCorrette(),
                s.getDifficolta()
        );
    }
}
