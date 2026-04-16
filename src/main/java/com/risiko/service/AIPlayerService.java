package com.risiko.service;

import com.risiko.service.RisikoBoardData.ObiettivoTarget;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AIPlayerService — Logica AI per la simulazione RisiKo!
 *
 * Regole implementate:
 * - Rinforzi: max(3, territori/3) + bonus continente
 * - Attacca solo con almeno il doppio delle armate del difensore
 * - Difende sempre con il massimo dei dadi (fino a 3)
 * - Lascia minimo 2 armate nel territorio da cui attacca
 * - Tris usato strategicamente (non appena ha 3 carte)
 * - Bonus tris +2 per ogni territorio in carta posseduto
 * - Sdadata: non conquista più di 2 territori se vuole sdadare
 * - Sdada solo se è in vantaggio di punteggio
 * - Blocca continenti avversari con alta priorità
 * - Priorità obiettivo + equilibrio partita in parallelo
 */
@Service
public class AIPlayerService {

    private static final Random RND = new Random();

    // ── Minimo armate lasciate in un territorio ───────────────────────────────
    private static final int MIN_ARMATE_DIFESA = 2;

    // ── Soglia minima territori per non scendere sotto 3 rinforzi/turno ───────
    private static final int SOGLIA_MIN_TERRITORI = 7;

    // ── Ordine preferenziale continenti da completare ─────────────────────────
    private static final List<String> ORDINE_CONTINENTI = List.of(
            "oceania", "sudamerica", "africa", "europa", "nordamerica", "asia"
    );

    // ── Territori strategici ad alto valore ───────────────────────────────────
    private static final Set<String> TERRITORI_STRATEGICI = Set.of(
            "medio_oriente", "ucraina", "ontario", "cina",
            "kamchatka", "africa_del_nord", "egitto", "brasile",
            "venezuela", "europa_settentrionale", "afghanistan", "mongolia"
    );

    // ── Sistema carte ─────────────────────────────────────────────────────────
    private enum Simbolo { FANTE, CANNONE, CAVALLO, JOLLY }
    private record Carta(String territorio, Simbolo simbolo) {}

    private final Map<String, List<Carta>> maniCarte = new HashMap<>();
    private final Map<String, Integer> territorConquistatiNelTurno = new HashMap<>();

    // ── Sistema cartina ───────────────────────────────────────────────────────
    // Tiene traccia degli accordi di cartina attivi: colore → partner
    private final Map<String, String> accordiCartina = new HashMap<>();
    // Territorio lasciato scoperto per cartina: colore → territorio
    private final Map<String, String> territoriCartina = new HashMap<>();
    // Armate precedenti per rilevare spostamenti (territorio → armate turno prima)
    private final Map<String, Integer> armaturePrecedenti = new HashMap<>();

    // ── Inferenza obiettivi avversari ─────────────────────────────────────────
    private final ObiettivoInferencer inferencer = new ObiettivoInferencer();
    private boolean inferencerInizializzato = false;

    // ── Tracking eventi per il frontend ──────────────────────────────────────
    private final List<EventoAttacco>   eventiAttaccoTurno  = new ArrayList<>();
    private final List<EventoCartina>   eventiCartinaTurno  = new ArrayList<>();
    private final List<EventoTris>      eventiTrisTurno     = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════════════════
    //  RECORD DI OUTPUT (NUOVO)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Risultato di un turno completo: log + sdadata + attacchi + stato carte.
     */
    public record TurnoResult(
            List<String>              log,
            SdadataResult             sdadata,
            List<EventoAttacco>       attacchi,
            Map<String, StatoCarte>   statoCarte,
            List<EventoCartina>       cartine,
            List<EventoTris>          tris) {}

    /**
     * Risultato di una singola fase del turno di un giocatore.
     * SimulazioneService crea uno snapshot per ogni FaseResult.
     */
    public record FaseResult(
            String               fase,        // "TRIS","RINFORZI","ATTACCHI","SPOSTAMENTO","CARTINA"
            String               colore,
            int                  turno,
            List<String>         log,
            List<EventoAttacco>  attacchi,
            Map<String, StatoCarte> statoCarte,
            List<EventoCartina>  cartine,
            List<EventoTris>     tris,
            SdadataResult        sdadata) {}  // non-null solo nell'ultima fase

    /** Singolo attacco del turno (usato per le frecce SVG nel frontend). */
    public record EventoAttacco(
            String  da,
            String  verso,
            String  coloreAttaccante,
            String  coloreDifensore,
            boolean conquistato) {}

    /** Uso di un tris in questo turno. */
    public record EventoTris(
            String colore,
            int    bonus,
            String tipo) {} // "DIVERSI", "UGUALI", "JOLLY"

    /** Accordo di cartina rilevato o offerto in questo turno. */
    public record EventoCartina(
            String coloreOffre,
            String coloreRiceve,
            String territorio) {}

    /** Stato mano carte di un giocatore. */
    public record StatoCarte(int fanti, int cannoni, int cavalli, int jolly) {
        public int totale() { return fanti + cannoni + cavalli + jolly; }
    }

    /**
     * Esito del tentativo di sdadata.
     *
     * @param tentato  true se le condizioni erano soddisfatte e l'AI ha tentato
     * @param riuscita true se la somma dei dadi ≤ soglia del turno
     * @param dado1    valore primo dado
     * @param dado2    valore secondo dado
     * @param totale   somma dado1+dado2
     * @param soglia   soglia massima (T35=4, T36=5, T37=6, T38+=7)
     */
    public record SdadataResult(
            boolean tentato, boolean riuscita,
            int dado1, int dado2, int totale, int soglia) {}

    // ═══════════════════════════════════════════════════════════════════════════
    //  DISTRIBUZIONE INIZIALE (NUOVO — vincolo max metà continente)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Distribuisce i 42 territori tra i giocatori rispettando:
     * - Nessun giocatore può avere più della metà dei territori di un continente
     *   (es. max 2 in Oceania che ne ha 4, max 3 in Africa che ne ha 6)
     * - Con 4 giocatori: 1° e 2° di mano ricevono 10 territori, 3° e 4° ricevono 11
     *
     * @param colori  lista ordinata dei colori (ordine di mano)
     * @param rnd     generatore random per lo shuffle iniziale
     * @return mappa colore → lista di territori assegnati
     */
    public static Map<String, List<String>> distribuisciTerritori(List<String> colori, Random rnd) {
        List<String> tutti = new ArrayList<>(RisikoBoardData.TUTTI_TERRITORI);
        Collections.shuffle(tutti, rnd);

        int n = colori.size();
        int base  = 42 / n;
        int extra = 42 % n; // con 4 giocatori = 2 → gli ultimi 2 di mano prendono 1 in più
        int[] quote = new int[n];
        for (int i = 0; i < n; i++) quote[i] = base + (i >= (n - extra) ? 1 : 0);

        // Cap: nessuno può avere più di floor(size/2) territori per continente
        Map<String, Integer> limiti = new HashMap<>();
        RisikoBoardData.CONTINENTI.forEach((cont, terr) -> limiti.put(cont, terr.size() / 2));

        Map<String, List<String>> assegnazioni = new LinkedHashMap<>();
        colori.forEach(c -> assegnazioni.put(c, new ArrayList<>()));

        List<String> pool = new ArrayList<>(tutti);

        for (int gi = 0; gi < n; gi++) {
            String colore = colori.get(gi);
            int target    = quote[gi];

            while (assegnazioni.get(colore).size() < target && !pool.isEmpty()) {
                // Cerca il primo territorio che non viola il cap per nessun continente
                Optional<String> scelto = pool.stream()
                        .filter(t -> distribuzioneNonViolaCap(t, colore, assegnazioni, limiti))
                        .findFirst();

                // Fallback: se il cap rende impossibile, prendi il primo disponibile
                String t = scelto.orElse(pool.get(0));
                assegnazioni.get(colore).add(t);
                pool.remove(t);
            }
        }
        return assegnazioni;
    }

