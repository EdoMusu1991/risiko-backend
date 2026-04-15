package com.risiko.repository;

import com.risiko.model.SimulazioneRisultato;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SimulazioneRisultatoRepository extends JpaRepository<SimulazioneRisultato, Long> {

    List<SimulazioneRisultato> findByUserIdOrderByGiocataIlDesc(String userId);

    @Query(value = """
        SELECT r.*
        FROM simulazione_risultato r
        INNER JOIN (
            SELECT user_id, MAX(punteggio) AS max_pt, MAX(id) AS max_id
            FROM simulazione_risultato
            GROUP BY user_id
        ) best ON r.user_id = best.user_id
               AND r.punteggio = best.max_pt
               AND r.id        = best.max_id
        ORDER BY r.punteggio DESC
        LIMIT 10
        """, nativeQuery = true)
    List<SimulazioneRisultato> findTop10Deduplicati();
}