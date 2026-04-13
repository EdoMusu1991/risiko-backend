package com.risiko.repository;

import com.risiko.model.StatisticheUtente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface StatisticheRepository extends JpaRepository<StatisticheUtente, Long> {
    Optional<StatisticheUtente> findByUserId(String userId);
}
