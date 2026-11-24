package app.p2keyboard.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A short, human-readable ID for a device's public key, like "482 913 077" (RustDesk style). */
public final class DeviceId {
    private DeviceId() {}

    public static String of(byte[] publicKey) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(publicKey);
            long v = ((h[0] & 0xffL) << 24 | (h[1] & 0xffL) << 16 | (h[2] & 0xffL) << 8 | (h[3] & 0xffL)) % 1_000_000_000L;
            String s = String.format(java.util.Locale.ROOT, "%09d", v);
            return s.substring(0, 3) + " " + s.substring(3, 6) + " " + s.substring(6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
