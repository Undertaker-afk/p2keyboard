package app.p2keyboard.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal client for Tailscale's DERP relay protocol (tailscale.com/derp).
 *
 * <p>A DERP server relays opaque packets between clients identified by Curve25519 public keys.
 * Both phone and board connect out to the same server over TLS on port 443, so this works through
 * any NAT or firewall that allows HTTPS, with no port forwarding.
 */
public final class DerpClient implements Closeable {

    public interface Handler {
        void onPacket(byte[] srcKey, byte[] payload);

        void onPeerGone(byte[] key);
    }

    static final int FRAME_SERVER_KEY = 0x01;
    static final int FRAME_CLIENT_INFO = 0x02;
    static final int FRAME_SERVER_INFO = 0x03;
    static final int FRAME_SEND_PACKET = 0x04;
    static final int FRAME_RECV_PACKET = 0x05;
    static final int FRAME_KEEP_ALIVE = 0x06;
    static final int FRAME_NOTE_PREFERRED = 0x07;
    static final int FRAME_PEER_GONE = 0x08;
    static final int FRAME_PING = 0x12;
    static final int FRAME_PONG = 0x13;

    static final byte[] MAGIC = {0x44, 0x45, 0x52, 0x50, (byte) 0xF0, (byte) 0x9F, (byte) 0x94, (byte) 0x91}; // "DERP🔑"
    private static final int MAX_FRAME = 1 << 20;
    public static final int MAX_PACKET = 64 << 10;

    /**
     * Thread that must never write to the network (Android's main thread does this check itself
     * and kills the app). Tests set it to prove sends are handed off to the link's sender thread.
     */
    static volatile Thread noNetworkThread;

    public static void forbidNetworkOn(Thread t) {
        noNetworkThread = t;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final byte[] serverKey;
    private volatile long lastReceived = System.currentTimeMillis();

    private DerpClient(Socket socket, DataInputStream in, OutputStream out, byte[] serverKey) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.serverKey = serverKey;
    }

