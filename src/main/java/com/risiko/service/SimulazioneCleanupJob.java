package com.risiko.service;

import com.risiko.repository.GiocatoreSimulatoRepository;
import com.risiko.repository.HintUsatoRepository;
import com.risiko.repository.SimulazionePartitaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Job schedulato che elimina ogni notte le simulazioni più vecchie di 7 giorni
 * che non hanno un risultato salvato (cioè mai completate o abbandonate).
 *
 * ⚠️ Per abilitare: aggiungere @EnableScheduling alla classe principale SpringBoot:
 *
 *   @SpringBootApplication
 *   @EnableScheduling          ← aggiungere questa riga
 *   public class RisikoApplication { ... }
 */
@Component
public class SimulazioneCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SimulazioneCleanupJob.class);

    @Autowired private SimulazionePartitaRepository   simRepo;
    @Autowired private GiocatoreSimulatoRepository    giocatoreRepo;
    @Autowired private HintUsatoRepository            hintRepo;

    /**
     * Eseguito ogni notte alle 03:00.
     * Elimina simulazioni create più di 7 giorni fa.
     * Le simulazioni con risultato salvato vengono conservate (FK protegge).
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void pulisciSimulazioniVecchie() {
        LocalDateTime soglia = LocalDateTime.now().minusDays(7);

        List<Long> vecchieIds = simRepo.findIdCreatiPrimaDi(soglia);
        if (vecchieIds.isEmpty()) {
            log.info("Cleanup simulazioni: nulla da eliminare.");
            return;
        }

        log.info("Cleanup simulazioni: eliminazione di {} simulazioni precedenti al {}",
            vecchieIds.size(), soglia);

        // Elimina prima le entità figlie (FK constraint)
        hintRepo.deleteBySimulazioneIdIn(vecchieIds);
        giocatoreRepo.deleteBySimulazioneIdIn(vecchieIds);
        simRepo.deleteAllById(vecchieIds);
        log.info("Cleanup completato: {} simulazioni rimosse.", vecchieIds.size());
    }
}
