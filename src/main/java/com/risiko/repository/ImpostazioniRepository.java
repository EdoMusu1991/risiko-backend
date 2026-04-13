package com.risiko.repository;

import com.risiko.model.Impostazioni;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ImpostazioniRepository extends JpaRepository<Impostazioni, Long> {
    Optional<Impostazioni> findByUserId(String userId);
}
