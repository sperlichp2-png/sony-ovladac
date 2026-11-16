package cz.sperlich.sonyovladac;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Ovládání televize Sony Bravia přes Wi-Fi (vestavěné "IP control").
 * Na televizi: Síť → Nastavení domácí sítě → IP control →
 *   Authentication = Normal and Pre-Shared Key, nastavit Pre-Shared Key,
 *   zapnout Remote start (kvůli zapínání přes síť).
 *
 * Tlačítka se posílají jako IRCC kód, který obsahuje stejnou kategorii/data jako IR (SIRC).
 * Všechny metody jsou blokující – volat z vlákna na pozadí.
 */
public final class BraviaWifi {
    private static final int TIMEOUT_MS = 2500;
    private final String ip, psk;

    public BraviaWifi(String ip, String psk) {
        this.ip = ip.trim();
        this.psk = psk.trim();
    }

    /** IRCC kód: 00 00 00 FMT | 00 00 00 KAT | 00 00 00 DATA | 03, zakódované base64. */
    static String irccCode(int bits, int address, int command) {
        int fmt = bits == 12 ? 1 : bits == 15 ? 2 : 3;
        byte[] b = {
                0, 0, 0, (byte) fmt,
                0, 0, (byte) ((address >> 8) & 0xFF), (byte) (address & 0xFF),
                0, 0, 0, (byte) (command & 0x7F),
                3
        };
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public void sendKey(int bits, int address, int command) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\">"
                + "<IRCCCode>" + irccCode(bits, address, command) + "</IRCCCode>"
                + "</u:X_SendIRCC></s:Body></s:Envelope>";
        post("/sony/IRCC", body, "text/xml; charset=UTF-8",
                "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"");
    }

    /** Vrací true, pokud televize běží (status "active"). */
    public boolean isOn() throws Exception {
        String r = json("/sony/system", "getPowerStatus", "[]", "1.0");
        return r.contains("\"active\"");
    }

    public void setPower(boolean on) throws Exception {
        json("/sony/system", "setPowerStatus", "[{\"status\":" + on + "}]", "1.0");
    }

    /** Vloží text do právě otevřeného textového pole (vyhledávání apod.). */
    public void sendText(String text) throws Exception {
        String esc = text.replace("\\", "\\\\").replace("\"", "\\\"");
        json("/sony/appControl", "setTextForm", "[\"" + esc + "\"]", "1.0");
    }

    /** Seznam aplikací na televizi: každá položka {název, uri}. */
    public java.util.List<String[]> apps() throws Exception {
        String r = json("/sony/appControl", "getApplicationList", "[]", "1.0");
        org.json.JSONArray arr = new org.json.JSONObject(r).getJSONArray("result").getJSONArray(0);
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            out.add(new String[]{o.optString("title"), o.optString("uri")});
        }
        return out;
    }

    public void launch(String uri) throws Exception {
        String esc = uri.replace("\\", "\\\\").replace("\"", "\\\"");
        json("/sony/appControl", "setActiveApp", "[{\"uri\":\"" + esc + "\",\"data\":\"\"}]", "1.0");
    }

    /** Krátký test spojení – vrátí popis stavu. */
    public String test() throws Exception {
        return isOn() ? "Spojeno ✔ (televize je zapnutá)" : "Spojeno ✔ (televize je v pohotovosti)";
    }

    private String json(String path, String method, String params, String version) throws Exception {
        String body = "{\"method\":\"" + method + "\",\"id\":1,\"params\":" + params
                + ",\"version\":\"" + version + "\"}";
        String resp = post(path, body, "application/json; charset=UTF-8", null);
        if (resp.contains("\"error\"")) throw new Exception("Televize vrátila chybu: " + resp);
        return resp;
    }

    private String post(String path, String body, String contentType, String soapAction) throws Exception {
        if (ip.isEmpty()) throw new Exception("Není nastavená IP adresa televize");
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + path).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS * 2);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            c.setRequestProperty("X-Auth-PSK", psk);
            if (soapAction != null) c.setRequestProperty("SOAPACTION", soapAction);
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            if (code == 403) throw new Exception("Špatné heslo (Pre-Shared Key)");
            if (code >= 400) throw new Exception("Televize odpověděla chybou " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
