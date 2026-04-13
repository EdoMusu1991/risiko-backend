package com.risiko.service;

import com.risiko.dto.*;
import com.risiko.model.Obiettivo;
import com.risiko.model.StatisticheUtente;
import com.risiko.repository.ObiettivoRepository;
import com.risiko.repository.StatisticheRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class QuizService {

    private static final List<String> TUTTI_42 = List.of(
            "alaska","alberta","america_centrale","groenlandia","ontario",
            "quebec","stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
            "argentina","brasile","peru","venezuela",
            "europa_occidentale","europa_meridionale","europa_settentrionale",
            "gran_bretagna","islanda","scandinavia","ucraina",
            "afghanistan","urali","medio_oriente","india","siam","cina",
            "mongolia","jacuzia","cita","siberia","kamchatka","giappone",
            "indonesia","australia_occidentale","australia_orientale","nuova_guinea",
            "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar"
    );

    private static final List<String> COLORI = List.of("blu","rosso","verde","giallo");

    private final ObiettivoRepository obiettivoRepo;
    private final StatisticheRepository statRepo;
    private final TeoriaService teoriaService;

    /**
     * Sessione: sessionId -> (territori del giocatore, difficoltà)
     * Salviamo i TERRITORI (non l'obiettivo) per trovare tutti i compatibili.
     */
    private record SessionData(List<String> territori, int difficolta) {}
    private final Map<String, SessionData> sessioniAttive = new ConcurrentHashMap<>();

    public QuizService(ObiettivoRepository obiettivoRepo,
                       StatisticheRepository statRepo,
                       TeoriaService teoriaService) {
        this.obiettivoRepo = obiettivoRepo;
        this.statRepo = statRepo;
        this.teoriaService = teoriaService;
    }

    // =========================================================
    // GENERA PLANCIA
    // =========================================================

    public PlanciaDto generaPlancia(int difficolta) {
        List<Obiettivo> tutti = obiettivoRepo.findAll();
        Random rnd = new Random();

        List<Obiettivo> obiettivi = scegli4Obiettivi(tutti, rnd);
        List<List<String>> distribuzioni = distribuisciTerritori(obiettivi, difficolta, rnd);

        List<GiocatoreQuizDto> giocatori = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            List<String> terr = distribuzioni.get(i);
            String sessionId = UUID.randomUUID().toString();
            sessioniAttive.put(sessionId, new SessionData(new ArrayList<>(terr), difficolta));

            // Trova quanti obiettivi compatibili esistono (info per il frontend)
            List<Obiettivo> compatibili = trovaObiettiviCompatibili(terr, tutti, difficolta);
            int nCompatibili = compatibili.size();

            // Opzioni da mostrare all'utente (tutti e 16 vengono mostrati nel frontend)
            List<ObiettivoDto> opzioni = tutti.stream().map(teoriaService::toDto).toList();

            giocatori.add(new GiocatoreQuizDto(
                    COLORI.get(i), sessionId, terr, opzioni, nCompatibili));
        }

        return new PlanciaDto(
                giocatori.get(0), giocatori.get(1),
                giocatori.get(2), giocatori.get(3));
    }

    // =========================================================
    // CONFERMA PLANCIA (tutti e 4 insieme)
    // =========================================================

    /**
     * La risposta è COMPLETAMENTE corretta solo se l'utente ha selezionato
     * ESATTAMENTE l'insieme degli obiettivi compatibili:
     *  - Zero compatibili  → deve aver scelto "Nessun Obiettivo"
     *  - N compatibili     → deve aver scelto esattamente quegli N obiettivi
     *                        (né di più, né di meno)
     *
     * Un obiettivo O è compatibile con i territori T se:
     *   |T NOT in O| ≤ difficoltà
     */
    public RisultatoPlanciaDto confermaPlancia(ConfermaPlanciaRequest req) {
        List<Obiettivo> tutti = obiettivoRepo.findAll();
        List<RisultatoGiocatoreDto> risultati = new ArrayList<>();
        int punteggioTotale = 0;

        for (RispostaGiocatoreDto risposta : req.risposte()) {
            SessionData session = sessioniAttive.remove(risposta.sessionId());
            if (session == null) {
                risultati.add(new RisultatoGiocatoreDto(
                        risposta.colore(), false, List.of(), List.of(),
                        false, 0, "Sessione scaduta, ricarica la plancia."));
                continue;
            }

            List<String> terr   = session.territori();
            int diff            = session.difficolta();

            // Tutti gli obiettivi compatibili con questi territori
            List<Obiettivo> compatibili    = trovaObiettiviCompatibili(terr, tutti, diff);
            List<Integer>   idCompatibili  = compatibili.stream().map(Obiettivo::getId).toList();
            List<String>    nomiCompatibili= compatibili.stream().map(Obiettivo::getNome).toList();
            boolean nessunCompatibile      = compatibili.isEmpty();

            // Risposta dell'utente
            Set<Integer> sceltaUtente = risposta.obiettiviScelti() != null
                    ? new HashSet<>(risposta.obiettiviScelti())
                    : new HashSet<>();
            boolean haSceltaNessuno = risposta.nessunObiettivo();

            boolean corretta;
            String spiegazione;
            int punti = 0;

            if (nessunCompatibile) {
                // Caso: nessun obiettivo compatibile
                corretta = haSceltaNessuno && sceltaUtente.isEmpty();
                spiegazione = corretta
                        ? "Bravo! Nessun obiettivo era compatibile con questa configurazione."
                        : "Sbagliato: nessun obiettivo era compatibile con questi territori.";
            } else {
                // Caso: esistono N obiettivi compatibili
                // Corretto solo se l'utente ha scelto ESATTAMENTE quell'insieme
                Set<Integer> setCompatibili = new HashSet<>(idCompatibili);
                corretta = !haSceltaNessuno
                        && sceltaUtente.equals(setCompatibili);

                if (corretta) {
                    spiegazione = idCompatibili.size() == 1
                            ? "Perfetto! L'unico obiettivo compatibile era \"" + nomiCompatibili.get(0) + "\"."
                            : "Perfetto! Hai trovato tutti i " + idCompatibili.size()
                            + " obiettivi compatibili: " + String.join(", ", nomiCompatibili) + ".";
                } else {
                    spiegazione = "Sbagliato. "
                            + (idCompatibili.size() == 1
                            ? "L'obiettivo compatibile era: \"" + nomiCompatibili.get(0) + "\"."
                            : "Gli obiettivi compatibili erano: " + String.join(", ", nomiCompatibili) + ".");
                }
            }

            if (corretta) {
                punti = diff * 10;
                punteggioTotale += punti;
            }

            aggiornaStat(req.userId(), corretta);

            risultati.add(new RisultatoGiocatoreDto(
                    risposta.colore(), corretta,
                    idCompatibili, nomiCompatibili,
                    nessunCompatibile, punti, spiegazione));
        }

        boolean tuttiCorretti = risultati.stream().allMatch(RisultatoGiocatoreDto::corretta);
        return new RisultatoPlanciaDto(risultati, tuttiCorretti, punteggioTotale);
    }

    // =========================================================
    // ENDPOINT LEGACY (solo blu, compatibilità)
    // =========================================================

    public DomandaDto generaDomanda(String userId, int difficolta) {
        List<Obiettivo> tutti = obiettivoRepo.findAll();
        Random rnd = new Random();
        Obiettivo segreto = tutti.get(rnd.nextInt(tutti.size()));
        String sessionId = UUID.randomUUID().toString();
        List<String> terr = generaTerritoriBlu(segreto.getTerritori(), difficolta, rnd);
        sessioniAttive.put(sessionId, new SessionData(new ArrayList<>(terr), difficolta));
        List<ObiettivoDto> opzioni = tutti.stream().map(teoriaService::toDto).toList();
        int compatibili = trovaObiettiviCompatibili(terr, tutti, difficolta).size();
        return new DomandaDto(sessionId, terr, opzioni, segreto.getTerritori().size(), compatibili);
    }

    public RispostaDto valutaRisposta(RispostaRequest req) {
        SessionData session = sessioniAttive.remove(req.sessionId());
        if (session == null) throw new IllegalArgumentException("Sessione non trovata: " + req.sessionId());

        List<Obiettivo> tutti = obiettivoRepo.findAll();
        List<Obiettivo> compatibili = trovaObiettiviCompatibili(session.territori(), tutti, session.difficolta());
        List<Integer> idCompatibili = compatibili.stream().map(Obiettivo::getId).toList();
        boolean nessuno = compatibili.isEmpty();

        boolean giusta;
        if (req.nessunObiettivo()) {
            giusta = nessuno;
        } else {
            giusta = idCompatibili.contains(req.obiettivoScelto());
        }

        int punti = aggiornaStat(req.userId(), giusta);
        String nomeCorretto = compatibili.isEmpty() ? "Nessun obiettivo" : compatibili.get(0).getNome();
        int numTerr = session.territori().size();
        String spieg = giusta ? "Bravo!" : "Obiettivi compatibili: " +
                compatibili.stream().map(Obiettivo::getNome).collect(Collectors.joining(", "));
        return new RispostaDto(giusta, idCompatibili.isEmpty() ? null : idCompatibili.get(0),
                nomeCorretto, spieg, punti, nessuno);
    }

    // =========================================================
    // HELPER
    // =========================================================

    /**
     * Trova tutti gli obiettivi compatibili con i territori T:
     * un obiettivo O è compatibile se |T NOT in O| ≤ difficoltà
     */
    public List<Obiettivo> trovaObiettiviCompatibili(List<String> territori,
                                                     List<Obiettivo> tutti, int difficolta) {
        Set<String> terrSet = new HashSet<>(territori);
        return tutti.stream()
                .filter(o -> {
                    long fuori = terrSet.stream()
                            .filter(t -> !o.getTerritori().contains(t))
                            .count();
                    return fuori <= difficolta;
                })
                .collect(Collectors.toList());
    }

    private List<Obiettivo> scegli4Obiettivi(List<Obiettivo> tutti, Random rnd) {
        List<Obiettivo> shuffled = new ArrayList<>(tutti);
        Collections.shuffle(shuffled, rnd);
        return new ArrayList<>(shuffled.subList(0, Math.min(4, shuffled.size())));
    }

    private List<List<String>> distribuisciTerritori(List<Obiettivo> obiettivi,
                                                     int difficolta, Random rnd) {
        List<String> pool = new ArrayList<>(TUTTI_42);
        Collections.shuffle(pool, rnd);
        Set<String> assegnati = new HashSet<>();
        List<List<String>> risultati = new ArrayList<>();
        List<Integer> targets = new ArrayList<>(Arrays.asList(11, 11, 10, 10));
        Collections.shuffle(targets, rnd);

        for (int i = 0; i < 4; i++) {
            Obiettivo obj = obiettivi.get(i);
            int tot = targets.get(i);

            List<String> inObj = obj.getTerritori().stream()
                    .filter(t -> !assegnati.contains(t) && TUTTI_42.contains(t))
                    .collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(inObj, rnd);

            List<String> fuoriObj = pool.stream()
                    .filter(t -> !assegnati.contains(t) && !obj.getTerritori().contains(t))
                    .collect(Collectors.toCollection(ArrayList::new));

            int nFuori  = rnd.nextInt(Math.min(difficolta, fuoriObj.size()) + 1);
            int nDentro = Math.max(0, Math.min(tot - nFuori, inObj.size()));
            nFuori      = Math.min(tot - nDentro, fuoriObj.size());

            List<String> terr = new ArrayList<>();
            terr.addAll(inObj.subList(0, nDentro));
            terr.addAll(fuoriObj.subList(0, nFuori));

            if (terr.size() < 9) {
                List<String> liberi = pool.stream()
                        .filter(t -> !assegnati.contains(t) && !terr.contains(t))
                        .collect(Collectors.toCollection(ArrayList::new));
                int mancanti = Math.min(9 - terr.size(), liberi.size());
                terr.addAll(liberi.subList(0, mancanti));
            }

            Collections.shuffle(terr, rnd);
            assegnati.addAll(terr);
            risultati.add(terr);
        }
        return risultati;
    }

    private List<String> generaTerritoriBlu(List<String> territoriObj, int diff, Random rnd) {
        List<String> inObj = new ArrayList<>(territoriObj);
        List<String> fuoriObj = new ArrayList<>(TUTTI_42);
        fuoriObj.removeAll(new HashSet<>(territoriObj));
        Collections.shuffle(inObj, rnd); Collections.shuffle(fuoriObj, rnd);
        int nFuori  = rnd.nextInt(Math.min(diff, fuoriObj.size()) + 1);
        int totale  = 9 + rnd.nextInt(3);
        int nDentro = Math.max(1, Math.min(totale - nFuori, inObj.size()));
        nFuori      = Math.min(nFuori, fuoriObj.size());
        List<String> blue = new ArrayList<>();
        blue.addAll(inObj.subList(0, nDentro));
        blue.addAll(fuoriObj.subList(0, nFuori));
        Collections.shuffle(blue, rnd);
        return blue;
    }

    private List<ObiettivoDto> generaOpzioni(List<String> terr, List<Obiettivo> tutti, Random rnd) {
        return tutti.stream().map(teoriaService::toDto).toList();
    }

    private int aggiornaStat(String userId, boolean corretta) {
        if (userId == null || userId.isBlank()) return 0;
        StatisticheUtente stat = statRepo.findByUserId(userId)
                .orElseGet(() -> statRepo.save(new StatisticheUtente(userId)));
        stat.setTotaleRisposte(stat.getTotaleRisposte() + 1);
        if (corretta) stat.setRisposteCorrette(stat.getRisposteCorrette() + 1);
        statRepo.save(stat);
        return stat.getRisposteCorrette();
    }
}
