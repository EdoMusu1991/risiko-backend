package com.risiko.service;

import com.risiko.model.Badge;
import com.risiko.model.Partita;
import com.risiko.model.StatisticheUtente;
import com.risiko.model.Utente;
import com.risiko.repository.BadgeRepository;
import com.risiko.repository.PartitaRepository;
import com.risiko.repository.StatisticheRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BadgeService {

    @Autowired
    private BadgeRepository badgeRepository;

    @Autowired
    private StatisticheRepository statisticheRepository;

    @Autowired
    private PartitaRepository partitaRepository;

    // Definizione di tutti i badge disponibili
    public static final Map<String, String[]> BADGE_DEFINITIONS = Map.ofEntries(
            // Partite giocate
            Map.entry("PARTITE_10",    new String[]{"🎮", "Esordiente",       "Hai giocato 10 partite"}),
            Map.entry("PARTITE_50",    new String[]{"🎮", "Veterano",          "Hai giocato 50 partite"}),
            Map.entry("PARTITE_100",   new String[]{"🎮", "Centurione",        "Hai giocato 100 partite"}),
            Map.entry("PARTITE_500",   new String[]{"🎮", "Leggenda",          "Hai giocato 500 partite"}),
            // Streak vittorie
            Map.entry("STREAK_3",      new String[]{"🔥", "In Forma",          "3 vittorie consecutive"}),
            Map.entry("STREAK_5",      new String[]{"🔥", "Serie Vincente",    "5 vittorie consecutive"}),
            Map.entry("STREAK_10",     new String[]{"🔥", "Inarrestabile",     "10 vittorie consecutive"}),
            Map.entry("STREAK_20",     new String[]{"🔥", "Dio del RisiKo",    "20 vittorie consecutive"}),
            // Punteggio
            Map.entry("PUNTI_100",     new String[]{"⭐", "Primo Centinaio",   "100 punti totali"}),
            Map.entry("PUNTI_500",     new String[]{"⭐", "Cinquecento",       "500 punti totali"}),
            Map.entry("PUNTI_1000",    new String[]{"⭐", "Millionario",       "1000 punti totali"}),
            // Difficolta
            Map.entry("DIFFICILE_1",   new String[]{"💀", "Coraggioso",        "Prima vittoria in Difficile"}),
            Map.entry("DIFFICILE_10",  new String[]{"💀", "Masochista",        "10 vittorie in Difficile"}),
            // Precisione
            Map.entry("ACC_80",        new String[]{"🎯", "Cecchino",          "80% accuratezza su 20+ partite"}),
            Map.entry("ACC_90",        new String[]{"🎯", "Tiratore Scelto",   "90% accuratezza su 20+ partite"}),
            // Speciali
            Map.entry("PERFETTO",      new String[]{"🏆", "Plancia Perfetta",  "Tutti e 4 corretti in un round"}),
            Map.entry("STUDIO_100",    new String[]{"📚", "Studioso",          "Tutti i 16 obiettivi studiati"})
    );

    public List<Badge> getBadgeUtente(Utente utente) {
        return badgeRepository.findByUtente(utente);
    }

    /**
     * Verifica e assegna badge usando StatisticheUtente + dati calcolati dalle partite.
     * StatisticheUtente ha: totaleRisposte, risposteCorrette, difficolta
     */
    public List<Badge> verificaEAssegnaBadge(Utente utente, StatisticheUtente stats) {
        List<Badge> nuoviBadge = new ArrayList<>();

        // Partite totali — usa totaleRisposte da StatisticheUtente
        int partiteTotali = stats.getTotaleRisposte();
        nuoviBadge.addAll(checkPartiteBadge(utente, partiteTotali));

        // Accuratezza — usa risposteCorrette / totaleRisposte
        if (partiteTotali >= 20) {
            double acc = stats.getPercentualeCorrette();
            nuoviBadge.addAll(checkAccuratezzaBadge(utente, acc));
        }

        // Punteggio totale — calcolato dalle partite
        List<Partita> partite = partitaRepository.findByUtenteOrderByGiocataIlDesc(utente);
        int punteggioTotale = partite.stream().mapToInt(Partita::getPunteggio).sum();
        nuoviBadge.addAll(checkPunteggioBadge(utente, punteggioTotale));

        // Streak — calcolato dalle partite in ordine cronologico inverso
        int streak = calcolaStreak(partite);
        nuoviBadge.addAll(checkStreakBadge(utente, streak));

        // Vittorie in difficile (difficolta == 3 && corretta == true)
        long vittorieDifficile = partite.stream()
                .filter(p -> p.getDifficolta() == 3 && p.isCorretta())
                .count();
        nuoviBadge.addAll(checkDifficileBadge(utente, (int) vittorieDifficile));

        return nuoviBadge;
    }

    /**
     * Calcola la streak corrente di vittorie consecutive
     * (partite già ordinate per data decrescente)
     */
    private int calcolaStreak(List<Partita> partiteDesc) {
        int streak = 0;
        for (Partita p : partiteDesc) {
            if (p.isCorretta()) streak++;
            else break;
        }
        return streak;
    }

    private List<Badge> checkPartiteBadge(Utente utente, int totale) {
        List<Badge> nuovi = new ArrayList<>();
        int[] soglie = {10, 50, 100, 500};
        String[] codici = {"PARTITE_10", "PARTITE_50", "PARTITE_100", "PARTITE_500"};
        for (int i = 0; i < soglie.length; i++) {
            if (totale >= soglie[i]) nuovi.addAll(assegnaSeNonEsiste(utente, codici[i]));
        }
        return nuovi;
    }

    private List<Badge> checkStreakBadge(Utente utente, int streak) {
        List<Badge> nuovi = new ArrayList<>();
        int[] soglie = {3, 5, 10, 20};
        String[] codici = {"STREAK_3", "STREAK_5", "STREAK_10", "STREAK_20"};
        for (int i = 0; i < soglie.length; i++) {
            if (streak >= soglie[i]) nuovi.addAll(assegnaSeNonEsiste(utente, codici[i]));
        }
        return nuovi;
    }

    private List<Badge> checkPunteggioBadge(Utente utente, int punteggio) {
        List<Badge> nuovi = new ArrayList<>();
        int[] soglie = {100, 500, 1000};
        String[] codici = {"PUNTI_100", "PUNTI_500", "PUNTI_1000"};
        for (int i = 0; i < soglie.length; i++) {
            if (punteggio >= soglie[i]) nuovi.addAll(assegnaSeNonEsiste(utente, codici[i]));
        }
        return nuovi;
    }

    private List<Badge> checkAccuratezzaBadge(Utente utente, double acc) {
        List<Badge> nuovi = new ArrayList<>();
        if (acc >= 90) nuovi.addAll(assegnaSeNonEsiste(utente, "ACC_90"));
        else if (acc >= 80) nuovi.addAll(assegnaSeNonEsiste(utente, "ACC_80"));
        return nuovi;
    }

    private List<Badge> checkDifficileBadge(Utente utente, int vittorieDifficile) {
        List<Badge> nuovi = new ArrayList<>();
        if (vittorieDifficile >= 1)  nuovi.addAll(assegnaSeNonEsiste(utente, "DIFFICILE_1"));
        if (vittorieDifficile >= 10) nuovi.addAll(assegnaSeNonEsiste(utente, "DIFFICILE_10"));
        return nuovi;
    }

    private List<Badge> assegnaSeNonEsiste(Utente utente, String codice) {
        List<Badge> lista = new ArrayList<>();
        if (!badgeRepository.existsByUtenteAndCodice(utente, codice)) {
            Badge b = new Badge(utente, codice);
            badgeRepository.save(b);
            lista.add(b);
        }
        return lista;
    }

    public void assegnaBadgeSpeciale(Utente utente, String codice) {
        assegnaSeNonEsiste(utente, codice);
    }
}