    /** Connects, upgrades to DERP and completes the key handshake. */
    public static DerpClient connect(String host, byte[] privateKey, int timeoutMs) throws IOException {
        Socket raw = openTcp(host, 443, timeoutMs);
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
        try {
            tls.setSoTimeout(timeoutMs);
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // check the cert matches the host
            tls.setSSLParameters(params);
            tls.startHandshake();
            if (System.getProperty("java.vendor", "").contains("Android")
                    && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                throw new IOException("TLS certificate does not match " + host);
            }
            OutputStream out = new BufferedOutputStream(tls.getOutputStream(), 16 << 10);
            InputStream bin = new BufferedInputStream(tls.getInputStream(), 16 << 10);
            String req = "GET /derp HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Upgrade: DERP\r\n"
                    + "User-Agent: p2keyboard\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String status = readLine(bin);
            if (status == null || !status.contains(" 101")) throw new IOException("DERP upgrade refused: " + status);
            while (true) {
                String line = readLine(bin);
                if (line == null) throw new EOFException();
                if (line.isEmpty()) break;
            }
            DataInputStream in = new DataInputStream(bin);

            int type = in.readUnsignedByte();
            int len = in.readInt();
            if (type != FRAME_SERVER_KEY || len < MAGIC.length + 32 || len > 1024) {
                throw new IOException("unexpected first DERP frame " + type);
            }
            byte[] body = new byte[len];
            in.readFully(body);
            if (!Arrays.equals(Arrays.copyOf(body, MAGIC.length), MAGIC)) throw new IOException("bad DERP magic");
            byte[] serverKey = Arrays.copyOfRange(body, MAGIC.length, MAGIC.length + 32);

            DerpClient c = new DerpClient(tls, in, out, serverKey);
            c.sendClientInfo(privateKey);
            c.writeFrame(FRAME_NOTE_PREFERRED, new byte[]{1});
            tls.setSoTimeout(0);
            return c;
        } catch (IOException | RuntimeException e) {
            try {
                tls.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** Honours an HTTPS proxy configured in the JVM / Android system properties (CONNECT tunnel). */
    private static Socket openTcp(String host, int port, int timeoutMs) throws IOException {
        String proxyHost = System.getProperty("https.proxyHost");
        if (proxyHost == null || proxyHost.isEmpty()) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }
        int proxyPort = Integer.parseInt(System.getProperty("https.proxyPort", "443"));
        Socket s = new Socket();
        s.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
        s.setSoTimeout(timeoutMs);
        OutputStream o = s.getOutputStream();
        o.write(("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        o.flush();
        InputStream i = s.getInputStream();
        String status = readLine(i);
        if (status == null || !status.matches("HTTP/1\\.[01] 200.*")) {
            s.close();
            throw new IOException("proxy refused CONNECT: " + status);
        }
        while (true) {
            String line = readLine(i);
            if (line == null || line.isEmpty()) break;
        }
        return s;
    }

    private void sendClientInfo(byte[] privateKey) throws IOException {
        byte[] pub = NaclBox.publicKey(privateKey);
        byte[] nonce = NaclBox.randomNonce();
        byte[] info = "{\"version\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = NaclBox.seal(info, nonce, NaclBox.sharedKey(serverKey, privateKey));
        byte[] body = new byte[32 + 24 + sealed.length];
        System.arraycopy(pub, 0, body, 0, 32);
        System.arraycopy(nonce, 0, body, 32, 24);
        System.arraycopy(sealed, 0, body, 56, sealed.length);
        writeFrame(FRAME_CLIENT_INFO, body);
    }

    public void sendPacket(byte[] dstKey, byte[] payload) throws IOException {
        if (payload.length > MAX_PACKET) throw new IOException("packet too large");
        byte[] body = new byte[32 + payload.length];
        System.arraycopy(dstKey, 0, body, 0, 32);
        System.arraycopy(payload, 0, body, 32, payload.length);
        writeFrame(FRAME_SEND_PACKET, body);
    }

    /** Sends a DERP-level ping; the server answers with a pong, which keeps NAT mappings alive. */
    public void ping() throws IOException {
        byte[] data = new byte[8];
        long t = System.nanoTime();
        for (int i = 0; i < 8; i++) data[i] = (byte) (t >>> (8 * i));
        writeFrame(FRAME_PING, data);
    }

    public long millisSinceLastFrame() {
        return System.currentTimeMillis() - lastReceived;
    }

    private synchronized void writeFrame(int type, byte[] body) throws IOException {
        if (Thread.currentThread() == noNetworkThread) {
            throw new IllegalStateException("network write on a UI thread");
        }
        out.write(type);
        out.write(body.length >>> 24);
        out.write(body.length >>> 16);
        out.write(body.length >>> 8);
        out.write(body.length);
        out.write(body);
        out.flush();
    }

    /** Blocks reading frames until the connection closes; then throws. */
    public void readLoop(Handler handler) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            int len = in.readInt();
            if (len < 0 || len > MAX_FRAME) throw new IOException("DERP frame too large");
            byte[] body = new byte[len];
            in.readFully(body);
            lastReceived = System.currentTimeMillis();
            switch (type) {
                case FRAME_RECV_PACKET:
                    if (len >= 32) handler.onPacket(Arrays.copyOf(body, 32), Arrays.copyOfRange(body, 32, len));
                    break;
                case FRAME_PEER_GONE:
                    if (len >= 32) handler.onPeerGone(Arrays.copyOf(body, 32));
                    break;
                case FRAME_PING:
                    writeFrame(FRAME_PONG, Arrays.copyOf(body, 8));
                    break;
                default:
                    // server info, keep-alive, pong, health, restarting, peer present: nothing to do
                    break;
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return sb.length() == 0 ? null : sb.toString();
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("header line too long");
        }
    }

    static String describe(byte[] key) {
        return NaclBox.hex(key).substring(0, 8).toLowerCase(Locale.ROOT);
    }
}
