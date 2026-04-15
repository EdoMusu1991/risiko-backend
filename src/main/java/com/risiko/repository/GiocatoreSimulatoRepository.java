package com.risiko.repository;

import com.risiko.model.GiocatoreSimulato;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface GiocatoreSimulatoRepository extends JpaRepository<GiocatoreSimulato, Long> {
    List<GiocatoreSimulato> findBySimulazioneId(Long simulazioneId);
    void deleteBySimulazioneIdIn(List<Long> ids);
}