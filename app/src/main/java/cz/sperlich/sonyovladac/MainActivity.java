package cz.sperlich.sonyovladac;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.speech.RecognizerIntent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ovladač pro Sony Bravia (KD-55XD7005) přes IR vysílač telefonu.
 * Protokol Sony SIRC, nosná 40 kHz.
 *
 * Kódy: "kategorie" (adresa) + "data" (příkaz) podle tabulky Sony
 * (pro-bravia.sony.net, SIRCS Code List). Kategorie 0x01 = 12bitový rámec,
 * kategorie > 0x1F = 15bitový rámec.
 *
 * Režim úprav (tlačítko ✎ nahoře): klepnutím na tlačítko se otevře editor
 * kódu – lze změnit počet bitů, adresu i příkaz a hned vyzkoušet.
 */
public class MainActivity extends Activity {

    // ---------- styly tlačítek ----------
    static final int S_NORMAL = 0, S_POWER = 1, S_RED = 2, S_GREEN = 3, S_YELLOW = 4,
            S_BLUE = 5, S_NETFLIX = 6, S_OK = 7, S_DPAD = 8, S_ROCKER = 9;

    static class Key {
        final String id, label;
        final int defBits, defAddr, defCmd, style;
        final boolean repeat;
        final float textSp;

        Key(String id, String label, int bits, int addr, int cmd, int style, boolean repeat, float textSp) {
            this.id = id; this.label = label; this.defBits = bits; this.defAddr = addr;
            this.defCmd = cmd; this.style = style; this.repeat = repeat; this.textSp = textSp;
        }
    }

    static Key k(String id, String label, int bits, int addr, int cmd) {
        return new Key(id, label, bits, addr, cmd, S_NORMAL, false, 13);
    }
    static Key k(String id, String label, int bits, int addr, int cmd, int style) {
        return new Key(id, label, bits, addr, cmd, style, false, 13);
    }
    static Key kr(String id, String label, int bits, int addr, int cmd, int style, float sp) {
        return new Key(id, label, bits, addr, cmd, style, true, sp);
    }

    private ConsumerIrManager ir;
    private Vibrator vibrator;
    private SharedPreferences prefs;
    private Handler irHandler;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean editMode = false;
    private final Map<String, Key> keys = new HashMap<>();
    private static final int REQ_VOICE = 42;
    private TextView editToggle;
    private TextView modeLabel;
    private SharedPreferences settings;
    private final java.util.concurrent.atomic.AtomicBoolean wifiBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private float dp;

    // ---------- životní cyklus ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("kody", MODE_PRIVATE);
        settings = getSharedPreferences("nastaveni", MODE_PRIVATE);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        HandlerThread t = new HandlerThread("ir");
        t.start();
        irHandler = new Handler(t.getLooper());

        getWindow().setStatusBarColor(Color.parseColor("#121212"));
        getWindow().setNavigationBarColor(Color.parseColor("#121212"));
        setContentView(buildUi());

        if (!isWifi() && !hasIr()) {
            Toast.makeText(this, "Telefon nemá IR vysílač – zapni Wi-Fi režim v ⚙.",
                    Toast.LENGTH_LONG).show();
        } else if (isWifi() && wifiIp().isEmpty()) {
            showSettings();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        irHandler.getLooper().quitSafely();
    }

    // ---------- rozložení podle originálního ovladače ----------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#121212"));
        scroll.setFillViewport(true);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = px(14);
        body.setPadding(pad, px(10), pad, px(24));

        GradientDrawable shell = new GradientDrawable();
        shell.setColor(Color.parseColor("#1C1C1C"));
        shell.setCornerRadius(px(28));
        shell.setStroke(px(1), Color.parseColor("#2A2A2A"));

        LinearLayout remote = new LinearLayout(this);
        remote.setOrientation(LinearLayout.VERTICAL);
        remote.setBackground(shell);
        remote.setPadding(px(14), px(14), px(14), px(18));

