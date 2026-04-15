package com.risiko.repository;

import com.risiko.model.SfidaGiornaliera;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface SfidaGiornalieraRepository extends JpaRepository<SfidaGiornaliera, Long> {
    Optional<SfidaGiornaliera> findByGiorno(LocalDate giorno);
}
