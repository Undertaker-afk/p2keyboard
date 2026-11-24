package app.p2keyboard.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Board side of the link (RustDesk-style trust): a phone that scanned the board's QR code proves
 * it with the pairing secret once and is remembered; after that it connects with no code.
 *
 * <p>Session messages (inside the encrypted link):
 * <pre>
 *   phone -> board   h&lt;secret&gt;\n&lt;phone name&gt;  hello (secret empty when already paired)
 *                    p  ping     b  bye      anything else: an input op
 *   board -> phone   w&lt;board name&gt;  welcome      x  not paired (scan again)
 *                    d  disconnected by the board    p  pong
 *                    anything else: editor state for the phone
 * </pre>
 * One phone types at a time; a newer hello from another paired phone takes over.
 */
public final class BoardSession implements SecureLink.Listener {

    public interface Listener {
        void onRelayOnline(boolean online);

        void onPhoneConnected(byte[] phoneKey, String phoneName);

        void onPhoneDisconnected();

        /** An input operation from the connected phone (see RemoteOps). Called on a network thread. */
        void onOp(String op);
    }

    /** Persists which phones are paired with this board. */
    public interface TrustStore {
        boolean isTrusted(byte[] phoneKey);

        /** Called when a phone pairs with the secret (or reconnects; update its name). */
        void trust(byte[] phoneKey, String phoneName);
    }

    /** A phone is active while it heartbeats (every 2 s); after 3 missed beats it counts as gone. */
    private static final long PHONE_TIMEOUT_MS = 6_000;

    private final SecureLink link;
    private final Listener listener;
    private final TrustStore trust;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2k-board-watchdog");
        t.setDaemon(true);
        return t;
    });
    private volatile String secret;
    private volatile String name;
    private byte[] phone;
    private long lastSeen;

    public BoardSession(String host, byte[] privateKey, String secret, String name, TrustStore trust, Listener listener) {
        this.link = new SecureLink(host, privateKey, this);
        this.secret = secret;
        this.name = Pairing.cleanName(name);
        this.trust = trust;
        this.listener = listener;
    }

    public void start() {
        link.start();
        watchdog.scheduleWithFixedDelay(() -> {
            boolean expired;
            synchronized (this) {
                expired = phone != null && System.currentTimeMillis() - lastSeen > PHONE_TIMEOUT_MS;
            }
            if (expired) dropPhone(null);
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        dropPhone("d");
        watchdog.shutdownNow();
        link.stop();
    }

    public Pairing pairing() {
        return new Pairing(link.publicKey(), link.host(), secret, name);
    }

    public boolean isRelayOnline() {
        return link.isOnline();
    }

    public synchronized boolean isPhoneConnected() {
        return phone != null;
    }

    public synchronized byte[] connectedPhone() {
        return phone == null ? null : phone.clone();
    }

    public void setName(String newName) {
        name = Pairing.cleanName(newName);
    }

    /** New QR secret: the old code can no longer pair new phones. Paired phones keep working. */
    public void setSecret(String newSecret) {
        secret = newSecret;
    }

    /** Ends the current phone's session; it stays paired and can connect again from its list. */
    public void disconnectPhone() {
        dropPhone("d");
    }

    /** A phone was unpaired: if it is connected, end its session and tell it. */
    public void revoked(byte[] phoneKey) {
        boolean ours;
        synchronized (this) {
            ours = phone != null && Arrays.equals(phone, phoneKey);
        }
        if (ours) dropPhone("x");
        else link.send(phoneKey, "x");
    }

    public void sendToPhone(String message) {
        byte[] p;
        synchronized (this) {
            p = phone;
        }
        if (p != null) link.send(p, message);
    }

    private void dropPhone(String tellPhone) {
        byte[] p;
        synchronized (this) {
            p = phone;
            phone = null;
        }
        if (p == null) return;
        if (tellPhone != null) link.send(p, tellPhone);
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
            hello(peer, msg.substring(1));
            return;
        }
        synchronized (this) {
            if (phone == null || !Arrays.equals(phone, peer)) return; // not the connected phone: ignore
            lastSeen = System.currentTimeMillis();
        }
        switch (msg) {
            case "p":
                link.send(peer, "p");
                break;
            case "b":
                dropPhone(null);
                break;
            default:
                listener.onOp(msg);
                break;
        }
    }

    private void hello(byte[] peer, String body) {
        int nl = body.indexOf('\n');
        String offered = nl < 0 ? body : body.substring(0, nl);
        String phoneName = Pairing.cleanName(nl < 0 ? "" : body.substring(nl + 1));
        boolean secretOk = !offered.isEmpty() && MessageDigest.isEqual(
                offered.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
        if (!trust.isTrusted(peer) && !secretOk) {
            link.send(peer, "x");
            return;
        }
        trust.trust(peer, phoneName); // pair now, or refresh the name of a paired phone
        byte[] previous;
        synchronized (this) {
            previous = phone;
            phone = peer;
            lastSeen = System.currentTimeMillis();
        }
        link.send(peer, "w" + name);
        if (previous != null && !Arrays.equals(previous, peer)) link.send(previous, "d"); // another phone took over
        // Also on a re-hello from the same phone (after a reconnect) so editor state is resent.
        listener.onPhoneConnected(peer, phoneName);
    }

    @Override
    public void onPeerGone(byte[] peer) {
        boolean ours;
        synchronized (this) {
            ours = phone != null && Arrays.equals(phone, peer);
        }
        if (ours) dropPhone(null);
    }
}
