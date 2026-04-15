package com.risiko.repository;

import com.risiko.model.SimulazionePartita;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SimulazionePartitaRepository extends JpaRepository<SimulazionePartita, Long> {

    @Query("""
        SELECT s.id FROM SimulazionePartita s
        WHERE s.creataIl < :soglia
          AND s.id NOT IN (SELECT r.simulazione.id FROM SimulazioneRisultato r)
        """)
    List<Long> findIdCreatiPrimaDi(@Param("soglia") LocalDateTime soglia);
}