        // horní lišta aplikace
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("SONY");
        title.setTextColor(Color.parseColor("#8A8A8A"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.15f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modeLabel = new TextView(this);
        modeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        modeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        modeLabel.setPadding(px(10), px(2), px(10), px(2));
        modeLabel.setOnClickListener(v -> showSettings());
        refreshModeLabel();
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = px(8);
        top.addView(modeLabel, mlp);

        editToggle = new TextView(this);
        editToggle.setText("✎");
        editToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        editToggle.setPadding(px(12), px(4), px(12), px(4));
        editToggle.setOnClickListener(v -> {
            editMode = !editMode;
            refreshEditToggle();
            Toast.makeText(this, editMode
                    ? "Režim úprav: klepni na tlačítko a uprav jeho kód"
                    : "Režim úprav vypnut", Toast.LENGTH_SHORT).show();
        });
        refreshEditToggle();

        TextView mic = new TextView(this);
        mic.setText("🎤");
        mic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mic.setPadding(px(10), px(4), px(10), px(4));
        mic.setOnClickListener(v -> startVoice());
        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        gear.setTextColor(Color.parseColor("#8A8A8A"));
        gear.setPadding(px(10), px(4), px(10), px(4));
        gear.setOnClickListener(v -> showSettings());
        top.addView(mic);
        top.addView(gear);
        top.addView(editToggle);
        body.addView(top);
        body.addView(remote, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int B12 = 12, B15 = 15;

        // 1) vstup + napájení
        addRow(remote, 48,
                k("input", "⇥  VSTUP", B12, 0x01, 0x25),
                null,
                k("power", "⏻", B12, 0x01, 0x15, S_POWER));

        // 2) DIGITAL/ANALOG, TV/RADIO, SYNC MENU
        addRow(remote, 40,
                small(k("dig_ana", "DIGITAL/\nANALOG", B15, 0x77, 0x0D)),
                small(k("tv_radio", "TV/\nRADIO", B15, 0x1A, 0x57)),
                small(k("sync", "SYNC\nMENU", B15, 0x1A, 0x58)));
        gap(remote, 6);

        // 3) číslice
        String[] sub = {"1 .", "2 abc", "3 def", "4 ghi", "5 jkl", "6 mno", "7 pqrs", "8 tuv", "9 wxyz"};
        for (int r = 0; r < 3; r++) {
            Key[] row = new Key[3];
            for (int c = 0; c < 3; c++) {
                int n = r * 3 + c; // 0..8 -> číslice 1..9
                row[c] = new Key("num" + (n + 1), sub[n], B12, 0x01, n, S_NORMAL, false, 17);
            }
            addRow(remote, 46, row);
        }
        addRow(remote, 46,
                k("exit", "EXIT", B12, 0x01, 0x63),
                new Key("num0", "0 ␣", B12, 0x01, 0x09, S_NORMAL, false, 17),
                k("teletext", "▤ TEXT", B12, 0x01, 0x3F));
        gap(remote, 6);

        // 4) barevná tlačítka
        addRow(remote, 30,
                k("red", "", B15, 0x97, 0x25, S_RED),
                k("green", "", B15, 0x97, 0x26, S_GREEN),
                k("yellow", "", B15, 0x97, 0x27, S_YELLOW),
                k("blue", "", B15, 0x97, 0x24, S_BLUE));

        // 5) i+, NETFLIX, HELP
        addRow(remote, 42,
                k("info", "i+", B12, 0x01, 0x3A),
                k("netflix", "NETFLIX", B15, 0x1A, 0x7C, S_NETFLIX),
                k("help", "HELP", B15, 0xC4, 0x4D));
        gap(remote, 8);

        // 6) navigační kříž
        addRow(remote, 40, small(k("action", "ACTION MENU", B15, 0x97, 0x36)));
        addRow(remote, 56,
                small(k("tv", "TV", B12, 0x01, 0x24)),
                kr("up", "▲", B12, 0x01, 0x74, S_DPAD, 18),
                small(k("guide", "GUIDE", B15, 0xA4, 0x5B)));
        addRow(remote, 64,
                kr("left", "◀", B12, 0x01, 0x34, S_DPAD, 18),
                k("ok", "OK", B12, 0x01, 0x65, S_OK),
                kr("right", "▶", B12, 0x01, 0x33, S_DPAD, 18));
        addRow(remote, 56,
                small(k("back", "↩ BACK", B15, 0x97, 0x23)),
                kr("down", "▼", B12, 0x01, 0x75, S_DPAD, 18),
                small(k("home", "HOME", B12, 0x01, 0x60)));
        addRow(remote, 40, small(k("discover", "DISCOVER", B15, 0xC4, 0x4F)));
        gap(remote, 10);

        // 7) hlasitost / jump+mute / programy
        addRow(remote, 58,
                kr("vol_up", "◢  +", B12, 0x01, 0x12, S_ROCKER, 20),
                k("jump", "⟲ JUMP", B12, 0x01, 0x3B),
                kr("prog_up", "PROG  +", B12, 0x01, 0x10, S_ROCKER, 18));
        addRow(remote, 58,
                kr("vol_down", "◢  −", B12, 0x01, 0x13, S_ROCKER, 20),
                k("mute", "🔇", B12, 0x01, 0x14),
                kr("prog_down", "PROG  −", B12, 0x01, 0x11, S_ROCKER, 18));
        gap(remote, 8);

        // 8) audio, titulky
        addRow(remote, 40,
                k("audio", "AUDIO", B12, 0x01, 0x17),
                null,
                k("subtitle", "▭ TITULKY", B15, 0x97, 0x28));
        gap(remote, 6);

        // 9) přehrávání
        addRow(remote, 46,
                k("rew", "⏪", B15, 0x97, 0x1B),
                k("play", "▶", B15, 0x97, 0x1A),
                k("ff", "⏩", B15, 0x97, 0x1C));
        addRow(remote, 46,
                k("prev", "⏮", B15, 0x97, 0x3C),
                k("pause", "⏸", B15, 0x97, 0x19),
                k("next", "⏭", B15, 0x97, 0x3D));
        addRow(remote, 46,
                k("rec", "● REC", B15, 0x97, 0x20),
                k("stop", "■", B15, 0x97, 0x18),
                small(k("title_list", "TITLE LIST", B15, 0x1A, 0x5A)));

        scroll.addView(body);
        return scroll;
    }

    private Key small(Key key) {
        return new Key(key.id, key.label, key.defBits, key.defAddr, key.defCmd, key.style, key.repeat, 11);
    }

    private void refreshEditToggle() {
        editToggle.setTextColor(editMode ? Color.parseColor("#FFB300") : Color.parseColor("#6A6A6A"));
    }

    private void gap(LinearLayout parent, int heightDp) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, px(heightDp)));
    }

    private void addRow(LinearLayout parent, int heightDp, Key... keys) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Key key : keys) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(heightDp), 1);
            int m = px(5);
            lp.setMargins(m, m, m, m);
            if (key == null) {
                row.addView(new View(this), lp);
            } else {
                row.addView(makeButton(key), lp);
            }
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------- vzhled a chování tlačítka ----------

    private TextView makeButton(final Key key) {
        keys.put(key.id, key);
        TextView b = new TextView(this);
        b.setText(key.label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, key.textSp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.parseColor("#E8E8E8"));
        b.setIncludeFontPadding(false);
        b.setClickable(true);

        int bg = Color.parseColor("#2B2B2B");
        float radius = px(12);
        switch (key.style) {
            case S_POWER: bg = Color.parseColor("#1FA463"); b.setTextColor(Color.WHITE);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22); radius = px(30); break;
            case S_RED: bg = Color.parseColor("#E53935"); radius = px(15); break;
            case S_GREEN: bg = Color.parseColor("#43A047"); radius = px(15); break;
            case S_YELLOW: bg = Color.parseColor("#FDD835"); radius = px(15); break;
            case S_BLUE: bg = Color.parseColor("#81D4FA"); radius = px(15); break;
            case S_NETFLIX: bg = Color.parseColor("#F5F5F5");
                b.setTextColor(Color.parseColor("#E50914")); break;
            case S_OK: bg = Color.parseColor("#3A3A3A"); radius = px(32); break;
            case S_DPAD: bg = Color.parseColor("#242424"); break;
            case S_ROCKER: bg = Color.parseColor("#262626"); radius = px(18); break;
            default: break;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(radius);
        shape.setStroke(px(1), Color.parseColor("#000000"));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#55FFFFFF")), shape, mask));

        if (key.repeat) {
            b.setOnTouchListener(new View.OnTouchListener() {
                Runnable repeater;
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.setPressed(true);
                            if (editMode) return true;
                            press(key);
                            repeater = new Runnable() {
                                @Override public void run() {
                                    if (isWifi()) {
                                        if (!wifiBusy.get()) sendKey(key, 1);
                                        ui.postDelayed(this, 200);
                                    } else {
                                        sendKey(key, 1);
                                        ui.postDelayed(this, 130);
                                    }
                                }
                            };
                            ui.postDelayed(repeater, 450);
                            return true;
                        case MotionEvent.ACTION_UP:
                            v.setPressed(false);
                            if (repeater != null) ui.removeCallbacks(repeater);
                            repeater = null;
                            if (editMode) editKey(key);
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            if (repeater != null) ui.removeCallbacks(repeater);
                            repeater = null;
                            return true;
                    }
                    return false;
                }
            });
        } else {
            b.setOnClickListener(v -> {
                if (editMode) editKey(key); else press(key);
            });
        }
        return b;
    }

    private void press(Key key) {
        buzz();
        sendKey(key, 3);
    }

    private void buzz() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(18);
        }
    }

    // ---------- uložené kódy ----------

    private int bits(Key k) { return prefs.getInt(k.id + "_b", k.defBits); }
    private int addr(Key k) { return prefs.getInt(k.id + "_a", k.defAddr); }
    private int cmd(Key k)  { return prefs.getInt(k.id + "_c", k.defCmd); }

    // ---------- vysílání SIRC ----------

    private void sendKey(Key key, int frames) {
        if (isWifi() && key.id.equals("power")) {
            wifiPowerToggle();
            return;
        }
        sendCode(bits(key), addr(key), cmd(key), frames);
    }

    /** Pošle kód podle zvoleného režimu (IR nebo Wi-Fi). */
    private void sendCode(int bits, int address, int command, int frames) {
        if (isWifi()) sendWifi(bits, address, command);
        else sendSirc(bits, address, command, frames);
    }

    // ---------- Wi-Fi ----------

    private boolean hasIr() { return ir != null && ir.hasIrEmitter(); }
    private boolean isWifi() { return settings.getBoolean("wifi", !hasIr()); }
    private String wifiIp() { return settings.getString("ip", ""); }
    private BraviaWifi bravia() {
        return new BraviaWifi(wifiIp(), settings.getString("psk", ""));
    }

    private void sendWifi(final int bits, final int address, final int command) {
        final BraviaWifi tv = bravia();
        wifiBusy.set(true);
        irHandler.post(() -> {
            try {
                tv.sendKey(bits, address, command);
            } catch (Exception ex) {
                wifiError(ex);
            } finally {
                wifiBusy.set(false);
            }
        });
    }

    private void wifiPowerToggle() {
        final BraviaWifi tv = bravia();
        irHandler.post(() -> {
            try {
                boolean on;
                try { on = tv.isOn(); } catch (Exception ex) { on = false; }
                tv.setPower(!on);
            } catch (Exception ex) {
                wifiError(ex);
            }
        });
    }

    private void sendWifiText(final String text) {
        final BraviaWifi tv = bravia();
        irHandler.post(() -> {
            try {
                tv.sendText(text);
                ui.post(() -> Toast.makeText(this, "Odesláno: „" + text + "“", Toast.LENGTH_SHORT).show());
            } catch (Exception ex) {
                ui.post(() -> Toast.makeText(this,
                        "Text se nepodařilo poslat – nejdřív na TV otevři vyhledávací pole. ("
                                + ex.getMessage() + ")", Toast.LENGTH_LONG).show());
            }
        });
    }

    private long lastErrorToast = 0;

    private void wifiError(final Exception ex) {
        ui.post(() -> {
            long now = System.currentTimeMillis();
            if (now - lastErrorToast < 3000) return; // nezahltit obrazovku hláškami
            lastErrorToast = now;
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (ex instanceof java.net.SocketTimeoutException || ex instanceof java.net.ConnectException
                    || ex instanceof java.net.NoRouteToHostException) {
                msg = "Televize neodpovídá – je zapnutá a na stejné Wi-Fi? Sedí IP adresa?";
            }
            Toast.makeText(this, "Wi-Fi: " + msg, Toast.LENGTH_LONG).show();
        });
    }

    private void refreshModeLabel() {
        boolean w = isWifi();
        modeLabel.setText(w ? "Wi-Fi" : "IR");
        modeLabel.setTextColor(w ? Color.parseColor("#81D4FA") : Color.parseColor("#FFB300"));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setStroke(px(1), w ? Color.parseColor("#81D4FA") : Color.parseColor("#FFB300"));
        modeLabel.setBackground(g);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(8), px(20), 0);

        final android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        final android.widget.RadioButton rIr = new android.widget.RadioButton(this);
        rIr.setText(hasIr() ? "Infraport (IR)" : "Infraport (IR) – tento telefon ho nemá");
        rIr.setId(View.generateViewId());
        final android.widget.RadioButton rWifi = new android.widget.RadioButton(this);
        rWifi.setText("Wi-Fi (síť)");
        rWifi.setId(View.generateViewId());
        group.addView(rIr);
        group.addView(rWifi);
        group.check(isWifi() ? rWifi.getId() : rIr.getId());
        box.addView(group);

        final EditText eIp = field(box, "IP adresa televize (např. 192.168.1.50)", wifiIp(), false);
        eIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText ePsk = field(box, "Heslo – Pre-Shared Key", settings.getString("psk", ""), false);
        ePsk.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        TextView help = new TextView(this);
        help.setText("Na televizi: Nastavení → Síť → Nastavení domácí sítě → IP control\n"
                + "• Authentication: Normal and Pre-Shared Key\n"
                + "• Pre-Shared Key: stejné heslo jako tady\n"
                + "• Remote start: zapnout (zapínání přes Wi-Fi)\n"
                + "IP adresu najdeš v Nastavení → Síť → Stav sítě.");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        help.setPadding(0, px(10), 0, 0);
        box.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Nastavení")
                .setView(sv)
                .setPositiveButton("Uložit", null)
                .setNeutralButton("Test spojení", null)
                .setNegativeButton("Zrušit", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                settings.edit()
                        .putBoolean("wifi", group.getCheckedRadioButtonId() == rWifi.getId())
                        .putString("ip", eIp.getText().toString().trim())
                        .putString("psk", ePsk.getText().toString().trim())
                        .apply();
                refreshModeLabel();
                d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                final BraviaWifi tv = new BraviaWifi(eIp.getText().toString(), ePsk.getText().toString());
                Toast.makeText(this, "Zkouším spojení…", Toast.LENGTH_SHORT).show();
                irHandler.post(() -> {
                    String r;
                    try { r = tv.test(); } catch (Exception ex) {
                        r = "Nepovedlo se: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    }
                    final String res = r;
                    ui.post(() -> Toast.makeText(this, res, Toast.LENGTH_LONG).show());
                });
            });
        });
        d.show();
    }

    private void sendSirc(final int bits, final int address, final int command, final int frames) {
        if (ir == null || !ir.hasIrEmitter()) return;
        final int[] pattern = SircEncoder.pattern(bits, address, command, frames);
        irHandler.post(() -> {
            try {
                ir.transmit(SircEncoder.CARRIER_HZ, pattern);
            } catch (Exception ex) {
                ui.post(() -> Toast.makeText(this, "Chyba IR: " + ex.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ---------- hlasové povely ----------

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Řekni povel, např. „zesil“, „kanál 5“, „Netflix“");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Rozpoznávání řeči není v telefonu dostupné (chybí aplikace Google).",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        String text = VoiceCommands.searchText(heard.get(0));
        if (text != null) {
            if (isWifi()) sendWifiText(text);
            else Toast.makeText(this, "Psaní textu funguje jen ve Wi-Fi režimu.", Toast.LENGTH_LONG).show();
            return;
        }
        for (String phrase : heard) {
            List<String> seq = VoiceCommands.parse(phrase);
            if (!seq.isEmpty()) {
                Toast.makeText(this, "„" + phrase + "“", Toast.LENGTH_SHORT).show();
                runSequence(seq);
                return;
            }
        }
        Toast.makeText(this, "Nerozumím: „" + heard.get(0) + "“", Toast.LENGTH_LONG).show();
    }

    /** Pošle tlačítka postupně s malou pauzou (např. číslice kanálu). */
    private void runSequence(List<String> ids) {
        long delay = 0;
        for (String id : ids) {
            final Key key = keys.get(id);
            if (key == null) continue;
            ui.postDelayed(() -> sendKey(key, 3), delay);
            delay += 300;
        }
        buzz();
    }

    // ---------- editor kódu ----------

    private void editKey(final Key key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(8), px(20), 0);

        final EditText eBits = field(box, "Počet bitů (12, 15 nebo 20)", String.valueOf(bits(key)), false);
        final EditText eAddr = field(box, "Adresa / kategorie (hex)", hex(addr(key)), true);
        final EditText eCmd = field(box, "Příkaz / data (hex)", hex(cmd(key)), true);

        TextView hint = new TextView(this);
        hint.setText("Výchozí: " + key.defBits + " bitů, 0x" + hex(key.defAddr) + " / 0x" + hex(key.defCmd)
                + "\n„Zkusit\" pošle kód bez uložení.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, px(8), 0, 0);
        box.addView(hint);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Tlačítko: " + key.label.replace('\n', ' ').trim()
                        + (key.label.trim().isEmpty() ? key.id : ""))
                .setView(box)
                .setPositiveButton("Uložit", null)
                .setNeutralButton("Zkusit", null)
                .setNegativeButton("Výchozí", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int[] p = parse(eBits, eAddr, eCmd);
                if (p == null) return;
                prefs.edit().putInt(key.id + "_b", p[0]).putInt(key.id + "_a", p[1])
                        .putInt(key.id + "_c", p[2]).apply();
                Toast.makeText(this, "Uloženo", Toast.LENGTH_SHORT).show();
                d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                int[] p = parse(eBits, eAddr, eCmd);
                if (p == null) return;
                buzz();
                sendCode(p[0], p[1], p[2], 3);
            });
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                prefs.edit().remove(key.id + "_b").remove(key.id + "_a").remove(key.id + "_c").apply();
                eBits.setText(String.valueOf(key.defBits));
                eAddr.setText(hex(key.defAddr));
                eCmd.setText(hex(key.defCmd));
                Toast.makeText(this, "Obnoven výchozí kód", Toast.LENGTH_SHORT).show();
            });
        });
        d.show();
    }

    private EditText field(LinearLayout box, String label, String value, boolean hexInput) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setPadding(0, px(10), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(hexInput
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_NUMBER);
        box.addView(e);
        return e;
    }

    private int[] parse(EditText eBits, EditText eAddr, EditText eCmd) {
        try {
            int b = Integer.parseInt(eBits.getText().toString().trim());
            int a = Integer.parseInt(strip(eAddr), 16);
            int c = Integer.parseInt(strip(eCmd), 16);
            if (b != 12 && b != 15 && b != 20) throw new IllegalArgumentException("bity");
            if (c < 0 || c > 0x7F) throw new IllegalArgumentException("příkaz");
            int maxA = b == 12 ? 0x1F : b == 15 ? 0xFF : 0x1FFF;
            if (a < 0 || a > maxA) throw new IllegalArgumentException("adresa");
            return new int[]{b, a, c};
        } catch (Exception ex) {
            Toast.makeText(this, "Neplatný kód (příkaz max 7F; adresa max 1F/FF/1FFF)",
                    Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private static String strip(EditText e) {
        String s = e.getText().toString().trim().toLowerCase();
        return s.startsWith("0x") ? s.substring(2) : s;
    }

    private static String hex(int v) { return String.format("%02X", v); }

    private int px(float dpVal) { return Math.round(dpVal * dp); }
}
