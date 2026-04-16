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

    private int trovaTris(List<Carta> mano) {
        if (mano.size() < 3) return 0;
        List<Carta> prime3 = mano.subList(0, 3);
        Map<Simbolo, Long> count = prime3.stream()
                .collect(Collectors.groupingBy(Carta::simbolo, Collectors.counting()));

        long jolly = count.getOrDefault(Simbolo.JOLLY, 0L);
        if (jolly >= 1) {
            boolean ha2Uguali = count.entrySet().stream()
                    .filter(e -> e.getKey() != Simbolo.JOLLY)
                    .anyMatch(e -> e.getValue() >= 2);
            if (ha2Uguali) return 12; // Jolly + 2 uguali
        }
        if (count.values().stream().anyMatch(v -> v >= 3)) return 8; // 3 uguali
        long tipiDiversi = count.entrySet().stream()
                .filter(e -> e.getKey() != Simbolo.JOLLY && e.getValue() >= 1).count();
        if (tipiDiversi >= 3) return 10; // Fante + Cannone + Cavallo
        return 0;
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
        final int CAP_ARMATE_TERRITORIO = 25;
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

        int punteggioMio = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int punteggioMassAvversari = getMassimoPunteggioAvversari(colore, mappa);
        boolean sonoInVantaggio = punteggioMio > punteggioMassAvversari;
        int maxConquiste = sonoInVantaggio ? 2 : Integer.MAX_VALUE;

        // ── Strategia attacchi: dipende da turno, posizione in classifica, endgame ──
        List<String> mieiPerConteggio = getTerritoriGiocatore(colore, mappa);
        boolean sottoSogliaTerritoriMinimi = mieiPerConteggio.size() < 9;

        // Calcola posizione in classifica (punteggio stimato)
        int punteggioMioAtk = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int punteggioMaxAvv = getMassimoPunteggioAvversari(colore, mappa);
        boolean sonoInTesta = punteggioMioAtk > punteggioMaxAvv;
        boolean sonoIndietro = punteggioMioAtk < punteggioMaxAvv - 10;

        // Endgame: dal turno 30 la partita finisce presto → massima aggressività
        boolean endgame = turno >= 30;
        boolean preEndgame = turno >= 20 && turno < 30;

        int maxAttacchi;
        double moltiplicatoreSoglia;

        if (sottoSogliaTerritoriMinimi) {
            // Emergenza territori: attacca a tutti i costi
            maxAttacchi = 8;
            moltiplicatoreSoglia = 1.5;
        } else if (endgame && sonoIndietro) {
            // Endgame in svantaggio: devo recuperare punti urgentemente
            maxAttacchi = 6;
            moltiplicatoreSoglia = 1.8;
        } else if (endgame && sonoInTesta) {
            // Endgame in vantaggio: 1 attacco max per non rischiare, preparo sdadata
            maxAttacchi = 1;
            moltiplicatoreSoglia = 3.0;
        } else if (endgame) {
            // Endgame in pareggio: attacca moderatamente
            maxAttacchi = 3;
            moltiplicatoreSoglia = 2.0;
        } else if (preEndgame) {
            maxAttacchi = 2;
            moltiplicatoreSoglia = 2.5;
        } else if (turno <= 8) {
            maxAttacchi = 5;
            moltiplicatoreSoglia = 2.0;
        } else if (turno <= 20) {
            maxAttacchi = 3;
            moltiplicatoreSoglia = 2.5;
        } else {
            maxAttacchi = 1;
            moltiplicatoreSoglia = 3.0;
        }

        // Territori già tentati e falliti in questo turno — non riprovarli
        Set<String> giaTentati = new HashSet<>();
        int fallitiConsecutivi = 0;

        for (int tentativo = 0; tentativo < maxAttacchi; tentativo++) {
            List<String> miei = getTerritoriGiocatore(colore, mappa);

            if (miei.size() <= SOGLIA_MIN_TERRITORI) break;
            if (territorConquistatiNelTurno.getOrDefault(colore, 0) >= maxConquiste
                    && sonoInVantaggio) break;
            // Dopo 3 fallimenti consecutivi senza conquiste, smette
            if (fallitiConsecutivi >= 3) break;

            AttaccoCandidate best = trovaMigliorAttacco(colore, miei, mappa, obj, giaTentati, moltiplicatoreSoglia, turno);
            if (best == null) break;

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
        if (mieiTotali < 9) p += 400;

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
