package cz.sperlich.sonyovladac;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Psaní do vyhledávání Netflixu (česká klávesnice na TV) pomocí šipek a OK.
 *
 * Rozložení podle fotky (6 sloupců):
 *   řádek 0: [ mezera ][ smazat ]   (dvě široká tlačítka)
 *   1: a á b c č d
 *   2: ď e é ě f g
 *   3: h i í j k l
 *   4: m n ň o ó p
 *   5: q r ř s š t
 *   6: ť u ú ů v w
 *   7: x y ý z ž
 *   8: 1 2 3 4 5 6
 *   9: 7 8 9 0
 *
 * Předpoklad: na začátku je kurzor na písmenu „a“.
 * Výsledek je posloupnost id tlačítek: up/down/left/right/ok.
 */
public final class NetflixKeyboard {
    private NetflixKeyboard() {}

    static final String[][] ROWS = {
            null, // řádek 0 = mezera / smazat
            {"a", "á", "b", "c", "č", "d"},
            {"ď", "e", "é", "ě", "f", "g"},
            {"h", "i", "í", "j", "k", "l"},
            {"m", "n", "ň", "o", "ó", "p"},
            {"q", "r", "ř", "s", "š", "t"},
            {"ť", "u", "ú", "ů", "v", "w"},
            {"x", "y", "ý", "z", "ž"},
            {"1", "2", "3", "4", "5", "6"},
            {"7", "8", "9", "0"},
    };

    public static List<String> moves(String text) {
        List<String> out = new ArrayList<>();
        int r = 1, c = 0; // start na „a“
        String t = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFC);
        for (int i = 0; i < t.length(); i++) {
            String ch = String.valueOf(t.charAt(i));
            if (ch.equals(" ")) {
                // ke sloupci 0, nahoru na řádek 1 a ještě o jeden výš na mezeru
                if (c >= ROWS[r].length) c = ROWS[r].length - 1;
                while (c > 0) { out.add("left"); c--; }
                while (r > 1) {
                    out.add("up");
                    r--;
                }
                out.add("up");
                out.add("ok");
                // zpět dolů do tabulky; po široké mezeře zarovnat na sloupec 0
                out.add("down");
                out.add("left");
                out.add("left");
                r = 1; c = 0;
                continue;
            }
            int[] pos = find(ch);
            if (pos == null) {
                // znak bez háčku/čárky, jinak přeskočit (interpunkce apod.)
                String plain = Normalizer.normalize(ch, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                pos = plain.isEmpty() ? null : find(plain);
                if (pos == null) continue;
            }
            int tr = pos[0], tc = pos[1];
            // 1) pokud to jde, nejdřív vodorovně v aktuálním řádku
            if (tc < ROWS[r].length) {
                while (c < tc) { out.add("right"); c++; }
                while (c > tc) { out.add("left"); c--; }
            }
            // 2) svisle po jednom řádku, u kratších řádků nejdřív posunout doleva
            while (r != tr) {
                int next = r < tr ? r + 1 : r - 1;
                int max = ROWS[next].length - 1;
                while (c > max) { out.add("left"); c--; }
                out.add(r < tr ? "down" : "up");
                r = next;
            }
            // 3) dorovnat sloupec v cílovém řádku
            while (c < tc) { out.add("right"); c++; }
            while (c > tc) { out.add("left"); c--; }
            out.add("ok");
        }
        return out;
    }

    private static int[] find(String ch) {
        for (int r = 1; r < ROWS.length; r++) {
            for (int c = 0; c < ROWS[r].length; c++) {
                if (ROWS[r][c].equals(ch)) return new int[]{r, c};
            }
        }
        return null;
    }
}
