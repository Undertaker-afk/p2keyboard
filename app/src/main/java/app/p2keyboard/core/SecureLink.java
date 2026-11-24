package app.p2keyboard.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A self-healing DERP connection that carries end-to-end encrypted text messages.
 *
 * <p>Every message is sealed with NaCl box between the two devices' own keys, so the Tailscale
 * relay only forwards ciphertext. Packets that fail authentication are dropped silently.
 */
public final class SecureLink {

    public interface Listener {
        void onOnline(boolean online);

        void onMessage(byte[] peer, String message);

        void onPeerGone(byte[] peer);
    }

    private static final byte[] HEADER = {'p', '2'};

    private final String host;
    private final byte[] privateKey;
    private final Listener listener;
    private final Map<String, byte[]> sharedKeys = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeq = new ConcurrentHashMap<>();
    // Strictly increasing per sender (also across restarts), so replayed packets are rejected.
    private final AtomicLong seq = new AtomicLong(System.currentTimeMillis() * 1000);
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2k-keepalive");
        t.setDaemon(true);
        return t;
    });

    private volatile DerpClient client;
    private volatile boolean running;
    private Thread loop;

    public SecureLink(String host, byte[] privateKey, Listener listener) {
        this.host = host;
        this.privateKey = privateKey;
        this.listener = listener;
    }

    public String host() {
        return host;
    }

    public byte[] publicKey() {
        return NaclBox.publicKey(privateKey);
    }

    public boolean isOnline() {
        return client != null;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        loop = new Thread(this::runLoop, "p2k-derp");
        loop.setDaemon(true);
        loop.start();
        keepAlive.scheduleWithFixedDelay(() -> {
            DerpClient c = client;
            if (c == null) return;
            if (c.millisSinceLastFrame() > 75_000) {
                c.close(); // silent connection: force a reconnect
                return;
            }
            try {
                c.ping();
            } catch (IOException e) {
                c.close();
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        running = false;
        keepAlive.shutdownNow();
        DerpClient c = client;
        if (c != null) c.close();
        if (loop != null) loop.interrupt();
    }

    /** Encrypts and sends; returns false if the relay is currently unreachable. */
    public boolean send(byte[] peer, String message) {
        DerpClient c = client;
        if (c == null) return false;
        byte[] nonce = NaclBox.randomNonce();
        String framed = seq.incrementAndGet() + "|" + message;
        byte[] sealed = NaclBox.seal(framed.getBytes(StandardCharsets.UTF_8), nonce, shared(peer));
        byte[] packet = new byte[HEADER.length + nonce.length + sealed.length];
        System.arraycopy(HEADER, 0, packet, 0, HEADER.length);
        System.arraycopy(nonce, 0, packet, HEADER.length, nonce.length);
        System.arraycopy(sealed, 0, packet, HEADER.length + nonce.length, sealed.length);
        try {
            c.sendPacket(peer, packet);
            return true;
        } catch (IOException e) {
            c.close();
            return false;
        }
    }

    private byte[] shared(byte[] peer) {
        return sharedKeys.computeIfAbsent(NaclBox.hex(peer), k -> NaclBox.sharedKey(peer, privateKey));
    }

    private void runLoop() {
        long backoff = 1000;
        while (running) {
            DerpClient c = null;
            try {
                c = DerpClient.connect(host, privateKey, 10_000);
                client = c;
                backoff = 1000;
                listener.onOnline(true);
                c.readLoop(new DerpClient.Handler() {
                    @Override
                    public void onPacket(byte[] src, byte[] payload) {
                        handlePacket(src, payload);
                    }

                    @Override
                    public void onPeerGone(byte[] key) {
                        listener.onPeerGone(key);
                    }
                });
            } catch (IOException | RuntimeException e) {
                // fall through to reconnect
            } finally {
                boolean wasOnline = c != null && client == c;
                client = null;
                if (c != null) c.close();
                if (wasOnline) listener.onOnline(false);
            }
            if (!running) return;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 10_000);
        }
    }

    private void handlePacket(byte[] src, byte[] p) {
        int min = HEADER.length + NaclBox.NONCE_BYTES + NaclBox.OVERHEAD;
        if (p.length < min || p[0] != HEADER[0] || p[1] != HEADER[1]) return;
        byte[] nonce = new byte[NaclBox.NONCE_BYTES];
        System.arraycopy(p, HEADER.length, nonce, 0, nonce.length);
        int off = HEADER.length + nonce.length;
        byte[] plain;
        try {
            plain = NaclBox.open(p, off, p.length - off, nonce, shared(src));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (plain == null) return;
        String framed = new String(plain, StandardCharsets.UTF_8);
        int bar = framed.indexOf('|');
        if (bar <= 0) return;
        long n;
        try {
            n = Long.parseLong(framed.substring(0, bar));
        } catch (NumberFormatException e) {
            return;
        }
        String peer = NaclBox.hex(src);
        Long last = lastSeq.get(peer);
        if (last != null && n <= last) return; // replay or reordering
        lastSeq.put(peer, n);
        listener.onMessage(src, framed.substring(bar + 1));
    }
}
