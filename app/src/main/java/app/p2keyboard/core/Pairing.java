package app.p2keyboard.core;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * What the board's QR code carries:
 * {@code p2k://pair?k=<board public key>&h=<DERP host>&s=<pairing secret>&n=<board name>}.
 * The phone connects to the same DERP server, addresses the board by its public key and proves
 * it scanned the code by sending the secret (encrypted end-to-end). After that first time the
 * board remembers the phone's key, so later connections need no code (RustDesk-style trust).
 */
public final class Pairing {
    public final byte[] boardKey;
    public final String host;
    public final String secret;
    /** The board's display name; may be empty. */
    public final String name;

    public Pairing(byte[] boardKey, String host, String secret, String name) {
        this.boardKey = boardKey;
        this.host = host;
        this.secret = secret;
        this.name = name == null ? "" : name;
    }

    public Pairing(byte[] boardKey, String host, String secret) {
        this(boardKey, host, secret, "");
    }

    public String deviceId() {
        return DeviceId.of(boardKey);
    }

    public String toUri() {
        String uri = "p2k://pair?k=" + NaclBox.hex(boardKey) + "&h=" + host + "&s=" + secret;
        if (!name.isEmpty()) {
            try {
                uri += "&n=" + URLEncoder.encode(name, StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return uri;
    }

    /** Returns null if the text is not a valid pairing code. */
    public static Pairing parse(String text) {
        if (text == null) return null;
        try {
            URI uri = new URI(text.trim());
            if (!"p2k".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return null;
            String k = null, h = null, s = null, n = "";
            String q = uri.getRawQuery();
            if (q == null) return null;
            for (String pair : q.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                switch (pair.substring(0, eq)) {
                    case "k": k = v; break;
                    case "h": h = v; break;
                    case "s": s = v; break;
                    case "n": n = cleanName(v); break;
                    default: break;
                }
            }
            if (k == null || h == null || s == null) return null;
            if (!h.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) return null;
            if (!s.matches("[A-Za-z0-9_-]{16,64}")) return null;
            byte[] key = NaclBox.unhex(k);
            if (key.length != 32) return null;
            return new Pairing(key, h, s, n);
        } catch (Exception e) {
            return null;
        }
    }

    /** Device names are shown in lists: one line, no control characters, at most 40 chars. */
    public static String cleanName(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
