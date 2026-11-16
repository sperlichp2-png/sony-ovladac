package cz.sperlich.sonyovladac;

/**
 * Kodér protokolu Sony SIRC pro ConsumerIrManager (časy v mikrosekundách).
 *
 * Rámec: hlavička 2400 µs puls + 600 µs mezera, pak bity od nejnižšího:
 *   "1" = 1200 µs puls, "0" = 600 µs puls, za každým 600 µs mezera.
 *   12 bitů = 7 bitů příkaz + 5 bitů adresa
 *   15 bitů = 7 bitů příkaz + 8 bitů adresa
 *   20 bitů = 7 bitů příkaz + 5 bitů adresa + 8 bitů rozšíření
 *            (zde adresa = spodních 5 bitů, rozšíření = horních 8 bitů hodnoty)
 * Rámec trvá 45 ms od začátku k začátku; televize chce aspoň 3 opakování.
 */
public final class SircEncoder {
    public static final int CARRIER_HZ = 40000;
    private static final int FRAME_US = 45000;

    private SircEncoder() {}

    public static int[] pattern(int bits, int address, int command, int frames) {
        int[] one = frame(bits, address, command);
        int[] out = new int[one.length * frames];
        for (int f = 0; f < frames; f++) {
            System.arraycopy(one, 0, out, f * one.length, one.length);
        }
        return out;
    }

    static int[] frame(int bits, int address, int command) {
        long data = command & 0x7F;
        if (bits == 12) {
            data |= (long) (address & 0x1F) << 7;
        } else if (bits == 15) {
            data |= (long) (address & 0xFF) << 7;
        } else { // 20
            data |= (long) (address & 0x1F) << 7;
            data |= (long) ((address >> 5) & 0xFF) << 12;
        }

        int[] p = new int[2 + bits * 2];
        int i = 0, total = 0;
        p[i++] = 2400; p[i++] = 600;
        total += 3000;
        for (int b = 0; b < bits; b++) {
            int mark = ((data >> b) & 1) == 1 ? 1200 : 600;
            p[i++] = mark;
            p[i++] = 600;
            total += mark + 600;
        }
        // poslední mezeru prodloužíme, aby rámec trval 45 ms
        p[p.length - 1] += Math.max(0, FRAME_US - total);
        return p;
    }
}
