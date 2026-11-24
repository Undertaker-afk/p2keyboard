package app.p2keyboard.core;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Phone side of the link: says hello with the scanned secret, keeps the session alive. */
public final class PhoneSession implements SecureLink.Listener {

    public enum Status { CONNECTING_RELAY, WAITING_FOR_BOARD, CONNECTED, REJECTED }

    public interface Listener {
        void onStatus(Status status);

        /** Editor state from the board (see RemoteOps). Called on a network thread. */
        void onBoardMessage(String message);
    }

    private final Pairing pairing;
    private final SecureLink link;
    private final Listener listener;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2k-phone-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile Status status = Status.CONNECTING_RELAY;
    private volatile long lastHeard;

    public PhoneSession(Pairing pairing, byte[] privateKey, Listener listener) {
        this.pairing = pairing;
        this.link = new SecureLink(pairing.host, privateKey, this);
        this.listener = listener;
    }

    public Status status() {
        return status;
    }

    public void start() {
        link.start();
        timer.scheduleWithFixedDelay(this::tick, 3, 3, TimeUnit.SECONDS);
    }

    public void stop() {
        if (status == Status.CONNECTED) link.send(pairing.boardKey, "b");
        timer.shutdownNow();
        link.stop();
    }

    /** Sends an input op to the board; false if not currently connected. */
    public boolean send(String op) {
        return status == Status.CONNECTED && link.send(pairing.boardKey, op);
    }

    private void tick() {
        if (!link.isOnline()) return;
        switch (status) {
            case WAITING_FOR_BOARD:
                link.send(pairing.boardKey, "h" + pairing.secret);
                break;
            case CONNECTED:
                if (System.currentTimeMillis() - lastHeard > 25_000) {
                    setStatus(Status.WAITING_FOR_BOARD);
                    link.send(pairing.boardKey, "h" + pairing.secret);
                } else {
                    link.send(pairing.boardKey, "p");
                }
                break;
            default:
                break;
        }
    }

    private void setStatus(Status s) {
        if (status == s) return;
        status = s;
        listener.onStatus(s);
    }

    @Override
    public void onOnline(boolean online) {
        if (status == Status.REJECTED) return;
        if (online) {
            setStatus(Status.WAITING_FOR_BOARD);
            link.send(pairing.boardKey, "h" + pairing.secret);
        } else {
            setStatus(Status.CONNECTING_RELAY);
        }
    }

    @Override
    public void onMessage(byte[] peer, String msg) {
        if (!Arrays.equals(peer, pairing.boardKey)) return;
        lastHeard = System.currentTimeMillis();
        switch (msg) {
            case "w":
                setStatus(Status.CONNECTED);
                break;
            case "x":
                setStatus(Status.REJECTED);
                break;
            case "p":
                break;
            default:
                listener.onBoardMessage(msg);
                break;
        }
    }

    @Override
    public void onPeerGone(byte[] peer) {
        if (Arrays.equals(peer, pairing.boardKey) && status == Status.CONNECTED) setStatus(Status.WAITING_FOR_BOARD);
    }
}
