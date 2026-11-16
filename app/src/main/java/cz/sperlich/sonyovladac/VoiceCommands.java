package cz.sperlich.sonyovladac;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Převod české věty na posloupnost tlačítek (id z MainActivity).
 * Text se převede na malá písmena bez diakritiky, pak se hledají klíčová slova.
 */
public final class VoiceCommands {
    private VoiceCommands() {}

    private static final String[] NUM_WORDS = {
            "nula", "jedna", "dva", "tri", "ctyri", "pet", "sest", "sedm", "osm", "devet", "deset"
    };

    private static final Pattern SEARCH = Pattern.compile(
            "^\\s*(vyhledej|vyhledat|hledej|hledat|najdi|napiš|napis|napsat)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** „hledej Pelíšky“ → „Pelíšky“ (text pro vyhledávací pole), jinak null. */
    public static String searchText(String raw) {
        Matcher m = SEARCH.matcher(raw);
        return m.matches() ? m.group(2).trim() : null;
    }

    private static final Pattern APP = Pattern.compile(
            "^\\s*(spust|spustit|otevri|otevrit|pust|zapni|aplikace)\\s+(.+)$");

    /** „spusť YouTube“ → „youtube“; null, pokud věta nezačíná slovesem spuštění. */
    public static String appQuery(String raw) {
        Matcher m = APP.matcher(normalize(raw).trim());
        if (!m.matches()) return null;
        String rest = m.group(2).replaceFirst("^aplikac[ei] ", "").trim();
        if (rest.isEmpty() || rest.matches(".*(zvuk|televiz|titulk|teletext|tv).*")) return null;
        return rest;
    }

    /** Najde aplikaci podle názvu (bez diakritiky, i část názvu). Vrací {název, uri} nebo null. */
    public static String[] matchApp(String query, List<String[]> apps) {
        String q = normalize(query).trim();
        if (q.isEmpty()) return null;
        String[] best = null;
        for (String[] a : apps) {
            String t = normalize(a[0]).trim();
            if (t.isEmpty()) continue;
            boolean hit = t.contains(q) || q.contains(t) || t.replace(" ", "").contains(q.replace(" ", ""));
            if (hit && (best == null || a[0].length() < best[0].length())) best = a;
        }
        return best;
    }

    public static List<String> parse(String raw) {
        String t = normalize(raw);
        List<String> out = new ArrayList<>();
        Integer n = number(t);

        // --- zvuk (pořadí je důležité: „vypni zvuk“ dřív než „vypni“) ---
        if (has(t, "vypni zvuk", "zapni zvuk", "mute", "bez zvuku", "ticho")) return one("mute");
        if (has(t, "zesil", "hlasit", "nahlas", "pridej")) return repeat("vol_up", n, 3);
        if (has(t, "ztlum", "ztis", "potis", "tiseji", "slabsi", "uber")) return repeat("vol_down", n, 3);

        // --- napájení ---
        if (has(t, "vypni", "zapni", "televiz", "napajeni")) return one("power");

        // --- programy ---
        if (has(t, "dalsi program", "dalsi kanal", "program nahoru", "kanal nahoru", "dalsi stanice"))
            return repeat("prog_up", n, 1);
        if (has(t, "predchozi program", "predchozi kanal", "program dolu", "kanal dolu", "predchozi stanice"))
            return repeat("prog_down", n, 1);
        if (has(t, "kanal", "program", "stanice", "prepni na") && n != null) return digits(n);
        if (has(t, "posledni", "jump", "minuly")) return one("jump");

        // --- aplikace a menu ---
        if (has(t, "netflix")) return one("netflix");
        if (has(t, "domu", "home", "hlavni nabid")) return one("home");
        if (has(t, "zpet", "back")) return one("back");
        if (has(t, "odejit", "exit", "konec")) return one("exit");
        if (has(t, "menu", "moznosti", "nabidk")) return one("action");
        if (has(t, "pruvodce", "guide", "epg")) return one("guide");
        if (has(t, "vstup", "hdmi", "zdroj", "input")) return one("input");
        if (has(t, "titulk")) return one("subtitle");
        if (has(t, "teletext", "text")) return one("teletext");
        if (has(t, "informac", "info")) return one("info");
        if (has(t, "vysilani", "televizni program", "tv")) return one("tv");

        // --- navigace ---
        if (has(t, "potvrd", "ok", "vyber", "enter")) return one("ok");
        if (has(t, "nahoru")) return repeat("up", n, 1);
        if (has(t, "dolu")) return repeat("down", n, 1);
        if (has(t, "doleva", "vlevo")) return repeat("left", n, 1);
        if (has(t, "doprava", "vpravo")) return repeat("right", n, 1);

        // --- přehrávání ---
        if (has(t, "pauza", "pozastav")) return one("pause");
        if (has(t, "prehraj", "pust", "play", "pokracuj")) return one("play");
        if (has(t, "stop", "zastav")) return one("stop");
        if (has(t, "pretoc dopredu", "dopredu", "rychle vpred")) return one("ff");
        if (has(t, "pretoc", "dozadu")) return one("rew");

        // --- barvy ---
        if (has(t, "cerven")) return one("red");
        if (has(t, "zelen")) return one("green");
        if (has(t, "zlut")) return one("yellow");
        if (has(t, "modr")) return one("blue");

        // samotné číslo = přepni kanál
        if (n != null && t.trim().matches("[0-9 ]+|" + String.join("|", NUM_WORDS))) return digits(n);

        return out;
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD);
        return " " + n.replaceAll("\\p{M}", "").replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
    }

    private static boolean has(String t, String... words) {
        for (String w : words) {
            // krátká slova hledáme jako celá slova, delší i jako začátek slova
            if (w.length() <= 3) {
                if (t.contains(" " + w + " ")) return true;
            } else if (t.contains(" " + w)) {
                return true;
            }
        }
        return false;
    }

    private static Integer number(String t) {
        Matcher m = Pattern.compile("\\d+").matcher(t);
        if (m.find()) {
            try { return Integer.parseInt(m.group()); } catch (NumberFormatException e) { return null; }
        }
        for (int i = 0; i < NUM_WORDS.length; i++) {
            if (t.contains(" " + NUM_WORDS[i] + " ")) return i;
        }
        if (t.contains(" jeden ") || t.contains(" jednu ")) return 1;
        if (t.contains(" dve ")) return 2;
        return null;
    }

    private static List<String> one(String id) {
        return Collections.singletonList(id);
    }

    private static List<String> repeat(String id, Integer n, int def) {
        int count = n == null ? def : Math.max(1, Math.min(n, 30));
        return new ArrayList<>(Collections.nCopies(count, id));
    }

    private static List<String> digits(int n) {
        List<String> out = new ArrayList<>();
        for (char c : String.valueOf(n).toCharArray()) out.add("num" + c);
        return out;
    }
}
