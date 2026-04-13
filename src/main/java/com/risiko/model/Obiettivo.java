package com.risiko.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Entity
@Table(name = "obiettivi")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Obiettivo {

    @Id
    private Integer id;

    @Column(nullable = false)
    private String nome;

    @Column
    private String immagine;

    @Column(nullable = false, length = 2000)
    @Convert(converter = StringListConverter.class)
    private List<String> territori;
}
