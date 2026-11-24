package app.p2keyboard.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Board side of the link: waits on the DERP relay for a phone that presents the pairing secret,
 * then hands that phone's input operations to the keyboard service.
 *
 * <p>Session messages (inside the encrypted link):
 * <pre>
 *   phone -> board   h&lt;secret&gt;  hello     p  ping     b  bye     anything else: an input op
 *   board -> phone   w  welcome    x  rejected / unpaired    p  pong   anything else: editor state
 * </pre>
 */
public final class BoardSession implements SecureLink.Listener {

    public interface Listener {
        void onRelayOnline(boolean online);

        void onPhoneConnected();

        void onPhoneDisconnected();

        /** An input operation from the paired phone (see RemoteOps). Called on a network thread. */
        void onOp(String op);
    }

    private static final long PHONE_TIMEOUT_MS = 30_000;

    private final SecureLink link;
    private final Listener listener;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2k-board-watchdog");
        t.setDaemon(true);
        return t;
    });
    private volatile String secret;
    private byte[] phone;
    private long lastSeen;

    public BoardSession(String host, byte[] privateKey, String secret, Listener listener) {
        this.link = new SecureLink(host, privateKey, this);
        this.secret = secret;
        this.listener = listener;
    }

    public void start() {
        link.start();
        watchdog.scheduleWithFixedDelay(() -> {
            boolean expired;
            synchronized (this) {
                expired = phone != null && System.currentTimeMillis() - lastSeen > PHONE_TIMEOUT_MS;
            }
            if (expired) dropPhone(false);
        }, 5, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        dropPhone(true);
        watchdog.shutdownNow();
        link.stop();
    }

    public Pairing pairing() {
        return new Pairing(link.publicKey(), link.host(), secret);
    }

    public boolean isRelayOnline() {
        return link.isOnline();
    }

    public synchronized boolean isPhoneConnected() {
        return phone != null;
    }

    /** New QR secret: the old code stops working and the current phone is disconnected. */
    public void setSecret(String newSecret) {
        secret = newSecret;
        dropPhone(true);
    }

    public void sendToPhone(String message) {
        byte[] p;
        synchronized (this) {
            p = phone;
        }
        if (p != null) link.send(p, message);
    }

    private void dropPhone(boolean notifyPhone) {
        byte[] p;
        synchronized (this) {
            p = phone;
            phone = null;
        }
        if (p == null) return;
        if (notifyPhone) link.send(p, "x");
        listener.onPhoneDisconnected();
    }

    // ---- SecureLink.Listener ----

    @Override
    public void onOnline(boolean online) {
        listener.onRelayOnline(online);
    }

    @Override
    public void onMessage(byte[] peer, String msg) {
        if (msg.startsWith("h")) {
            boolean ok = MessageDigest.isEqual(
                    msg.substring(1).getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                link.send(peer, "x");
                return;
            }
            byte[] previous;
            synchronized (this) {
                previous = phone;
                phone = peer;
                lastSeen = System.currentTimeMillis();
            }
            link.send(peer, "w");
            if (previous != null && !Arrays.equals(previous, peer)) {
                link.send(previous, "x"); // a newer phone took over
            }
            // Also on a re-hello from the same phone (after a reconnect) so editor state is resent.
            listener.onPhoneConnected();
            return;
        }
        synchronized (this) {
            if (phone == null || !Arrays.equals(phone, peer)) return; // not the paired phone: ignore
            lastSeen = System.currentTimeMillis();
        }
        switch (msg) {
            case "p":
                link.send(peer, "p");
                break;
            case "b":
                dropPhone(false);
                break;
            default:
                listener.onOp(msg);
                break;
        }
    }

    @Override
    public void onPeerGone(byte[] peer) {
        boolean ours;
        synchronized (this) {
            ours = phone != null && Arrays.equals(phone, peer);
        }
        if (ours) dropPhone(false);
    }
}
