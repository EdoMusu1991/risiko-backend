package com.risiko.repository;

import com.risiko.model.Obiettivo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ObiettivoRepository extends JpaRepository<Obiettivo, Integer> {
}
