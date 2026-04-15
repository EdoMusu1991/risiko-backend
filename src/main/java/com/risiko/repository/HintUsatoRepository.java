package com.risiko.repository;

import com.risiko.model.HintUsato;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface HintUsatoRepository extends JpaRepository<HintUsato, Long> {
    Optional<HintUsato> findBySimulazioneIdAndColore(Long simId, String colore);
    List<HintUsato> findBySimulazioneId(Long simId);
    long countBySimulazioneId(Long simId);
    void deleteBySimulazioneIdIn(List<Long> ids);
}