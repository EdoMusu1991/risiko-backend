package com.risiko.service;

import java.util.*;

/**
 * Dati statici della mappa di RisiKo: territori, adiacenze, continenti, obiettivi.
 * Obiettivi aggiornati per corrispondere esattamente al database (territori specifici).
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
        a.put("africa_orientale",      Arrays.asList("egitto","africa_settentrionale","congo","africa_meridionale","madagascar"));
        a.put("congo",                 Arrays.asList("africa_settentrionale","africa_orientale","africa_meridionale"));
        a.put("africa_meridionale",    Arrays.asList("congo","africa_orientale","madagascar"));
        a.put("madagascar",            Arrays.asList("africa_orientale","africa_meridionale"));
        a.put("medio_oriente",         Arrays.asList("europa_meridionale","ucraina","egitto","afghanistan","india"));
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

        // ── OBIETTIVI ─────────────────────────────────────────────────────────
        // Territori specifici mappati con i nomi backend (diversi dal database):
        //   stati_uniti_occidentali → stati_occidentali
        //   stati_uniti_orientali   → stati_orientali
        //   territori_del_nord_ovest→ territori_nordovest
        //   africa_del_nord         → africa_settentrionale
        //   africa_del_sud          → africa_meridionale
        //   jacuzia                 → jakutsk
        //   cita                    → irkutsk
        //   siam                    → asia_sudorientale

        Map<Integer, ObiettivoTarget> obj = new HashMap<>();

        // 1. Letto — Nord America + Sud America + parte Asia/Africa/Oceania
        obj.put(1, new ObiettivoTarget(1, "Letto", null,
                Arrays.asList(
                        "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                        "stati_occidentali","stati_orientali","territori_nordovest",
                        "argentina","brasile","peru","venezuela",
                        "australia_orientale","nuova_guinea","indonesia","asia_sudorientale","india","medio_oriente",
                        "africa_settentrionale","congo","egitto","africa_orientale"
                ), 0));

        // 2. Elefante — parte Nord America + Europa + Asia centrale + Africa
        obj.put(2, new ObiettivoTarget(2, "Elefante del Circo", null,
                Arrays.asList(
                        "quebec","groenlandia","ontario","islanda","stati_orientali",
                        "europa_occidentale","europa_meridionale","europa_settentrionale",
                        "gran_bretagna","scandinavia","ucraina",
                        "afghanistan","urali","medio_oriente",
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar"
                ), 0));

        // 3. Ciclista — Europa + Asia (parziale) + Oceania + Africa
        obj.put(3, new ObiettivoTarget(3, "Ciclista", null,
                Arrays.asList(
                        "europa_occidentale","europa_meridionale","europa_settentrionale",
                        "gran_bretagna","islanda","scandinavia","ucraina",
                        "afghanistan","urali","medio_oriente","india","asia_sudorientale",
                        "australia_occidentale","australia_orientale","nuova_guinea","indonesia",
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar"
                ), 0));

        // 4. Giraffa — Nord America + parte Europa + Africa
        obj.put(4, new ObiettivoTarget(4, "Giraffa", null,
                Arrays.asList(
                        "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                        "stati_occidentali","stati_orientali","territori_nordovest",
                        "europa_meridionale","europa_settentrionale","gran_bretagna","islanda","scandinavia","ucraina",
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar"
                ), 0));

        // 5. Granchio — Nord America + parte Europa + Asia + Oceania
        obj.put(5, new ObiettivoTarget(5, "Granchio", null,
                Arrays.asList(
                        "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                        "stati_occidentali","stati_orientali","territori_nordovest",
                        "islanda","scandinavia","ucraina",
                        "afghanistan","urali","medio_oriente","cina","india","asia_sudorientale",
                        "australia_occidentale","australia_orientale","nuova_guinea","indonesia"
                ), 0));

        // 6. Formula 1 — Europa + Asia (parziale) + Sud America + Oceania + Africa (parziale)
        obj.put(6, new ObiettivoTarget(6, "Formula 1", null,
                Arrays.asList(
                        "europa_occidentale","europa_meridionale","europa_settentrionale",
                        "gran_bretagna","islanda","scandinavia","ucraina",
                        "afghanistan","medio_oriente","india","asia_sudorientale",
                        "argentina","brasile","peru","venezuela",
                        "australia_occidentale","australia_orientale","nuova_guinea","indonesia",
                        "africa_settentrionale","egitto","africa_orientale"
                ), 0));

        // 7. Befana — Sud America + Africa + Asia orientale + Oceania (parziale)
        obj.put(7, new ObiettivoTarget(7, "Befana", null,
                Arrays.asList(
                        "argentina","brasile","peru","venezuela",
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar",
                        "afghanistan","urali","medio_oriente","india","asia_sudorientale","cina",
                        "mongolia","jakutsk","irkutsk","siberia","kamchatka","giappone","indonesia"
                ), 0));

        // 8. Elvis — Nord America + Sud America + Europa + Kamchatka/Giappone
        obj.put(8, new ObiettivoTarget(8, "Elvis", null,
                Arrays.asList(
                        "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                        "stati_occidentali","stati_orientali","territori_nordovest",
                        "argentina","brasile","peru","venezuela",
                        "europa_occidentale","europa_meridionale","europa_settentrionale",
                        "gran_bretagna","islanda","scandinavia","ucraina",
                        "kamchatka","giappone"
                ), 0));

        // 9. Dromedario con mosca — Europa + Asia orientale completa + Indonesia
        obj.put(9, new ObiettivoTarget(9, "Dromedario", null,
                Arrays.asList(
                        "europa_occidentale","europa_meridionale","europa_settentrionale",
                        "gran_bretagna","islanda","scandinavia","ucraina",
                        "afghanistan","urali","medio_oriente","india","asia_sudorientale","cina",
                        "mongolia","jakutsk","irkutsk","siberia","kamchatka","giappone","indonesia"
                ), 0));

        // 10. Piovra — Nord America + Europa + Asia settentrionale
        obj.put(10, new ObiettivoTarget(10, "Piovra", null,
                Arrays.asList(
                        "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                        "stati_occidentali","stati_orientali","territori_nordovest",
                        "europa_occidentale","europa_meridionale","europa_settentrionale",
                        "gran_bretagna","islanda","scandinavia","ucraina",
                        "urali","siberia","kamchatka","giappone","jakutsk"
                ), 0));

        // 11. Lupo (Siberiana) — Europa + Asia centrale + Africa + Sud America
        obj.put(11, new ObiettivoTarget(11, "Lupo", null,
                Arrays.asList(
                        "europa_occidentale","europa_meridionale","europa_settentrionale",
                        "gran_bretagna","islanda","scandinavia","ucraina",
                        "siberia","urali","afghanistan","medio_oriente",
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar",
                        "argentina","brasile","peru","venezuela"
                ), 0));

        // 12. Tappeto — Africa + Asia + Europa (parziale)
        obj.put(12, new ObiettivoTarget(12, "Tappeto", null,
                Arrays.asList(
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar",
                        "afghanistan","urali","medio_oriente","india","asia_sudorientale","cina",
                        "mongolia","jakutsk","irkutsk","siberia","kamchatka","giappone","indonesia",
                        "europa_meridionale","ucraina"
                ), 0));

        // 13. Guerra Fredda — Nord America + Asia orientale completa
        obj.put(13, new ObiettivoTarget(13, "Guerra Fredda", null,
                Arrays.asList(
                        "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                        "stati_occidentali","stati_orientali","territori_nordovest",
                        "afghanistan","urali","medio_oriente","india","asia_sudorientale","cina",
                        "mongolia","jakutsk","siberia","irkutsk","kamchatka","giappone"
                ), 0));

        // 14. Motorino — Sud America + Africa + Oceania + Asia (parziale)
        obj.put(14, new ObiettivoTarget(14, "Motorino", null,
                Arrays.asList(
                        "argentina","brasile","peru","venezuela",
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar",
                        "australia_occidentale","australia_orientale","nuova_guinea","indonesia",
                        "europa_occidentale","europa_meridionale","medio_oriente","india","asia_sudorientale","cina",
                        "mongolia","irkutsk","giappone"
                ), 0));

        // 15. Aragosta e pesciolino — Alaska/Alberta + Africa (parziale) + Asia + Oceania
        obj.put(15, new ObiettivoTarget(15, "Aragosta con Pesciolino", null,
                Arrays.asList(
                        "alaska","alberta",
                        "egitto","congo","africa_orientale","africa_meridionale","madagascar",
                        "afghanistan","urali","medio_oriente","india","asia_sudorientale","cina",
                        "mongolia","jakutsk","siberia","kamchatka","giappone","irkutsk","indonesia",
                        "australia_occidentale","australia_orientale","nuova_guinea"
                ), 0));

        // 16. Locomotiva — Nord America + Sud America + Africa + Europa (parziale)
        obj.put(16, new ObiettivoTarget(16, "Locomotiva", null,
                Arrays.asList(
                        "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                        "stati_occidentali","stati_orientali","territori_nordovest",
                        "argentina","brasile","peru","venezuela",
                        "africa_settentrionale","egitto","congo","africa_orientale","africa_meridionale","madagascar",
                        "europa_occidentale","ucraina","europa_meridionale"
                ), 0));

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

    public static long countTerritoriInContinente(String continente, String colore,
                                                  Map<String, ?> mappa,
                                                  java.util.function.Function<Object,String> getColore) {
        List<String> terr = CONTINENTI.getOrDefault(continente, List.of());
        return terr.stream().filter(t -> {
            Object s = mappa.get(t);
            return s != null && colore.equals(getColore.apply(s));
        }).count();
    }
}
