package app.p2keyboard.core;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Phone side of the link: says hello (with the pairing secret the first time), keeps it alive. */
public final class PhoneSession implements SecureLink.Listener {

    public enum Status {
        CONNECTING_RELAY,
        WAITING_FOR_BOARD,
        CONNECTED,
        /** The board doesn't know this phone (never paired, or unpaired on the board): scan again. */
        NOT_PAIRED,
        /** The board ended the session (or another phone took over). */
        DISCONNECTED
    }

    public interface Listener {
        void onStatus(Status status);

        /** The board's name, from its welcome. */
        void onBoardName(String name);

        /** Editor state from the board (see RemoteOps). Called on a network thread. */
        void onBoardMessage(String message);
    }

    private final Pairing pairing;
    private final String phoneName;
    private final SecureLink link;
    private final Listener listener;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2k-phone-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile Status status = Status.CONNECTING_RELAY;
    private volatile long lastHeard;

    /** {@code pairing.secret} may be empty for a board this phone is already paired with. */
    public PhoneSession(Pairing pairing, byte[] privateKey, String phoneName, Listener listener) {
        this.pairing = pairing;
        this.phoneName = Pairing.cleanName(phoneName);
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

    private void hello() {
        link.send(pairing.boardKey, "h" + pairing.secret + "\n" + phoneName);
    }

    private void tick() {
        if (!link.isOnline()) return;
        switch (status) {
            case WAITING_FOR_BOARD:
                hello();
                break;
            case CONNECTED:
                if (System.currentTimeMillis() - lastHeard > 25_000) {
                    setStatus(Status.WAITING_FOR_BOARD);
                    hello();
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

    private boolean finished() {
        return status == Status.NOT_PAIRED || status == Status.DISCONNECTED;
    }

    @Override
    public void onOnline(boolean online) {
        if (finished()) return;
        if (online) {
            setStatus(Status.WAITING_FOR_BOARD);
            hello();
        } else {
            setStatus(Status.CONNECTING_RELAY);
        }
    }

    @Override
    public void onMessage(byte[] peer, String msg) {
        if (!Arrays.equals(peer, pairing.boardKey) || msg.isEmpty()) return;
        lastHeard = System.currentTimeMillis();
        switch (msg.charAt(0)) {
            case 'w':
                listener.onBoardName(Pairing.cleanName(msg.substring(1)));
                setStatus(Status.CONNECTED);
                break;
            case 'x':
                if (msg.length() == 1) setStatus(Status.NOT_PAIRED);
                else listener.onBoardMessage(msg);
                break;
            case 'd':
                if (msg.length() == 1) setStatus(Status.DISCONNECTED);
                else listener.onBoardMessage(msg);
                break;
            case 'p':
                if (msg.length() > 1) listener.onBoardMessage(msg);
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
