package app.p2keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import app.p2keyboard.core.DeviceId;
import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;

/**
 * This device's permanent identity, used in both roles (RustDesk style): one Curve25519 key, the
 * short device ID derived from it, and an editable name shown on the other device.
 */
public final class Identity {
    private static final String PREFS = "identity";

    private Identity() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized byte[] privateKey(Context c) {
        SharedPreferences p = prefs(c);
        String k = p.getString("key", null);
        if (k == null) {
            // Keep the key of a board set up by an earlier version, so its QR code stays valid.
            k = c.getSharedPreferences("board", Context.MODE_PRIVATE).getString("key", null);
            if (k == null) k = NaclBox.hex(NaclBox.newPrivateKey());
            p.edit().putString("key", k).apply();
        }
        return NaclBox.unhex(k);
    }

    public static String deviceId(Context c) {
        return DeviceId.of(NaclBox.publicKey(privateKey(c)));
    }

    public static String name(Context c) {
        String n = prefs(c).getString("name", null);
        return n == null || n.isEmpty() ? defaultName() : n;
    }

    public static void setName(Context c, String name) {
        prefs(c).edit().putString("name", Pairing.cleanName(name)).apply();
    }

    static String defaultName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        if (!maker.isEmpty() && !model.toLowerCase().startsWith(maker.toLowerCase())) {
            model = Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
        }
        return Pairing.cleanName(model);
    }
}