    private static boolean distribuzioneNonViolaCap(String territorio, String colore,
                                                    Map<String, List<String>> assegnazioni,
                                                    Map<String, Integer> limiti) {
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            if (!ce.getValue().contains(territorio)) continue;
            long gia = assegnazioni.get(colore).stream().filter(ce.getValue()::contains).count();
            if (gia >= limiti.get(ce.getKey())) return false;
        }
        return true;
    }

    private boolean nonViolaCap(String territorio, String colore,
                                List<String> giàAssegnati,
                                Map<String, Integer> limiti) {
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            if (!ce.getValue().contains(territorio)) continue;
            long giàInCont = giàAssegnati.stream().filter(ce.getValue()::contains).count();
            if (giàInCont >= limiti.get(ce.getKey())) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PIAZZAMENTO INIZIALE (fase alternata, 3 carri per turno)
    // ═══════════════════════════════════════════════════════════════════════════

    public void piazzaIniziale(String colore, int obiettivoId,
                               Map<String, TerritoryState> mappa,
                               Map<String, Integer> carriPiazzatiDaAltri) {
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // Calcola dove gli altri stanno piazzando molto (da difendere)
        Set<String> territoriCaldi = carriPiazzatiDaAltri.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // Priorità piazzamento:
        // 1. Territori in obiettivo confinanti con territori caldi nemici
        // 2. Territori in obiettivo di confine
        // 3. Territori fuori obiettivo (minimi, solo 1 o 2 carri)
        List<String> inObiettivo = miei.stream()
                .filter(t -> isInObiettivo(t, obj))
                .collect(Collectors.toList());
        List<String> fuoriObiettivo = miei.stream()
                .filter(t -> !isInObiettivo(t, obj))
                .collect(Collectors.toList());

        int carriDaPiazzare = 3;

        // Prima i territori in obiettivo più minacciati
        for (String t : inObiettivo) {
            if (carriDaPiazzare <= 0) break;
            boolean minacciato = getConfinanti(t).stream()
                    .anyMatch(c -> territoriCaldi.contains(c) &&
                            !colore.equals(mappa.getOrDefault(c, new TerritoryState("?", 0)).getColore()));
            if (minacciato) {
                mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
                carriDaPiazzare--;
            }
        }

        // Poi i rimanenti sui territori in obiettivo più deboli
        if (carriDaPiazzare > 0) {
            inObiettivo.stream()
                    .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                    .limit(carriDaPiazzare)
                    .forEach(t -> mappa.get(t).setArmate(mappa.get(t).getArmate() + 1));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TURNO COMPLETO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Esegue il turno e restituisce la lista di fasi.
     * Ogni fase corrisponde a uno snapshot nel replay.
     */
    public List<FaseResult> eseguiFasi(String colore, int obiettivoId, int turno,
                                       Map<String, TerritoryState> mappa) {
        List<FaseResult> fasi = new ArrayList<>();

        // Inizializza l'inferencer al primo utilizzo
        if (!inferencerInizializzato) {
            Set<String> avversari = mappa.values().stream()
                    .map(TerritoryState::getColore)
                    .filter(c -> !colore.equals(c) && !"?".equals(c))
                    .collect(Collectors.toSet());
            inferencer.inizializza(avversari, mappa);
            inferencerInizializzato = true;
        }

        List<String> miei = getTerritoriGiocatore(colore, mappa);
        if (miei.isEmpty()) return fasi;

        territorConquistatiNelTurno.put(colore, 0);
        eventiAttaccoTurno.clear();
        eventiCartinaTurno.clear();
        eventiTrisTurno.clear();

        // ── FASE 1: TRIS ──────────────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("🃏 " + lbl(colore) + " — Fase tris (T" + turno + ")");
            eventiTrisTurno.clear();
            usaTrisSeUtile(colore, obiettivoId, new ArrayList<>(getTerritoriGiocatore(colore, mappa)), mappa, log);
            if (log.size() > 1) { // ha fatto qualcosa
                fasi.add(new FaseResult("TRIS", colore, turno, new ArrayList<>(log),
                        List.of(), buildStatoCarteCorrente(), List.of(),
                        List.copyOf(eventiTrisTurno), null));
            }
            eventiTrisTurno.clear();
        }

        // ── FASE 2: RINFORZI ──────────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("🛡 " + lbl(colore) + " — Fase rinforzi (T" + turno + ")");
            int rinforzi = calcolaRinforzi(colore, getTerritoriGiocatore(colore, mappa), mappa);
            piazzaRinforzi(colore, rinforzi, obiettivoId, turno, getTerritoriGiocatore(colore, mappa), mappa, log);
            fasi.add(new FaseResult("RINFORZI", colore, turno, new ArrayList<>(log),
                    List.of(), buildStatoCarteCorrente(), List.of(), List.of(), null));
        }

        // ── FASE 3: ATTACCHI ──────────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("⚔️ " + lbl(colore) + " — Fase attacchi (T" + turno + ")");
            eventiAttaccoTurno.clear();
            eventiCartinaTurno.clear();
            eseguiAttacchi(colore, obiettivoId, turno, mappa, log);

            // Pesca carta
            if (territorConquistatiNelTurno.getOrDefault(colore, 0) > 0) {
                pescaCarta(colore);
                log.add("🎴 " + lbl(colore) + " pesca una carta");
            }

            // Cartina
            gestisciCartina(colore, obiettivoId, mappa, log);

            if (log.size() > 1) {
                fasi.add(new FaseResult("ATTACCHI", colore, turno, new ArrayList<>(log),
                        List.copyOf(eventiAttaccoTurno), buildStatoCarteCorrente(),
                        List.copyOf(eventiCartinaTurno), List.of(), null));
            }
            eventiAttaccoTurno.clear();
            eventiCartinaTurno.clear();
        }

        // ── FASE 4: SPOSTAMENTO ───────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("🚀 " + lbl(colore) + " — Fase spostamento (T" + turno + ")");
            eseguiSpostamento(colore, obiettivoId, mappa, log);

            // Sdadata
            SdadataResult sdadata = valutaSdadata(colore, obiettivoId, turno, mappa);
            if (sdadata != null) {
                log.add(sdadata.riuscita()
                        ? "🎲 SDADATA! " + sdadata.dado1() + "+" + sdadata.dado2() + "=" + sdadata.totale() + " ≤ " + sdadata.soglia()
                        : "🎲 Sdadata fallita: " + sdadata.dado1() + "+" + sdadata.dado2() + "=" + sdadata.totale() + " > " + sdadata.soglia());
            }

            // Aggiorna inferencer
            inferencer.aggiornaTurno(mappa);
            salvaArmaturePrecedenti(mappa);

            fasi.add(new FaseResult("SPOSTAMENTO", colore, turno, new ArrayList<>(log),
                    List.of(), buildStatoCarteCorrente(), List.of(), List.of(), sdadata));
        }

        return fasi;
    }

    /** Mantiene la vecchia firma per compatibilità — delega a eseguiFasi. */
    public TurnoResult eseguiTurno(String colore, int obiettivoId, int turno,
                                   Map<String, TerritoryState> mappa) {
        List<FaseResult> fasi = eseguiFasi(colore, obiettivoId, turno, mappa);
        List<String> logTot = new ArrayList<>();
        List<EventoAttacco> att = new ArrayList<>();
        List<EventoCartina> cart = new ArrayList<>();
        List<EventoTris> tris = new ArrayList<>();
        SdadataResult sdadata = null;
        for (FaseResult f : fasi) {
            logTot.addAll(f.log());
            att.addAll(f.attacchi());
            cart.addAll(f.cartine());
            tris.addAll(f.tris());
            if (f.sdadata() != null) sdadata = f.sdadata();
        }
        Map<String, StatoCarte> sc = fasi.isEmpty() ? Map.of()
                : fasi.get(fasi.size()-1).statoCarte();
        return new TurnoResult(logTot, sdadata, att, sc, cart, tris);
    }

    /** Snapshot dello stato carte attuale di tutti i giocatori. */
    private Map<String, StatoCarte> buildStatoCarteCorrente() {
        Map<String, StatoCarte> result = new HashMap<>();
        maniCarte.forEach((c, mano) -> {
            int f=0,ca=0,k=0,j=0;
            for (Carta carta : mano) {
                switch (carta.simbolo()) {
                    case FANTE   -> f++;
                    case CANNONE -> ca++;
                    case CAVALLO -> k++;
                    case JOLLY   -> j++;
                }
            }
            result.put(c, new StatoCarte(f, ca, k, j));
        });
        return result;
    }



    // ═══════════════════════════════════════════════════════════════════════════
    //  SISTEMA CARTE E TRIS
    // ═══════════════════════════════════════════════════════════════════════════

    private void pescaCarta(String colore) {
        List<String> tutti = new ArrayList<>(RisikoBoardData.TUTTI_TERRITORI);
        String terr = tutti.get(RND.nextInt(tutti.size()));
        Simbolo sim = Simbolo.values()[RND.nextInt(3)]; // FANTE, CANNONE, CAVALLO
        maniCarte.computeIfAbsent(colore, k -> new ArrayList<>()).add(new Carta(terr, sim));
    }

    private void usaTrisSeUtile(String colore, int obiettivoId, List<String> miei,
                                Map<String, TerritoryState> mappa, List<String> log) {
        List<Carta> mano = maniCarte.getOrDefault(colore, new ArrayList<>());
        if (mano.size() < 3) return;

        int bonusTris = trovaTris(mano);
        if (bonusTris == 0) return;

        // Usa il tris solo se è strategicamente utile:
        // Deve poter attaccare con almeno il doppio dopo aver ricevuto i bonus
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // Calcola bonus territorio in carta (+2 per ogni territorio in carta posseduto)
        List<Carta> tris = mano.subList(0, 3);
        int bonusTerritori = 0;
        List<String> territoriBonus = new ArrayList<>();
        for (Carta c : tris) {
            if (c.simbolo() != Simbolo.JOLLY && miei.contains(c.territorio())) {
                bonusTerritori += 2;
                territoriBonus.add(c.territorio());
            }
        }

        // ── FIX: distribuisce armate tris sui confini in obiettivo più deboli ──
        // (non tutte su un solo territorio come nella versione precedente)
        List<String> confiniObiettivo = miei.stream()
                .filter(t -> isInObiettivo(t, obj))
                .filter(t -> !getConfinanti(t).stream()
                        .allMatch(c -> colore.equals(mappa.getOrDefault(c,
                                new TerritoryState(colore, 0)).getColore())))
                .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                .collect(Collectors.toList());

        if (confiniObiettivo.isEmpty()) {
            confiniObiettivo = miei.stream()
                    .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                    .collect(Collectors.toList());
        }

        // Distribuisce ciclicamente: 3 sul primo (concentrazione), poi 1 alla volta
        int rim = bonusTris;
        int iniziali = Math.min(3, rim);
        String primo = confiniObiettivo.get(0);
        mappa.get(primo).setArmate(mappa.get(primo).getArmate() + iniziali);
        rim -= iniziali;
        int idx = 1;
        while (rim > 0) {
            String t = confiniObiettivo.get(idx % confiniObiettivo.size());
            mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
            rim--;
            idx++;
        }
        log.add("🃏 " + lbl(colore) + " usa tris (" + bonusTris + " armate) su " +
                confiniObiettivo.stream().limit(3).map(this::nom).collect(Collectors.joining(", ")));

        // Applica bonus +2 per ogni territorio in carta posseduto
        for (String t : territoriBonus) {
            mappa.get(t).setArmate(mappa.get(t).getArmate() + 2);
            log.add("🃏 " + lbl(colore) + " bonus carta +2 → " + nom(t));
        }

        // Rimuovi le 3 carte usate
        // Determina tipo tris per il frontend
        List<Carta> prime3 = mano.subList(0, 3);
        Map<Simbolo, Long> cnt = prime3.stream()
                .collect(Collectors.groupingBy(Carta::simbolo, Collectors.counting()));
        String tipoTris = cnt.getOrDefault(Simbolo.JOLLY, 0L) >= 1 ? "JOLLY"
                : cnt.values().stream().anyMatch(v -> v >= 3) ? "UGUALI"
                : "DIVERSI";
        eventiTrisTurno.add(new EventoTris(colore, bonusTris, tipoTris));

        mano.subList(0, 3).clear();
    }

    /**
     * Cerca il miglior tris disponibile nella mano (non solo le prime 3 carte).
     * Ordine preferenza: Jolly+2uguali(12) > 3uguali(8) > Fante+Cannone+Cavallo(10)
     * Se trovato, riordina la mano mettendo le 3 carte del tris in cima.
     */
    private int trovaTris(List<Carta> mano) {
        if (mano.size() < 3) return 0;

        Map<Simbolo, List<Carta>> perSimbolo = new HashMap<>();
        for (Carta c : mano) perSimbolo.computeIfAbsent(c.simbolo(), k -> new ArrayList<>()).add(c);

        List<Carta> f = perSimbolo.getOrDefault(Simbolo.FANTE,   List.of());
        List<Carta> ca = perSimbolo.getOrDefault(Simbolo.CANNONE, List.of());
        List<Carta> k  = perSimbolo.getOrDefault(Simbolo.CAVALLO, List.of());
        List<Carta> j  = perSimbolo.getOrDefault(Simbolo.JOLLY,   List.of());

        // Jolly + 2 uguali (valore 12)
        if (!j.isEmpty()) {
            for (List<Carta> gruppo : List.of(f, ca, k)) {
                if (gruppo.size() >= 2) {
                    List<Carta> tris = new ArrayList<>();
                    tris.add(j.get(0)); tris.add(gruppo.get(0)); tris.add(gruppo.get(1));
                    riordinaMano(mano, tris);
                    return 12;
                }
            }
        }

        // 3 uguali (valore 8)
        for (List<Carta> gruppo : List.of(f, ca, k)) {
            if (gruppo.size() >= 3) {
                List<Carta> tris = new ArrayList<>(gruppo.subList(0, 3));
                riordinaMano(mano, tris);
                return 8;
            }
        }

        // Fante + Cannone + Cavallo (valore 10)
        if (!f.isEmpty() && !ca.isEmpty() && !k.isEmpty()) {
            List<Carta> tris = List.of(f.get(0), ca.get(0), k.get(0));
            riordinaMano(mano, tris);
            return 10;
        }

        return 0;
    }

    /** Porta le 3 carte del tris in cima alla mano per l'uso successivo. */
    private void riordinaMano(List<Carta> mano, List<Carta> tris) {
        mano.removeAll(tris);
        mano.addAll(0, tris);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  RINFORZI
    // ═══════════════════════════════════════════════════════════════════════════

    private int calcolaRinforzi(String colore, List<String> miei,
                                Map<String, TerritoryState> mappa) {
        int base = Math.max(3, miei.size() / 3);
        int bonus = 0;
        // Bonus continente completato
        for (Map.Entry<String, List<String>> e : RisikoBoardData.CONTINENTI.entrySet()) {
            if (miei.containsAll(e.getValue())) {
                bonus += bonusContinente(e.getKey());
            }
        }
        return base + bonus;
    }

    private int bonusContinente(String continente) {
        return switch (continente) {
            case "nordamerica" -> 5;
            case "sudamerica"  -> 2;
            case "europa"      -> 5;
            case "africa"      -> 3;
            case "asia"        -> 7;
            case "oceania"     -> 2;
            default            -> 0;
        };
    }

    private static final int MAX_ARMATE_TOTALI = 130;

    private void piazzaRinforzi(String colore, int rinforzi, int obiettivoId, int turno,
                                List<String> miei, Map<String, TerritoryState> mappa,
                                List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // ── CAP 130 armate totali ─────────────────────────────────────────────
        int armateAttuali = miei.stream().mapToInt(t -> mappa.get(t).getArmate()).sum();
        int spazio = Math.max(0, MAX_ARMATE_TOTALI - armateAttuali);
        if (spazio == 0) {
            log.add("🚫 " + lbl(colore) + " ha raggiunto il limite di " + MAX_ARMATE_TOTALI + " armate");
            return;
        }
        int rim = Math.min(rinforzi, spazio);

        List<String> confine = getTerritoriBordoNemico(colore, miei, mappa);
        if (confine.isEmpty()) confine = miei;

        // ── FASE 0: territori interni → riduce a 1 carro ────────────────────
        // Un territorio con tutti gli adiacenti dello stesso colore è al sicuro:
        // nessun nemico può attaccarlo. 1 carro basta; le armate in eccesso
        // vengono re-immesse nel budget per rinforzare i confini veri.
        for (String t : new ArrayList<>(miei)) {
            boolean interno = getConfinanti(t).stream()
                    .allMatch(adj -> colore.equals(
                            mappa.getOrDefault(adj, new TerritoryState("?", 0)).getColore()));
            if (interno && mappa.get(t).getArmate() > 1) {
                rim += mappa.get(t).getArmate() - 1;
                mappa.get(t).setArmate(1);
            }
        }

        // ── FASE 0b: territorio in obiettivo = ultimo nel suo continente ────
        // Se un territorio in obiettivo è l'unico del giocatore in quel continente,
        // va difeso con priorità assoluta perché:
        // 1. Perderlo = perdere punti obiettivo
        // 2. Il nemico potrebbe avere carte (3 carte = +10 armate extra stimate)
        // Calcola la minaccia nemica massima adiacente e porta il territorio
        // a un livello sufficiente a resistere anche a un attacco potenziato dal tris.
        for (String t : new ArrayList<>(confine)) {
            if (!isInObiettivo(t, obj)) continue;

            // Verifica se è l'ultimo del giocatore in quel continente
            boolean ultimoInContinente = false;
            for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
                List<String> tc = ce.getValue();
                if (!tc.contains(t)) continue;
                long mieiInCont = tc.stream()
                        .filter(terr -> colore.equals(
                                mappa.getOrDefault(terr, new TerritoryState("?",0)).getColore()))
                        .count();
                if (mieiInCont == 1) { ultimoInContinente = true; break; }
            }
            if (!ultimoInContinente) continue;

            // Calcola pressione nemica: max armate nemiche adiacenti
            int maxArmateNemiche = getConfinanti(t).stream()
                    .map(adj -> mappa.getOrDefault(adj, new TerritoryState("?", 0)))
                    .filter(st -> !colore.equals(st.getColore()) && !"?".equals(st.getColore()))
                    .mapToInt(TerritoryState::getArmate)
                    .max().orElse(0);

            // Stima se il nemico più forte ha carte (3+ carte = +10 armate potenziali)
            // Usa il colore del nemico più forte adiacente
            String coloreMiniccioso = getConfinanti(t).stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && !"?".equals(st.getColore())
                                && st.getArmate() == maxArmateNemiche;
                    })
                    .map(adj -> mappa.get(adj).getColore())
                    .findFirst().orElse(null);

            // Stima carte del nemico dal log delle carte (se tracciato)
            // Conservativamente: se ha già ≥6 armate adiacenti, assume possa avere carte
            int armateNemicheStimate = maxArmateNemiche;
            if (maxArmateNemiche >= 6) {
                armateNemicheStimate += 10; // stima tris nemico
            }

            // Il territorio deve avere almeno 2x le armate nemiche stimate per resistere
            int minimoDifesa = Math.max(6, armateNemicheStimate * 2);
            int attuale = mappa.get(t).getArmate();
            int mancanti = minimoDifesa - attuale;

            if (mancanti > 0 && rim > 0) {
                int aggiunti = Math.min(mancanti, rim);
                mappa.get(t).setArmate(attuale + aggiunti);
                rim -= aggiunti;
                log.add("🔒 " + lbl(colore) + " protegge ultimo in continente " +
                        nom(t) + " (" + attuale + "→" + (attuale + aggiunti) + ")");
            }

            if (rim <= 0) {
                log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate");
                return;
            }
        }

        // ── FASE 0c: territorio in obiettivo sotto pressione 3x ─────────────
        // Se le armate nemiche adiacenti dello stesso colore sommano ≥ 3x
        // le armate del territorio in obiettivo, va rinforzato con priorità alta.
        // Porta il territorio ad almeno la metà della pressione nemica.
        for (String t : new ArrayList<>(confine)) {
            if (!isInObiettivo(t, obj)) continue;
            if (rim <= 0) break;

            int mieArmate = mappa.get(t).getArmate();

            // Raggruppa i nemici per colore e somma le armate per ogni colore
            Map<String, Integer> sommaNemiciPerColore = new HashMap<>();
            for (String adj : getConfinanti(t)) {
                TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?", 0));
                if (!colore.equals(st.getColore()) && !"?".equals(st.getColore())) {
                    sommaNemiciPerColore.merge(st.getColore(), st.getArmate(), Integer::sum);
                }
            }

            // Prende la pressione massima da un singolo colore nemico
            int pressioneMassima = sommaNemiciPerColore.values().stream()
                    .mapToInt(Integer::intValue).max().orElse(0);

            // Se la pressione ≥ 3x le mie armate → territorio sotto assedio
            if (pressioneMassima >= mieArmate * 3) {
                // Porta il territorio ad almeno la metà della pressione nemica
                int target = pressioneMassima / 2;
                int mancanti = target - mieArmate;
                if (mancanti > 0) {
                    int aggiunti = Math.min(mancanti, rim);
                    mappa.get(t).setArmate(mieArmate + aggiunti);
                    rim -= aggiunti;
                    log.add("🛡 " + lbl(colore) + " rinforza sotto pressione 3x: "
                            + nom(t) + " (" + mieArmate + "→" + (mieArmate + aggiunti)
                            + " vs " + pressioneMassima + " nemici)");
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 0c: difendi territori che il nemico potrebbe rendere nicchia ──
        // Se tutti gli adiacenti di un nostro territorio sono già del nemico,
        // quel territorio è in pericolo estremo — va rinforzato subito.
        for (String t : new ArrayList<>(confine)) {
            List<String> confinanti = getConfinanti(t);
            if (confinanti.isEmpty()) continue;
            long adjNemici = confinanti.stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                    })
                    .count();
            // Se quasi tutti gli adiacenti sono nemici → territorio quasi-nicchiato
            if (adjNemici >= confinanti.size() - 1 && rim > 0) {
                int attuale = mappa.get(t).getArmate();
                // Stima minaccia: massima armata nemica adiacente (+ stima tris se ≥6)
                int maxNem = confinanti.stream()
                        .filter(adj -> {
                            TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                            return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                        })
                        .mapToInt(adj -> mappa.get(adj).getArmate())
                        .max().orElse(0);
                int minacciaStimata = maxNem >= 6 ? maxNem + 10 : maxNem;
                int minimo = Math.max(6, minacciaStimata * 2);
                int mancanti = minimo - attuale;
                if (mancanti > 0) {
                    int aggiunti = Math.min(mancanti, rim);
                    mappa.get(t).setArmate(attuale + aggiunti);
                    rim -= aggiunti;
                    log.add("🔒 " + lbl(colore) + " difende quasi-nicchia " +
                            nom(t) + " (" + attuale + "→" + (attuale + aggiunti) + ")");
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 1 (difesa): porta i territori di confine ad almeno 6 armate ──
        // Meno di 6 armate = bersaglio facile per la pesca carte nemica.
        // Usa al massimo metà dei rinforzi per questa fase difensiva.
        final int MINIMO_DIFESA_CONFINE = 6;
        int budgetDifesa = Math.max(0, rim / 2);

        List<String> daRinforzare = confine.stream()
                .filter(t -> mappa.get(t).getArmate() < MINIMO_DIFESA_CONFINE)
                .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                .collect(Collectors.toList());

        for (String t : daRinforzare) {
            if (budgetDifesa <= 0) break;
            int attuale  = mappa.get(t).getArmate();
            int mancanti = MINIMO_DIFESA_CONFINE - attuale;
            int aggiunti = Math.min(mancanti, budgetDifesa);
            if (aggiunti > 0) {
                mappa.get(t).setArmate(attuale + aggiunti);
                rim          -= aggiunti;
                budgetDifesa -= aggiunti;
                log.add("🔒 " + lbl(colore) + " difende " + nom(t) +
                        " (" + attuale + "→" + (attuale + aggiunti) + ")");
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 2 (offensiva): accumula TUTTE le armate restanti ────────────
        // Dal turno 35 (sdadata): SOLO territori in obiettivo contano.
        // Chi è sotto punteggio deve recuperare — tutto il resto è irrilevante.
        // Il numero di turno viene passato via obiettivoId come workaround
        // (vedi nota): usiamo la variabile locale se disponibile nel contesto.

        String continenteDaDifendere = trovaContinenteDaDifendere(colore, mappa);
        List<String> ordine = new ArrayList<>();

        // 1. In obiettivo + strategico (massima priorità sempre)
        confine.stream()
                .filter(t -> isInObiettivo(t, obj) && TERRITORI_STRATEGICI.contains(t))
                .forEach(ordine::add);

        // 2. In obiettivo — dal turno 35 aggiunto anche dall'interno (non solo confine)
        confine.stream().filter(t -> isInObiettivo(t, obj)).forEach(ordine::add);
        // Aggiungi anche i territori interni in obiettivo nella fase sdadata
        // (es. se Siberia è in obiettivo ma è interna, va rinforzata comunque)
        miei.stream()
                .filter(t -> isInObiettivo(t, obj))
                .filter(t -> !ordine.contains(t))
                .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                .forEach(ordine::add);

        // 3. Blocca continente avversario (dopo obiettivo, non prima)
        if (continenteDaDifendere != null) {
            List<String> tc = RisikoBoardData.CONTINENTI.get(continenteDaDifendere);
            confine.stream().filter(t -> tc != null && tc.contains(t)).forEach(ordine::add);
        }

        // 4. Confine del continente da completare
        for (String cont : ORDINE_CONTINENTI) {
            if (!isInObiettivoContinente(cont, obj)) continue;
            List<String> tc = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());
            confine.stream().filter(tc::contains).forEach(ordine::add);
        }

        // Tutto il confine
        ordine.addAll(confine);
        List<String> ord = ordine.stream().distinct().collect(Collectors.toList());
        if (ord.isEmpty()) ord = miei;

        // Dal turno 35: riordina mettendo PRIMA i territori in obiettivo
        // con meno armate — massimizza il punteggio per la sdadata.
        if (turno >= 35) {
            List<String> inObiettivo = ord.stream()
                    .filter(t -> isInObiettivo(t, obj))
                    .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                    .collect(Collectors.toList());
            List<String> fuoriObiettivo = ord.stream()
                    .filter(t -> !isInObiettivo(t, obj))
                    .collect(Collectors.toList());
            ord = new ArrayList<>(inObiettivo);
            ord.addAll(fuoriObiettivo);
        }

        // Concentra sul territorio prioritario, rispettando il cap di 25 armate.
        // Nessun territorio può superare 25 carri — inutile accumulare di più.
        final int CAP_ARMATE_TERRITORIO = 30;
        String principale = ord.get(0);
        int attualePrincipale = mappa.get(principale).getArmate();
        int spazioDisponibile = Math.max(0, CAP_ARMATE_TERRITORIO - attualePrincipale);
        int daAggiungere = Math.min(rim, spazioDisponibile);
        if (daAggiungere > 0) {
            mappa.get(principale).setArmate(attualePrincipale + daAggiungere);
            rim -= daAggiungere;
        }
        // Se il principale è già a 25, distribuisce sugli altri territori in ordine
        int idxExtra = 1;
        while (rim > 0 && idxExtra < ord.size()) {
            String t = ord.get(idxExtra % ord.size());
            int attualeT = mappa.get(t).getArmate();
            if (attualeT < CAP_ARMATE_TERRITORIO) {
                mappa.get(t).setArmate(attualeT + 1);
                rim--;
            }
            idxExtra++;
            if (idxExtra >= ord.size()) break; // evita loop infinito
        }
        log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate su " + nom(principale));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ATTACCHI
    // ═══════════════════════════════════════════════════════════════════════════

    private void eseguiAttacchi(String colore, int obiettivoId, int turno,
                                Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        int punteggioMio    = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int punteggioMaxAvv = getMassimoPunteggioAvversari(colore, mappa);
        int distaccoDalLeader = punteggioMaxAvv - punteggioMio; // negativo se sono in testa

        List<String> mieiPerConteggio = getTerritoriGiocatore(colore, mappa);
        boolean sottoSogliaTerritoriMinimi = mieiPerConteggio.size() < 9;
        int nTerr = mieiPerConteggio.size();

        int maxAttacchi;
        double moltiplicatoreSoglia;
        int maxConquiste = Integer.MAX_VALUE;

        // ══════════════════════════════════════════════════════════════════════
        // FASE SDADATA (turno ≥ 35): logica speciale
        // ══════════════════════════════════════════════════════════════════════
        if (turno >= 35) {
            // Stima quanti punti guadagnerei conquistando ogni territorio in obiettivo
            // raggiungibile. Ordina per punti decrescenti.
            List<String> miei = getTerritoriGiocatore(colore, mappa);
            List<int[]> bersagliConPunti = new ArrayList<>(); // [puntiGuadagnati, indice]

            for (String mio : miei) {
                for (String nemico : getConfinanti(mio)) {
                    TerritoryState st = mappa.get(nemico);
                    if (st == null || colore.equals(st.getColore())) continue;
                    if (!isInObiettivo(nemico, obj)) continue;
                    int punti = getConfinanti(nemico).size(); // valore del territorio
                    bersagliConPunti.add(new int[]{punti});
                }
            }
            bersagliConPunti.sort((a, b) -> b[0] - a[0]);

            // Caso A: posso andare sopra con ≤ 2 conquiste in obiettivo?
            int punteggioStimato = punteggioMio;
            int conquiste2 = 0;
            for (int[] bp : bersagliConPunti) {
                if (conquiste2 >= 2) break;
                punteggioStimato += bp[0];
                conquiste2++;
            }
            boolean possoPrendereVantaggioIn2 = punteggioStimato > punteggioMaxAvv;

            if (possoPrendereVantaggioIn2) {
                // Attacca max 2, soglia bassa per massimizzare chance
                maxAttacchi = 2;
                moltiplicatoreSoglia = 1.5;
                maxConquiste = 2;
            } else {
                // Caso B: non basta con 2 conquiste
                // Calcolo quanti territori servono per andare sopra
                int punteggioCon3 = punteggioMio;
                int c3 = 0;
                for (int[] bp : bersagliConPunti) {
                    if (c3 >= 3) break;
                    punteggioCon3 += bp[0];
                    c3++;
                }
                boolean possoCon3 = punteggioCon3 > punteggioMaxAvv;

                if (possoCon3 && distaccoDalLeader <= 15) {
                    // Con 3 conquiste vado sopra E sono vicino (≤15 punti):
                    // attacca 3 ma solo se posso difenderli (la soglia resta normale)
                    maxAttacchi = 3;
                    moltiplicatoreSoglia = 2.0;
                    maxConquiste = 3;
                } else if (distaccoDalLeader <= 15) {
                    // Sono vicino ma non riesco a sorpassare: attacca 1 (la migliore opportunità)
                    maxAttacchi = 1;
                    moltiplicatoreSoglia = 2.0;
                    maxConquiste = 1;
                } else {
                    // Troppo distante: non rischiare, difendi
                    maxAttacchi = 0;
                    moltiplicatoreSoglia = 99.0;
                    maxConquiste = 0;
                }
            }

            // ══════════════════════════════════════════════════════════════════════
            // FASE NORMALE (turni 1-34): logica difensiva standard
            // ══════════════════════════════════════════════════════════════════════
        } else {
            boolean sonoInVantaggio = punteggioMio > punteggioMaxAvv;
            maxConquiste = sonoInVantaggio ? 2 : Integer.MAX_VALUE;

            boolean endgame    = turno >= 30;
            boolean preEndgame = turno >= 20 && turno < 30;
            boolean sonoInTesta  = distaccoDalLeader < 0;
            boolean sonoIndietro = distaccoDalLeader > 10;

            if (sottoSogliaTerritoriMinimi) {
                maxAttacchi = 10; moltiplicatoreSoglia = 1.5;
            } else if (nTerr >= 12) {
                maxAttacchi = 1;  moltiplicatoreSoglia = 3.5;
            } else if (nTerr >= 10) {
                maxAttacchi = 2;  moltiplicatoreSoglia = 3.0;
            } else if (endgame && sonoIndietro) {
                maxAttacchi = 3;  moltiplicatoreSoglia = 2.0;
            } else if (endgame && sonoInTesta) {
                maxAttacchi = 1;  moltiplicatoreSoglia = 3.5;
            } else if (endgame) {
                maxAttacchi = 2;  moltiplicatoreSoglia = 2.5;
            } else if (turno <= 8) {
                maxAttacchi = 3;  moltiplicatoreSoglia = 2.5;
            } else if (turno <= 20) {
                maxAttacchi = 2;  moltiplicatoreSoglia = 3.0;
            } else {
                maxAttacchi = 1;
                moltiplicatoreSoglia = 3.5;
            }
        } // fine if turno >= 35 / else

        // ── ATTACCO FORZATO: ratio 4.5x-4.8x su territorio in obiettivo ────────
        // Se un territorio del giocatore ha tra 4.5 e 4.8 volte le armate
        // di un territorio adiacente in obiettivo, DEVE attaccarlo.
        // Questa opportunità non può essere ignorata indipendentemente dalla strategia.
        {
            List<String> mieiOra = getTerritoriGiocatore(colore, mappa);
            outer:
            for (String mio : mieiOra) {
                int mieArmate = mappa.get(mio).getArmate();
                for (String nemico : getConfinanti(mio)) {
                    TerritoryState st = mappa.get(nemico);
                    if (st == null || colore.equals(st.getColore())) continue;
                    if (!isInObiettivo(nemico, obj)) continue;
                    int armNem = st.getArmate();
                    if (armNem == 0) continue;
                    double ratio = (double) mieArmate / armNem;
                    if (ratio >= 4.5 && ratio <= 4.8) {
                        log.add("💥 " + lbl(colore) + " attacco forzato su " + nom(nemico)
                                + " (ratio " + String.format("%.1f", ratio) + "x)");
                        eseguiAttacco(new AttaccoCandidate(mio, nemico, 9999), mappa, log, colore);
                        break outer;
                    }
                }
            }
        }

        // Territori già tentati e falliti in questo turno — non riprovarli
        Set<String> giaTentati = new HashSet<>();
        int fallitiConsecutivi = 0;

        for (int tentativo = 0; tentativo < maxAttacchi; tentativo++) {
            List<String> miei = getTerritoriGiocatore(colore, mappa);

            if (miei.size() <= SOGLIA_MIN_TERRITORI) break;
            if (territorConquistatiNelTurno.getOrDefault(colore, 0) >= maxConquiste) break;
            // Dopo 3 fallimenti consecutivi senza conquiste, smette
            if (fallitiConsecutivi >= 3) break;

            AttaccoCandidate best = trovaMigliorAttacco(colore, miei, mappa, obj, giaTentati, moltiplicatoreSoglia, turno);
            if (best == null) break;

            // ── VETO: non attaccare se lascia esposto un territorio in obiettivo
            // che è l'ultimo nel suo continente e poco difeso vs nemici adiacenti
            // (considera anche +10 armate se il nemico ha ≥3 carte stimate)
            if (vetaAttaccoPerEsposizione(colore, best, obj, mappa)) {
                giaTentati.add(best.verso());
                fallitiConsecutivi++;
                continue;
            }

            boolean vinto = eseguiAttacco(best, mappa, log, colore);
            if (vinto) {
                fallitiConsecutivi = 0;
                giaTentati.clear(); // dopo una conquista, tutti i bersagli sono di nuovo validi
            } else {
                giaTentati.add(best.verso()); // non riprovare questo bersaglio
                fallitiConsecutivi++;
            }
        }
    }

    private record AttaccoCandidate(String da, String verso, int priorita) {}

    private AttaccoCandidate trovaMigliorAttacco(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa,
                                                 ObiettivoTarget obj) {
        return trovaMigliorAttacco(colore, miei, mappa, obj, Set.of());
    }

    private AttaccoCandidate trovaMigliorAttacco(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa,
                                                 ObiettivoTarget obj,
                                                 Set<String> escludi) {
        return trovaMigliorAttacco(colore, miei, mappa, obj, escludi, 2.0, 1);
    }

    private AttaccoCandidate trovaMigliorAttacco(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa,
                                                 ObiettivoTarget obj,
                                                 Set<String> escludi,
                                                 double moltiplicatoreSoglia,
                                                 int turno) {
        AttaccoCandidate best = null;

        for (String mio : miei) {
            int mieArmate = mappa.get(mio).getArmate();
            if (mieArmate < 2) continue;

            for (String nemico : getConfinanti(mio)) {
                if (escludi.contains(nemico)) continue;

                TerritoryState st = mappa.get(nemico);
                if (st == null || colore.equals(st.getColore())) continue;

                int armateNemico = st.getArmate();

                // Soglia difensività: cresce con i turni (2x → 2.5x → 3x)
                int minimoPerAttaccare = (int) Math.ceil(armateNemico * moltiplicatoreSoglia);

                if (mieArmate < minimoPerAttaccare) continue;

                int p = calcolaPrioritaAttacco(nemico, colore, st.getColore(), obj, mappa, turno);
                if (best == null || p > best.priorita())
                    best = new AttaccoCandidate(mio, nemico, p);
            }
        }
        return best;
    }

    private int calcolaPrioritaAttacco(String territorio, String colore,
                                       String coloreNemico, ObiettivoTarget obj,
                                       Map<String, TerritoryState> mappa,
                                       int turno) {
        int p = 0;
        int armateNemico = mappa.get(territorio).getArmate();

        // ── PRIORITÀ ASSOLUTA: territorio in obiettivo ───────────────────────
        // Il bonus cresce con i turni. Dal turno 35 (sdadata) diventa critico:
        // ogni turno aggiunge urgenza perché chi è sotto di punteggio perde.
        // Turni 1-10:  base 400
        // Turni 11-20: base 550
        // Turni 21-34: base 700
        // Turni 35+:   base 700 + (turno-34)*150 → cresce ogni turno di sdadata
        int bonusObiettivo;
        if (turno <= 10)      bonusObiettivo = 400;
        else if (turno <= 20) bonusObiettivo = 550;
        else if (turno <= 34) bonusObiettivo = 700;
        else                  bonusObiettivo = 700 + (turno - 34) * 150;

        // In fase sdadata, aggiunge urgenza se sotto di punteggio reale
        // Il bonus scala ogni turno: T35=+100, T36=+200, T37=+300 ecc.
        if (turno >= 35 && isInObiettivo(territorio, obj)) {
            bonusObiettivo += (turno - 34) * 100;
        }

        if (isInObiettivo(territorio, obj)) {
            p += bonusObiettivo;
            p += getConfinanti(territorio).size() * 20;
            if (TERRITORI_STRATEGICI.contains(territorio)) p += 80;
            for (int i = 0; i < ORDINE_CONTINENTI.size(); i++) {
                String cont = ORDINE_CONTINENTI.get(i);
                List<String> terrCont = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());
                if (terrCont.contains(territorio) && isInObiettivoContinente(cont, obj)) {
                    p += (ORDINE_CONTINENTI.size() - i) * 20;
                    break;
                }
            }
        }

        // ── PRIORITÀ 2: blocca continente avversario quasi completato ────────
        // Scende ogni turno: nei turni finali non vale la pena distrarsi dal proprio obiettivo.
        // Turni 1-10:  100
        // Turni 11-20:  60
        // Turni 21+:    20 (quasi irrilevante — focus solo sull'obiettivo)
        int bonusContinente;
        if (turno <= 10)      bonusContinente = 100;
        else if (turno <= 20) bonusContinente = 60;
        else                  bonusContinente = 20;

        if (staBlocandoContinente(territorio, coloreNemico, mappa)) p += bonusContinente;

        // ── PRIORITÀ 3: territorio con 1-3 armate → carta facile ────────────
        // Bersaglio prioritario: pesca carta + toglie territorio debole al nemico
        if      (armateNemico == 1) p += 180;
        else if (armateNemico == 2) p += 140;
        else if (armateNemico == 3) p += 80;

        // ── PRIORITÀ EMERGENZA: recupera territori se sotto soglia minima ────
        long mieiTotali = mappa.values().stream()
                .filter(st -> colore.equals(st.getColore())).count();
        if (mieiTotali < 9) p += 600; // EMERGENZA ASSOLUTA: priorità sopra tutto

        // ── ENDGAME (turno ≥ 30): attacca i territori in obiettivo del leader ─
        // I bot stimano gli obiettivi avversari e attaccano i territori del
        // giocatore in testa per abbassarne il punteggio stimato.
        if (turno >= 30) {
            int pMio  = calcolaPunteggioAggregato(colore, mappa);
            int pNem  = calcolaPunteggioAggregato(coloreNemico, mappa);
            if (pNem > pMio) {
                // Il nemico è in testa: vale la pena attaccargli territori ad alto valore
                // Stima se quel territorio vale punti per il nemico (molte adiacenze = valore)
                int valorePerNemico = getConfinanti(territorio).size();
                p += valorePerNemico * (turno - 29) * 5; // scala con l'urgenza
            }
        }

        // ── PRIORITÀ 4: territorio strategico (fuori obiettivo) ─────────────
        if (!isInObiettivo(territorio, obj) && TERRITORI_STRATEGICI.contains(territorio)) p += 40;

        // ── PRIORITÀ 5b: completa continente mancante di 1 territorio ─────
        // Se il bot possiede tutti i territori di un continente tranne 1,
        // e quel territorio è facilmente conquistabile (3x), attacca con priorità alta.
        // La priorità è ancora più alta se il continente vale molti rinforzi.
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> tc = ce.getValue();
            if (!tc.contains(territorio)) continue; // questo territorio non è nel continente
            String cont = ce.getKey();

            // Quanti territori del continente possiedo già?
            long mieiInCont = tc.stream()
                    .filter(terr -> colore.equals(
                            mappa.getOrDefault(terr, new TerritoryState("?",0)).getColore()))
                    .count();

            // Manca solo questo territorio per completare il continente
            if (mieiInCont == tc.size() - 1) {
                // Bonus base: proporzionale al valore del continente (rinforzi)
                int bonusCompletaCont = switch (cont) {
                    case "asia"        -> 7;  // +7 rinforzi → priorità massima
                    case "nordamerica" -> 5;
                    case "europa"      -> 5;
                    case "africa"      -> 3;
                    case "sudamerica"  -> 2;
                    case "oceania"     -> 2;
                    default            -> 1;
                } * 60; // scala: Asia = 420, Nord America/Europa = 300, ecc.

                // "Facile" = difensore ≤ 3 armate → +50%
                if (armateNemico <= 3) {
                    bonusCompletaCont = (int)(bonusCompletaCont * 1.5);
                }

                p += bonusCompletaCont;
                break;
            }
        }

        // ── PRIORITÀ 5c: territorio che creerebbe nicchia al nemico ─────────
        // Una nicchia = territorio con tutti gli adiacenti dello stesso colore
        // → al sicuro dagli attacchi. Va conquistato/difeso prima che accada.
        // Caso A: tutti gli adiacenti (tranne il territorio stesso) sono GIÀ del nemico
        //         → nicchia immediata se conquistato → urgenza massima (+350)
        // Caso B: la maggioranza degli adiacenti è del nemico → rischio nicchia (+150)
        {
            List<String> confinanti = getConfinanti(territorio);
            if (!confinanti.isEmpty()) {
                long adjNemici = confinanti.stream()
                        .filter(adj -> coloreNemico.equals(
                                mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore()))
                        .count();
                if (adjNemici >= confinanti.size() - 1) {
                    // Quasi tutti gli adiacenti sono del nemico → nicchia immediata
                    p += 350;
                } else if (adjNemici >= (confinanti.size() + 1) / 2) {
                    // Maggioranza degli adiacenti è del nemico → rischio nicchia
                    p += 150;
                }
            }
        }

        // ── PRIORITÀ 5: coalizione contro il giocatore dominante ───────────
        // Due condizioni indipendenti che si sommano:
        //
        // A) Ha >11 territori → minaccia globale, tutti lo attaccano
        //    Bonus proporzionale: +60 per ogni territorio oltre 11
        //
        // B) Controlla Nord America, Asia o Europa → bonus continente molto
        //    forte (+5 o +7 rinforzi/turno) → priorità assoluta fermarlo
        //    Bonus fisso: +500
        //
        // Le due priorità si moltiplicano bene con l'obiettivo:
        // se il territorio da conquistare è anche in obiettivo del bot
        // attaccante, i bonus si sommano → attacco quasi certo.

        long territoriNemico = mappa.values().stream()
                .filter(st -> st.getColore().equals(coloreNemico))
                .count();

        // A) Dominanza numerica
        if (territoriNemico >= 12) {
            p += (int)(territoriNemico - 11) * 60;
        }

        // B) Controlla continente ad alto valore (Nord America +5, Asia +7, Europa +5)
        List<String> continentiAltoValore = List.of("nordamerica", "asia", "europa");
        for (String cont : continentiAltoValore) {
            List<String> tc = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());
            boolean controllaContNemico = tc.stream()
                    .allMatch(terr -> coloreNemico.equals(
                            mappa.getOrDefault(terr, new TerritoryState("?", 0)).getColore()));
            if (controllaContNemico) {
                p += 500;
                break; // un solo bonus anche se controlla più continenti
            }
        }

        // Malus: armate difensore (rischioso attaccare posizioni forti)
        p -= armateNemico * 3;

        // ── Perturbazione casuale ±10% ───────────────────────────────────────
        // Introduce variabilità nelle decisioni dell'AI: 5-10% delle mosse
        // saranno "sorpresa" — l'AI non sceglie sempre il target ottimale.
        if (p > 0) {
            int noise = (int)(p * (RND.nextDouble() * 0.2 - 0.1)); // da -10% a +10%
            p += noise;
        }

        return p;
    }

    private boolean eseguiAttacco(AttaccoCandidate att, Map<String, TerritoryState> mappa,
                                  List<String> log, String colore) {
        TerritoryState da    = mappa.get(att.da());
        TerritoryState verso = mappa.get(att.verso());
        String difensore = verso.getColore();

        int totPerseAtt = 0;
        int totPerseDif = 0;

        // ── Multi-round: ogni round = un lancio di dadi ─────────────────────
        // Regole dadi:
        //   Attaccante: max 3 dadi, ma al massimo (armate - MIN_ARMATE_DIFESA)
        //               così non scende mai sotto il minimo strategico
        //   Difensore:  max 3 dadi (house rule: 3, non 2 come nel classico)
        //   Confronto:  coppie dal più alto; il dado più basso perde 1 armata
        //   Parità:     vince sempre il difensore
        int round = 0;
        // Combatte finché il difensore ha armate E l'attaccante ha almeno 2
        // (regola: deve restare almeno 1 in caso di vittoria + 1 per la difesa del territorio)
        while (da.getArmate() > MIN_ARMATE_DIFESA && verso.getArmate() > 0 && round++ < 100) {
            // Dadi attaccante: massimo 3, deve lasciare almeno MIN_ARMATE_DIFESA
            int numAtt = Math.min(3, da.getArmate() - 1); // lascia 1 in territorio (regola gioco, non MIN_ARMATE_DIFESA)
            // Dadi difensore: massimo 2 (regola standard Risiko)
            // Con 3v3, P(att vince dado) = 41.7% → il difensore vince sempre. Bug matematico.
            // Con 3v2, P(att vince) ≈ 57% → l'attaccante ha un vantaggio reale.
            int numDif = Math.min(2, verso.getArmate());

            int[] datiAtt = tiraDadiOrdinati(numAtt);
            int[] datiDif = tiraDadiOrdinati(numDif);

            // Confronta dal più alto: min(att, dif) coppie
            int confronti = Math.min(numAtt, numDif);
            int perseAtt  = 0;
            int perseDif  = 0;

            for (int i = 0; i < confronti; i++) {
                if (datiAtt[i] > datiDif[i]) perseDif++; // attaccante vince la coppia
                else                          perseAtt++; // parità → difensore vince
            }

            da.setArmate(da.getArmate() - perseAtt);
            verso.setArmate(Math.max(0, verso.getArmate() - perseDif));
            totPerseAtt += perseAtt;
            totPerseDif += perseDif;
        }

        boolean conquistato = verso.getArmate() <= 0;

        if (conquistato) {
            int spedite   = Math.max(MIN_ARMATE_DIFESA, da.getArmate() - MIN_ARMATE_DIFESA);
            int rimanenti = da.getArmate() - spedite;
            if (rimanenti < MIN_ARMATE_DIFESA) {
                spedite   = da.getArmate() - MIN_ARMATE_DIFESA;
                rimanenti = MIN_ARMATE_DIFESA;
            }
            da.setArmate(rimanenti);
            verso.setColore(colore);
            verso.setArmate(Math.max(1, spedite));

            territorConquistatiNelTurno.merge(colore, 1, Integer::sum);
            log.add("⚔️ " + lbl(colore) + " conquista " + nom(att.verso()) +
                    " da " + nom(att.da()) + " [era " + lbl(difensore) + "]" +
                    " (att -" + totPerseAtt + " | dif -" + totPerseDif + ")");
        } else {
            log.add("🛡 " + lbl(difensore) + " respinge " + lbl(colore) +
                    " su " + nom(att.verso()) +
                    " [att -" + totPerseAtt + ", dif -" + totPerseDif + "]");
        }

        // Registra evento per il frontend (frecce SVG)
        eventiAttaccoTurno.add(new EventoAttacco(
                att.da(), att.verso(), colore, difensore, conquistato));

        return conquistato;
    }

    /** Tira {@code n} dadi e restituisce i valori ordinati in modo DECRESCENTE. */
    private int[] tiraDadiOrdinati(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = RND.nextInt(6) + 1;
        Arrays.sort(r);
        for (int i = 0, j = n - 1; i < j; i++, j--) { int t = r[i]; r[i] = r[j]; r[j] = t; }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SPOSTAMENTO FINALE (solo territorio adiacente)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Veta un attacco se eseguirlo lascerebbe un territorio in obiettivo
     * che è l'ultimo nel suo continente con difesa insufficiente.
     *
     * La difesa è insufficiente se:
     * - Le armate del territorio ≤ 2x le armate nemiche adiacenti massime
     * - Se il nemico più forte ha ≥6 armate adiacenti, si stima +10 per tris
     *   (con 3 carte un bot può aggiungere 10 armate extra al prossimo turno)
     */
    private boolean vetaAttaccoPerEsposizione(String colore, AttaccoCandidate attacco,
                                              ObiettivoTarget obj,
                                              Map<String, TerritoryState> mappa) {
        // Dopo l'attacco, il territorio "da" perde alcune armate.
        // Simula la situazione peggiore: attaccante perde MIN_ARMATE_DIFESA armate
        // (scende al minimo consentito).
        String da = attacco.da();
        int armateDopoAttacco = MIN_ARMATE_DIFESA; // scenario peggiore

        // Controlla tutti i nostri territori in obiettivo che sono ultimi nel continente
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        for (String t : miei) {
            if (!isInObiettivo(t, obj)) continue;

            // È l'ultimo nel suo continente?
            boolean ultimoInContinente = false;
            for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
                List<String> tc = ce.getValue();
                if (!tc.contains(t)) continue;
                long mieiInCont = tc.stream()
                        .filter(terr -> colore.equals(
                                mappa.getOrDefault(terr, new TerritoryState("?",0)).getColore()))
                        .count();
                if (mieiInCont == 1) { ultimoInContinente = true; break; }
            }
            if (!ultimoInContinente) continue;

            // Calcola armate del territorio dopo l'attacco
            int armateT = t.equals(da) ? armateDopoAttacco : mappa.get(t).getArmate();

            // Calcola minaccia nemica massima adiacente
            int maxNemica = getConfinanti(t).stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                    })
                    .mapToInt(adj -> mappa.get(adj).getArmate())
                    .max().orElse(0);

            if (maxNemica == 0) continue;

            // Stima tris nemico: se ha ≥6 armate adiacenti, potrebbe avere carte
            int minacciaStimata = maxNemica >= 6 ? maxNemica + 10 : maxNemica;

            // Veta se la difesa è insufficiente (< 2x la minaccia stimata)
            if (armateT < minacciaStimata * 2) {
                return true; // veta l'attacco
            }
        }
        return false;
    }

    private void eseguiSpostamento(String colore, int obiettivoId,
                                   Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        List<String> confine = getTerritoriBordoNemico(colore, miei, mappa);
        if (confine.isEmpty()) return;

        // Trova territorio interno adiacente a un confine con molte armate da spostare
        for (String frontiera : confine) {
            // Cerca territorio interno adiacente con più armate
            Optional<String> interno = getConfinanti(frontiera).stream()
                    .filter(t -> colore.equals(mappa.getOrDefault(t,
                            new TerritoryState("?", 0)).getColore()))
                    .filter(t -> !confine.contains(t)) // è interno
                    .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1)
                    .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

            if (interno.isEmpty()) continue;

            String fonte = interno.get();
            int qty = mappa.get(fonte).getArmate() - MIN_ARMATE_DIFESA;
            if (qty <= 0) continue;

            // Sposta solo verso frontiera in obiettivo o strategica
            boolean valePena = isInObiettivo(frontiera, obj)
                    || TERRITORI_STRATEGICI.contains(frontiera)
                    || staBlocandoContinente(frontiera, null, mappa);

            if (!valePena) continue;

            mappa.get(fonte).setArmate(MIN_ARMATE_DIFESA);
            mappa.get(frontiera).setArmate(mappa.get(frontiera).getArmate() + qty);
            log.add("🚀 " + lbl(colore) + " sposta " + qty + " da " +
                    nom(fonte) + " → " + nom(frontiera));
            break; // Un solo spostamento per turno
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SISTEMA CARTINA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gestisce la logica cartina:
     * 1. Rileva se un avversario sta offrendo una cartina
     * 2. Decide se accettarla o rifiutarla
     * 3. Decide se offrirne una propria
     * 4. Ricambia se è in accordo attivo
     */
    private void gestisciCartina(String colore, int obiettivoId,
                                 Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        List<Carta> mano = maniCarte.getOrDefault(colore, new ArrayList<>());

        int punteggioMio = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int punteggioMax = getMassimoPunteggioAvversari(colore, mappa);
        boolean hoBisognoDiCarte = mano.size() < 3;
        boolean sonoLontanoDaObiettivo = punteggioMio < 40; // meno della metà di 86

        // ── RILEVA E ACCETTA/RIFIUTA CARTINA OFFERTA ─────────────────────────

        String cartinaTrovata = rilevaCarthina(colore, miei, mappa, obj);
        if (cartinaTrovata != null) {
            String partner = mappa.get(cartinaTrovata).getColore();
            boolean accetta = deveAccettareCartina(colore, partner, mano, mappa,
                    hoBisognoDiCarte, sonoLontanoDaObiettivo);
            if (accetta) {
                accettaCartina(colore, cartinaTrovata, partner, obiettivoId, mappa, log);
            } else {
                log.add("🤝 " + lbl(colore) + " rifiuta cartina di " + lbl(partner));
            }
        }

        // ── OFFRI CARTINA SE STRATEGICAMENTE UTILE ───────────────────────────
        // REGOLA: non apre mai cartina se ci sono territori nemici adiacenti
        // con 1 o 2 armate — quei territori sono bersagli facili da conquistare
        // direttamente (pesca carta gratis senza accordo).
        boolean esistonoBersagliAdjDeboli = miei.stream()
                .flatMap(t -> getConfinanti(t).stream())
                .distinct()
                .anyMatch(adj -> {
                    TerritoryState st = mappa.get(adj);
                    return st != null
                            && !colore.equals(st.getColore())
                            && st.getArmate() <= 2;
                });

        if (hoBisognoDiCarte && sonoLontanoDaObiettivo
                && !accordiCartina.containsKey(colore)
                && !esistonoBersagliAdjDeboli) {
            offriCartina(colore, obiettivoId, miei, mappa, log);
        }

        // ── RICAMBIA CARTINA SE IN ACCORDO ATTIVO ────────────────────────────

        if (accordiCartina.containsKey(colore)) {
            ricambiaCartina(colore, obiettivoId, miei, mappa, log);
        }

        // ── ROMPI ACCORDO SE NON SERVE PIÙ ───────────────────────────────────

        if (!hoBisognoDiCarte && mano.size() >= 3) {
            String partner = accordiCartina.remove(colore);
            if (partner != null) {
                log.add("🤝 " + lbl(colore) + " interrompe accordo con " + lbl(partner));
            }
        }
    }

    /**
     * Rileva se un avversario ha lasciato un territorio con 2 armate
     * che in precedenza ne aveva di più (segnale di cartina).
     */
    private String rilevaCarthina(String colore, List<String> miei,
                                  Map<String, TerritoryState> mappa, ObiettivoTarget obj) {
        for (String mio : miei) {
            for (String adiacente : getConfinanti(mio)) {
                TerritoryState st = mappa.get(adiacente);
                if (st == null || colore.equals(st.getColore())) continue;

                // Segnali di cartina:
                // 1. Ha esattamente 2 armate
                if (st.getArmate() != MIN_ARMATE_DIFESA) continue;

                // 2. Prima ne aveva di più (spostamento rilevato)
                int precedenti = armaturePrecedenti.getOrDefault(adiacente, -1);
                if (precedenti <= MIN_ARMATE_DIFESA) continue;

                // 3. Il territorio non è nell'obiettivo dell'avversario
                //    (dedotto: se lo stanno lasciando scoperto, non gli serve)
                // Non possiamo saperlo con certezza, ma se è un territorio
                // a basso valore (pochi confinanti) è più probabile sia fuori obiettivo
                if (getConfinanti(adiacente).size() >= 5) continue; // territorio troppo prezioso

                return adiacente;
            }
        }
        return null;
    }

    /**
     * Decide se accettare la cartina offerta da un avversario.
     */
    private boolean deveAccettareCartina(String colore, String partner,
                                         List<Carta> mano,
                                         Map<String, TerritoryState> mappa,
                                         boolean hoBisognoDiCarte,
                                         boolean sonoLontanoDaObiettivo) {
        // Rifiuta se ho già abbastanza carte
        if (!hoBisognoDiCarte) return false;

        // Rifiuta se non sono lontano dall'obiettivo
        if (!sonoLontanoDaObiettivo) return false;

        // Accetta preferibilmente con giocatore debole
        List<String> lorTerritori = getTerritoriGiocatore(partner, mappa);
        if (lorTerritori.size() > 15) return false; // troppo forte, rischioso

        // Accetta se il partner non ha un continente (meno pericoloso)
        boolean haContinente = RisikoBoardData.CONTINENTI.values().stream()
                .anyMatch(terrCont -> lorTerritori.containsAll(terrCont));

        return !haContinente || lorTerritori.size() < 12;
    }

    /**
     * Accetta la cartina: conquista il territorio con 2 armate
     * e decide se ricambiare lasciando 2 armate nel territorio conquistato
     * oppure su un altro territorio adiacente al partner.
     */
    private void accettaCartina(String colore, String territorio, String partner,
                                int obiettivoId, Map<String, TerritoryState> mappa,
                                List<String> log) {
        TerritoryState st = mappa.get(territorio);
        if (st == null) return;

        // Trova territorio da cui attaccare
        Optional<String> fonte = getConfinanti(territorio).stream()
                .filter(t -> colore.equals(mappa.getOrDefault(t,
                        new TerritoryState("?", 0)).getColore()))
                .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA)
                .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

        if (fonte.isEmpty()) return;

        // Conquista
        TerritoryState tfonte = mappa.get(fonte.get());
        tfonte.setArmate(tfonte.getArmate() - 1);
        st.setColore(colore);
        st.setArmate(MIN_ARMATE_DIFESA); // lascia 2 per ricambiare

        territorConquistatiNelTurno.merge(colore, 1, Integer::sum);
        pescaCarta(colore);

        log.add("🤝 " + lbl(colore) + " accetta cartina da " + lbl(partner) +
                " → conquista " + nom(territorio) + " (lascia " + MIN_ARMATE_DIFESA + ")");
        eventiCartinaTurno.add(new EventoCartina(partner, colore, territorio));

        // Registra accordo
        accordiCartina.put(colore, partner);
        territoriCartina.put(colore, territorio);
    }

    /**
     * Offre una cartina: sposta armate lasciando 2 in un territorio
     * fuori obiettivo adiacente al partner preferito.
     */
    private void offriCartina(String colore, int obiettivoId, List<String> miei,
                              Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // Trova il partner ideale: più debole e senza continente
        String partnerIdeale = trovaMigliorPartnerCartina(colore, mappa);
        if (partnerIdeale == null) return;

        // Trova territorio fuori obiettivo adiacente al partner, con più di 2 armate
        List<String> lorTerritori = getTerritoriGiocatore(partnerIdeale, mappa);
        Optional<String> territorioDaLasciare = miei.stream()
                .filter(t -> !isInObiettivo(t, obj))           // fuori obiettivo
                .filter(t -> !TERRITORI_STRATEGICI.contains(t)) // non strategico
                .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1) // ha abbastanza
                .filter(t -> getConfinanti(t).stream().anyMatch(lorTerritori::contains)) // adiacente al partner
                .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

        if (territorioDaLasciare.isEmpty()) return;

        String terrCartina = territorioDaLasciare.get();
        TerritoryState st = mappa.get(terrCartina);
        int armateToSposta = st.getArmate() - MIN_ARMATE_DIFESA;
        if (armateToSposta <= 0) return;

        // Trova territorio vicino dove spostare le armate in eccesso
        Optional<String> destinazione = getConfinanti(terrCartina).stream()
                .filter(t -> colore.equals(mappa.getOrDefault(t,
                        new TerritoryState("?", 0)).getColore()))
                .filter(t -> isInObiettivo(t, obj))
                .findFirst()
                .or(() -> getConfinanti(terrCartina).stream()
                        .filter(t -> colore.equals(mappa.getOrDefault(t,
                                new TerritoryState("?", 0)).getColore()))
                        .findFirst());

        if (destinazione.isEmpty()) return;

        // Sposta lasciando MIN_ARMATE_DIFESA
        st.setArmate(MIN_ARMATE_DIFESA);
        mappa.get(destinazione.get()).setArmate(
                mappa.get(destinazione.get()).getArmate() + armateToSposta);

        accordiCartina.put(colore, partnerIdeale);
        territoriCartina.put(colore, terrCartina);

        log.add("🤝 " + lbl(colore) + " offre cartina a " + lbl(partnerIdeale) +
                " su " + nom(terrCartina) + " (2 armate)");
        eventiCartinaTurno.add(new EventoCartina(colore, partnerIdeale, terrCartina));
    }

    /**
     * Ricambia la cartina: lascia 2 armate su un territorio adiacente al partner.
     */
    private void ricambiaCartina(String colore, int obiettivoId, List<String> miei,
                                 Map<String, TerritoryState> mappa, List<String> log) {
        String partner = accordiCartina.get(colore);
        if (partner == null) return;

        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> lorTerritori = getTerritoriGiocatore(partner, mappa);

        // Trova un nuovo territorio fuori obiettivo adiacente al partner
        Optional<String> nuovaCartina = miei.stream()
                .filter(t -> !isInObiettivo(t, obj))
                .filter(t -> !TERRITORI_STRATEGICI.contains(t))
                .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1)
                .filter(t -> getConfinanti(t).stream().anyMatch(lorTerritori::contains))
                .filter(t -> !t.equals(territoriCartina.get(colore))) // territorio diverso dal precedente
                .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

        if (nuovaCartina.isEmpty()) return;

        String terrCartina = nuovaCartina.get();
        TerritoryState st = mappa.get(terrCartina);
        int armateToSposta = st.getArmate() - MIN_ARMATE_DIFESA;
        if (armateToSposta <= 0) return;

        // Trova dove spostare le armate in eccesso
        Optional<String> destinazione = getConfinanti(terrCartina).stream()
                .filter(t -> colore.equals(mappa.getOrDefault(t,
                        new TerritoryState("?", 0)).getColore()))
                .findFirst();

        if (destinazione.isEmpty()) return;

        st.setArmate(MIN_ARMATE_DIFESA);
        mappa.get(destinazione.get()).setArmate(
                mappa.get(destinazione.get()).getArmate() + armateToSposta);

        territoriCartina.put(colore, terrCartina);
        log.add("🤝 " + lbl(colore) + " ricambia cartina a " + lbl(partner) +
                " su " + nom(terrCartina));
    }

    /**
     * Trova il miglior partner per la cartina:
     * - Più debole (pochi territori)
     * - Senza continente completato
     * - Adiacente a qualche territorio dell'AI
     */
    private String trovaMigliorPartnerCartina(String colore,
                                              Map<String, TerritoryState> mappa) {
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        Set<String> adiacenti = miei.stream()
                .flatMap(t -> getConfinanti(t).stream())
                .map(t -> mappa.getOrDefault(t, new TerritoryState("?", 0)).getColore())
                .filter(c -> !colore.equals(c) && !"?".equals(c))
                .collect(Collectors.toSet());

        return adiacenti.stream()
                .filter(partner -> {
                    List<String> loro = getTerritoriGiocatore(partner, mappa);
                    // Non fare cartina con chi ha troppi territori (pericoloso)
                    if (loro.size() > 14) return false;
                    // Preferisce chi non ha un continente
                    return RisikoBoardData.CONTINENTI.values().stream()
                            .noneMatch(terrCont -> loro.containsAll(terrCont));
                })
                .min(Comparator.comparingInt(partner ->
                        getTerritoriGiocatore(partner, mappa).size()))
                .orElse(null);
    }

    /**
     * Salva le armate attuali per rilevare spostamenti al turno successivo.
     */
    private void salvaArmaturePrecedenti(Map<String, TerritoryState> mappa) {
        mappa.forEach((t, st) -> armaturePrecedenti.put(t, st.getArmate()));
    }



    // ═══════════════════════════════════════════════════════════════════════════
    //  SDADATA (NUOVO)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Valuta e tenta la sdadata secondo le regole:
     * - La sdadata è disponibile SOLO dal turno 25 in poi
     * - Tira 2 dadi, somma ≤ soglia(turno) → sdadata riuscita → partita finita
     * - Soglie: T35≤4, T36≤5, T37≤6, T38+≤7
     * - L'AI tenta SOLO se ha punteggio > massimo avversario stimato
     * - Non può tentare se ha conquistato >2 territori nel turno corrente
     *
     * @return SdadataResult con esito del tentativo, oppure null se non tenta
     */
    /**
     * Sdadata OBBLIGATORIA se conquiste ≤ 2.
     *
     * GIALLO (4° di mano) inizia al T35:  T35=4, T36=5, T37=6, T38+=7
     * BLU/ROSSO/VERDE iniziano al T36:    T36=4, T37=5, T38=6, T39+=7
     * Dal T39 (GIALLO) e T40 (altri) in poi la soglia è fissa a 7.
     * Al T45 la partita finisce comunque.
     */
    public SdadataResult valutaSdadata(String colore, int obiettivoId, int turno,
                                       Map<String, TerritoryState> mappa) {
        // Condizione 1: conquiste > 2 → non può sdadare
        int conquiste = territorConquistatiNelTurno.getOrDefault(colore, 0);
        if (conquiste > 2) return null;

        // GIALLO inizia al T35, tutti gli altri al T36
        int turnoBase = "GIALLO".equals(colore) ? 35 : 36;
        if (turno < turnoBase) return null;

        // Soglia: 4 al turnoBase, +1 ogni turno, max 7
        int soglia = Math.min(7, 4 + (turno - turnoBase));

        int dado1  = RND.nextInt(6) + 1;
        int dado2  = RND.nextInt(6) + 1;
        int totale = dado1 + dado2;

        return new SdadataResult(true, totale <= soglia, dado1, dado2, totale, soglia);
    }

    private String avversariDebug(String colore, Map<String, TerritoryState> mappa) {
        return mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !colore.equals(c) && !"?".equals(c))
                .distinct()
                .map(c -> lbl(c) + "~" + inferencer.stimaPunteggio(c, mappa) + "pt")
                .collect(Collectors.joining(" | "));
    }

    /** Stima il punteggio aggregato di un giocatore basandosi sui suoi territori
     *  e sull'inferenza degli obiettivi avversari. Usato nell'endgame. */
    private int calcolaPunteggioAggregato(String colore, Map<String, TerritoryState> mappa) {
        if (inferencerInizializzato) {
            return inferencer.stimaPunteggio(colore, mappa);
        }
        // Fallback: somma adiacenze dei suoi territori / 2
        return getTerritoriGiocatore(colore, mappa).stream()
                .mapToInt(t -> getConfinanti(t).size())
                .sum() / 2;
    }

    public int calcolaPunteggioAttuale(String colore, int obiettivoId,
                                       Map<String, TerritoryState> mappa) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        if (obj == null) return 0;

        return getTerritoriGiocatore(colore, mappa).stream()
                .filter(t -> isInObiettivo(t, obj))
                .mapToInt(t -> getConfinanti(t).size())
                .sum();
    }

    private int getMassimoPunteggioAvversari(String colore, Map<String, TerritoryState> mappa) {
        // FIX: usa l'inferencer bayesiano invece della somma cieca di tutti i confinanti
        Set<String> avversari = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !colore.equals(c) && !"?".equals(c))
                .collect(Collectors.toSet());

        int max = 0;
        for (String avversario : avversari) {
            int stima = inferencerInizializzato
                    ? inferencer.stimaPunteggio(avversario, mappa)
                    : stimaPunteggioFallback(avversario, mappa);
            if (stima > max) max = stima;
        }
        return max;
    }

    /** Fallback pre-inizializzazione: stima conservativa (metà della somma dei confinanti). */
    private int stimaPunteggioFallback(String colore, Map<String, TerritoryState> mappa) {
        return getTerritoriGiocatore(colore, mappa).stream()
                .mapToInt(t -> getConfinanti(t).size())
                .sum() / 2;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  UTILITY DIFESA CONTINENTI
    // ═══════════════════════════════════════════════════════════════════════════

    private String trovaContinenteDaDifendere(String colore,
                                              Map<String, TerritoryState> mappa) {
        for (Map.Entry<String, List<String>> e : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> terrCont = e.getValue();
            // Conta quanti territori del continente ha un singolo avversario
            Map<String, Long> conteggioPerColore = terrCont.stream()
                    .map(t -> mappa.getOrDefault(t, new TerritoryState("?", 0)).getColore())
                    .filter(c -> !colore.equals(c) && !"?".equals(c))
                    .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

            for (Map.Entry<String, Long> ce : conteggioPerColore.entrySet()) {
                // Se un avversario ha tutti i territori tranne 1 → emergenza!
                if (ce.getValue() >= terrCont.size() - 1) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    private boolean staBlocandoContinente(String territorio, String coloreNemico,
                                          Map<String, TerritoryState> mappa) {
        for (Map.Entry<String, List<String>> e : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> terrCont = e.getValue();
            if (!terrCont.contains(territorio)) continue;

            if (coloreNemico != null) {
                long delNemico = terrCont.stream()
                        .filter(t -> {
                            TerritoryState st = mappa.get(t);
                            return st != null && coloreNemico.equals(st.getColore());
                        }).count();
                if (delNemico >= terrCont.size() - 1) return true;
            } else {
                // Controlla qualsiasi avversario
                Map<String, Long> count = terrCont.stream()
                        .map(t -> mappa.getOrDefault(t, new TerritoryState("?", 0)).getColore())
                        .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
                if (count.values().stream().anyMatch(v -> v >= terrCont.size() - 1)) return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  UTILITY GENERALI
    // ═══════════════════════════════════════════════════════════════════════════

    public List<String> getTerritoriGiocatore(String colore, Map<String, TerritoryState> mappa) {
        return mappa.entrySet().stream()
                .filter(e -> colore.equals(e.getValue().getColore()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<String> getTerritoriBordoNemico(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa) {
        return miei.stream()
                .filter(t -> getConfinanti(t).stream()
                        .anyMatch(c -> {
                            TerritoryState st = mappa.get(c);
                            return st != null && !colore.equals(st.getColore());
                        }))
                .collect(Collectors.toList());
    }

    private boolean isInObiettivo(String territorio, ObiettivoTarget obj) {
        if (obj == null) return false;
        if (obj.territoriSpecifici() != null &&
                obj.territoriSpecifici().contains(territorio)) return true;
        if (obj.continentiTarget() != null) {
            for (String cont : obj.continentiTarget()) {
                List<String> terrCont = RisikoBoardData.CONTINENTI.get(cont);
                if (terrCont != null && terrCont.contains(territorio)) return true;
            }
        }
        return false;
    }

    private boolean isInObiettivoContinente(String continente, ObiettivoTarget obj) {
        if (obj == null || obj.continentiTarget() == null) return false;
        return obj.continentiTarget().contains(continente);
    }

    private List<String> getConfinanti(String territorio) {
        return RisikoBoardData.ADIACENZE.getOrDefault(territorio, List.of());
    }

    private String lbl(String c) {
        return switch (c) {
            case "BLU"    -> "🔵 Blu";
            case "ROSSO"  -> "🔴 Rosso";
            case "VERDE"  -> "🟢 Verde";
            case "GIALLO" -> "🟡 Giallo";
            default       -> c;
        };
    }

    private String nom(String t) {
        if (t == null || t.isEmpty()) return "";
        String s = t.replace("_", " ");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
