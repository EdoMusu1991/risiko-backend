package com.risiko.service;

import java.util.*;

/**
 * Dati statici della mappa di RisiKo: territori, adiacenze, continenti, obiettivi.
 * VERSIONE 2: Elvis (id=8) incluso con i suoi 3 continenti target.
 */
public class RisikoBoardData {

    public static final Map<String, List<String>> CONTINENTI;
    public static final Map<String, Integer>      BONUS_CONTINENTE;
    public static final Map<String, List<String>> ADIACENZE;
    public static final List<String>              TUTTI_TERRITORI;
    public static final Map<Integer, ObiettivoTarget> OBIETTIVI;

    static {
        // ── CONTINENTI ──────────────────────────────────────────────────────────
        Map<String, List<String>> c = new LinkedHashMap<>();
        c.put("nordamerica", Arrays.asList(
            "alaska","territori_nordovest","groenlandia","alberta","ontario",
            "quebec","stati_occidentali","stati_orientali","america_centrale"
        ));
        c.put("sudamerica", Arrays.asList("venezuela","peru","brasile","argentina"));
        c.put("europa", Arrays.asList(
            "islanda","gran_bretagna","europa_settentrionale","scandinavia",
            "ucraina","europa_occidentale","europa_meridionale"
        ));
        c.put("africa", Arrays.asList(
            "africa_settentrionale","egitto","africa_orientale","congo",
            "africa_meridionale","madagascar"
        ));
        c.put("asia", Arrays.asList(
            "medio_oriente","afghanistan","india","urali","siberia",
            "jakutsk","kamchatka","irkutsk","mongolia","cina",
            "asia_sudorientale","giappone"
        ));
        c.put("oceania", Arrays.asList(
            "indonesia","nuova_guinea","australia_occidentale","australia_orientale"
        ));
        CONTINENTI = Collections.unmodifiableMap(c);

        // ── BONUS CONTINENTE ────────────────────────────────────────────────────
        Map<String, Integer> b = new HashMap<>();
        b.put("nordamerica", 5);
        b.put("sudamerica",  2);
        b.put("europa",      5);
        b.put("africa",      3);
        b.put("asia",        7);
        b.put("oceania",     2);
        BONUS_CONTINENTE = Collections.unmodifiableMap(b);

        // ── TUTTI I TERRITORI ───────────────────────────────────────────────────
        List<String> tutti = new ArrayList<>();
        CONTINENTI.values().forEach(tutti::addAll);
        TUTTI_TERRITORI = Collections.unmodifiableList(tutti);

        // ── ADIACENZE ───────────────────────────────────────────────────────────
        Map<String, List<String>> a = new HashMap<>();
        a.put("alaska",                Arrays.asList("territori_nordovest","alberta","kamchatka"));
        a.put("territori_nordovest",   Arrays.asList("alaska","alberta","ontario","groenlandia"));
        a.put("groenlandia",           Arrays.asList("territori_nordovest","ontario","quebec","islanda"));
        a.put("alberta",               Arrays.asList("alaska","territori_nordovest","ontario","stati_occidentali"));
        a.put("ontario",               Arrays.asList("territori_nordovest","alberta","stati_occidentali","stati_orientali","quebec","groenlandia"));
        a.put("quebec",                Arrays.asList("ontario","stati_orientali","groenlandia"));
        a.put("stati_occidentali",     Arrays.asList("alberta","ontario","stati_orientali","america_centrale"));
        a.put("stati_orientali",       Arrays.asList("stati_occidentali","ontario","quebec","america_centrale"));
        a.put("america_centrale",      Arrays.asList("stati_occidentali","stati_orientali","venezuela"));
        a.put("venezuela",             Arrays.asList("america_centrale","peru","brasile"));
        a.put("peru",                  Arrays.asList("venezuela","brasile","argentina"));
        a.put("brasile",               Arrays.asList("venezuela","peru","argentina","africa_settentrionale"));
        a.put("argentina",             Arrays.asList("peru","brasile"));
        a.put("islanda",               Arrays.asList("groenlandia","gran_bretagna","scandinavia"));
        a.put("gran_bretagna",         Arrays.asList("islanda","europa_settentrionale","scandinavia","europa_occidentale"));
        a.put("scandinavia",           Arrays.asList("islanda","gran_bretagna","europa_settentrionale","ucraina"));
        a.put("europa_settentrionale", Arrays.asList("gran_bretagna","scandinavia","ucraina","europa_occidentale","europa_meridionale"));
        a.put("europa_occidentale",    Arrays.asList("gran_bretagna","europa_settentrionale","europa_meridionale","africa_settentrionale"));
        a.put("europa_meridionale",    Arrays.asList("europa_settentrionale","europa_occidentale","ucraina","egitto","africa_settentrionale","medio_oriente"));
        a.put("ucraina",               Arrays.asList("scandinavia","europa_settentrionale","europa_meridionale","medio_oriente","afghanistan","urali"));
        a.put("africa_settentrionale", Arrays.asList("europa_occidentale","europa_meridionale","brasile","egitto","africa_orientale","congo"));
        a.put("egitto",                Arrays.asList("europa_meridionale","africa_settentrionale","africa_orientale","medio_oriente"));
        a.put("africa_orientale",      Arrays.asList("egitto","africa_settentrionale","congo","africa_meridionale","madagascar","medio_oriente"));
        a.put("congo",                 Arrays.asList("africa_settentrionale","africa_orientale","africa_meridionale"));
        a.put("africa_meridionale",    Arrays.asList("congo","africa_orientale","madagascar"));
        a.put("madagascar",            Arrays.asList("africa_orientale","africa_meridionale"));
        a.put("medio_oriente",         Arrays.asList("europa_meridionale","ucraina","egitto","africa_orientale","afghanistan","india"));
        a.put("afghanistan",           Arrays.asList("ucraina","medio_oriente","india","cina","urali"));
        a.put("india",                 Arrays.asList("medio_oriente","afghanistan","cina","asia_sudorientale"));
        a.put("urali",                 Arrays.asList("ucraina","afghanistan","cina","siberia"));
        a.put("siberia",               Arrays.asList("urali","cina","mongolia","irkutsk","jakutsk"));
        a.put("jakutsk",               Arrays.asList("siberia","irkutsk","kamchatka"));
        a.put("kamchatka",             Arrays.asList("jakutsk","irkutsk","mongolia","giappone","alaska"));
        a.put("irkutsk",               Arrays.asList("siberia","jakutsk","kamchatka","mongolia"));
        a.put("mongolia",              Arrays.asList("cina","siberia","irkutsk","kamchatka","giappone"));
        a.put("cina",                  Arrays.asList("mongolia","siberia","urali","afghanistan","india","asia_sudorientale"));
        a.put("asia_sudorientale",     Arrays.asList("india","cina","indonesia"));
        a.put("giappone",              Arrays.asList("kamchatka","mongolia"));
        a.put("indonesia",             Arrays.asList("asia_sudorientale","nuova_guinea","australia_occidentale"));
        a.put("nuova_guinea",          Arrays.asList("indonesia","australia_occidentale","australia_orientale"));
        a.put("australia_occidentale", Arrays.asList("indonesia","nuova_guinea","australia_orientale"));
        a.put("australia_orientale",   Arrays.asList("nuova_guinea","australia_occidentale"));
        ADIACENZE = Collections.unmodifiableMap(a);

        // ── OBIETTIVI ────────────────────────────────────────────────────────────
        List<String> urss = Arrays.asList("urali","siberia","jakutsk","kamchatka","irkutsk","mongolia");

        Map<Integer, ObiettivoTarget> obj = new HashMap<>();
        obj.put(1,  new ObiettivoTarget(1,  "Letto",                   List.of("europa","oceania"),                       null, 0));
        obj.put(2,  new ObiettivoTarget(2,  "Elefante del Circo",      List.of("africa","asia"),                          null, 0));
        obj.put(3,  new ObiettivoTarget(3,  "Ciclista",                List.of("nordamerica","africa"),                   null, 0));
        obj.put(4,  new ObiettivoTarget(4,  "Giraffa",                 List.of("africa","sudamerica"),                    null, 0));
        obj.put(5,  new ObiettivoTarget(5,  "Granchio",                List.of("sudamerica","europa"),                    null, 0));
        obj.put(6,  new ObiettivoTarget(6,  "Formula 1",               List.of("asia","oceania"),                         null, 0));
        obj.put(7,  new ObiettivoTarget(7,  "Befana",                  List.of("africa","sudamerica","oceania"),           null, 0));
        // ✅ ELVIS: Nord America + Sud America + Europa (3 continenti)
        obj.put(8,  new ObiettivoTarget(8,  "Elvis",                   List.of("nordamerica","sudamerica","europa"),       null, 0));
        obj.put(9,  new ObiettivoTarget(9,  "Dromedario",              List.of("europa","asia"),                          null, 0));
        obj.put(10, new ObiettivoTarget(10, "Piovra",                  List.of("nordamerica","sudamerica"),                null, 0));
        obj.put(11, new ObiettivoTarget(11, "Lupo",                    List.of("asia"),                                   null, 0));
        obj.put(12, new ObiettivoTarget(12, "Tappeto",                 null,                                              null, 24));
        obj.put(13, new ObiettivoTarget(13, "Guerra Fredda",           List.of("nordamerica"),                            urss, 0));
        obj.put(14, new ObiettivoTarget(14, "Motorino",                List.of("sudamerica","oceania"),                   null, 0));
        obj.put(15, new ObiettivoTarget(15, "Aragosta con Pesciolino", List.of("asia","sudamerica"),                      null, 0));
        obj.put(16, new ObiettivoTarget(16, "Locomotiva",              List.of("europa","nordamerica"),                   null, 0));
        OBIETTIVI = Collections.unmodifiableMap(obj);
    }

    // ── RECORD OBIETTIVO ────────────────────────────────────────────────────────
    public record ObiettivoTarget(
        int            id,
        String         nome,
        List<String>   continentiTarget,
        List<String>   territoriSpecifici,
        int            minimoTerritoriTarget
    ) {}

    // ── UTILITY ─────────────────────────────────────────────────────────────────
    public static String getContinente(String territorio) {
        for (Map.Entry<String, List<String>> e : CONTINENTI.entrySet()) {
            if (e.getValue().contains(territorio)) return e.getKey();
        }
        return null;
    }

    public static String nomeLeggibile(String territorio) {
        if (territorio == null) return "";
        String s = territorio.replace("_", " ");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Conta quanti territori di un continente appartengono al colore dato */
    public static long countTerritoriInContinente(String continente, String colore,
                                                   Map<String, ?> mappa, java.util.function.Function<Object,String> getColore) {
        List<String> terr = CONTINENTI.getOrDefault(continente, List.of());
        return terr.stream().filter(t -> {
            Object s = mappa.get(t);
            return s != null && colore.equals(getColore.apply(s));
        }).count();
    }
}
