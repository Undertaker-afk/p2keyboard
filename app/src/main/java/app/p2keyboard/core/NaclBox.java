package app.p2keyboard.core;

import org.bouncycastle.crypto.engines.XSalsa20Engine;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.math.ec.rfc7748.X25519;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * NaCl {@code crypto_box} (Curve25519 + XSalsa20 + Poly1305), byte-compatible with Go's
 * {@code golang.org/x/crypto/nacl/box}, which is what DERP uses for its handshake. We also use it
 * end-to-end between phone and board so the relay only ever sees ciphertext.
 */
public final class NaclBox {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;
    public static final int OVERHEAD = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] SIGMA = "expand 32-byte k".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private NaclBox() {}

    public static byte[] newPrivateKey() {
        byte[] k = new byte[KEY_BYTES];
        RANDOM.nextBytes(k);
        return k;
    }

    public static byte[] publicKey(byte[] privateKey) {
        byte[] pub = new byte[KEY_BYTES];
        X25519.scalarMultBase(privateKey, 0, pub, 0);
        return pub;
    }

    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_BYTES];
        RANDOM.nextBytes(n);
        return n;
    }

    /** crypto_box_beforenm: the shared key for a (peer public, own private) pair. */
    public static byte[] sharedKey(byte[] peerPublic, byte[] ownPrivate) {
        byte[] s = new byte[KEY_BYTES];
        if (!X25519.calculateAgreement(ownPrivate, 0, peerPublic, 0, s, 0)) {
            throw new IllegalArgumentException("weak public key");
        }
        return hsalsa20(s, new byte[16]);
    }

    /** crypto_box_afternm: returns tag(16) || ciphertext. */
    public static byte[] seal(byte[] message, byte[] nonce, byte[] sharedKey) {
        XSalsa20Engine cipher = new XSalsa20Engine();
        cipher.init(true, new ParametersWithIV(new KeyParameter(sharedKey), nonce));
        byte[] polyKey = new byte[32];
        cipher.processBytes(new byte[32], 0, 32, polyKey, 0);
        byte[] out = new byte[OVERHEAD + message.length];
        cipher.processBytes(message, 0, message.length, out, OVERHEAD);
        Poly1305 mac = new Poly1305();
        mac.init(new KeyParameter(polyKey));
        mac.update(out, OVERHEAD, message.length);
        mac.doFinal(out, 0);
        return out;
    }

    /** crypto_box_open_afternm: returns the plaintext, or null if authentication fails. */
    public static byte[] open(byte[] box, int off, int len, byte[] nonce, byte[] sharedKey) {
        if (len < OVERHEAD) return null;
        XSalsa20Engine cipher = new XSalsa20Engine();
        cipher.init(false, new ParametersWithIV(new KeyParameter(sharedKey), nonce));
        byte[] polyKey = new byte[32];
        cipher.processBytes(new byte[32], 0, 32, polyKey, 0);
        Poly1305 mac = new Poly1305();
        mac.init(new KeyParameter(polyKey));
        mac.update(box, off + OVERHEAD, len - OVERHEAD);
        byte[] tag = new byte[OVERHEAD];
        mac.doFinal(tag, 0);
        if (!MessageDigest.isEqual(tag, Arrays.copyOfRange(box, off, off + OVERHEAD))) return null;
        byte[] out = new byte[len - OVERHEAD];
        cipher.processBytes(box, off + OVERHEAD, out.length, out, 0);
        return out;
    }

    static byte[] hsalsa20(byte[] key, byte[] in16) {
        int[] x = new int[16];
        x[0] = le(SIGMA, 0);
        x[5] = le(SIGMA, 4);
        x[10] = le(SIGMA, 8);
        x[15] = le(SIGMA, 12);
        for (int i = 0; i < 4; i++) {
            x[1 + i] = le(key, 4 * i);
            x[11 + i] = le(key, 16 + 4 * i);
            x[6 + i] = le(in16, 4 * i);
        }
        for (int i = 0; i < 10; i++) {
            x[4] ^= rotl(x[0] + x[12], 7);   x[8] ^= rotl(x[4] + x[0], 9);
            x[12] ^= rotl(x[8] + x[4], 13);  x[0] ^= rotl(x[12] + x[8], 18);
            x[9] ^= rotl(x[5] + x[1], 7);    x[13] ^= rotl(x[9] + x[5], 9);
            x[1] ^= rotl(x[13] + x[9], 13);  x[5] ^= rotl(x[1] + x[13], 18);
            x[14] ^= rotl(x[10] + x[6], 7);  x[2] ^= rotl(x[14] + x[10], 9);
            x[6] ^= rotl(x[2] + x[14], 13);  x[10] ^= rotl(x[6] + x[2], 18);
            x[3] ^= rotl(x[15] + x[11], 7);  x[7] ^= rotl(x[3] + x[15], 9);
            x[11] ^= rotl(x[7] + x[3], 13);  x[15] ^= rotl(x[11] + x[7], 18);

            x[1] ^= rotl(x[0] + x[3], 7);    x[2] ^= rotl(x[1] + x[0], 9);
            x[3] ^= rotl(x[2] + x[1], 13);   x[0] ^= rotl(x[3] + x[2], 18);
            x[6] ^= rotl(x[5] + x[4], 7);    x[7] ^= rotl(x[6] + x[5], 9);
            x[4] ^= rotl(x[7] + x[6], 13);   x[5] ^= rotl(x[4] + x[7], 18);
            x[11] ^= rotl(x[10] + x[9], 7);  x[8] ^= rotl(x[11] + x[10], 9);
            x[9] ^= rotl(x[8] + x[11], 13);  x[10] ^= rotl(x[9] + x[8], 18);
            x[12] ^= rotl(x[15] + x[14], 7); x[13] ^= rotl(x[12] + x[15], 9);
            x[14] ^= rotl(x[13] + x[12], 13); x[15] ^= rotl(x[14] + x[13], 18);
        }
        byte[] out = new byte[32];
        int[] idx = {0, 5, 10, 15, 6, 7, 8, 9};
        for (int i = 0; i < 8; i++) {
            int v = x[idx[i]];
            out[4 * i] = (byte) v;
            out[4 * i + 1] = (byte) (v >>> 8);
            out[4 * i + 2] = (byte) (v >>> 16);
            out[4 * i + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static int rotl(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) throw new IllegalArgumentException("bad hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }
}
