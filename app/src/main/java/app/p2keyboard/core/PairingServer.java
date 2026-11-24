package app.p2keyboard.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Tiny dependency-free HTTP + WebSocket server living on the board.
 *
 * <p>The board shows a QR code containing {@code http://<lan-ip>:<port>/?t=<token>}. The phone
 * opens that URL in its browser, receives the typing page, and connects back over a WebSocket.
 * Everything stays on the local network; the token is the pairing secret.
 *
 * <p>Wire protocol (WebSocket text frames):
 * <pre>
 *   phone -> board   e:&lt;del&gt;:&lt;text&gt;   delete {del} chars before the cursor, then insert text
 *                    k:&lt;name&gt;         press a key (enter, tab, del, left, right, up, down, home, end)
 *                    p                 heartbeat
 *   board -> phone   f:1 | f:0         a text field on the board is focused / not focused
 *                    p                 heartbeat reply
 * </pre>
 */
public final class PairingServer {

    public interface Listener {
        /** Called from a server thread. */
        void onPhoneConnected();

        void onPhoneDisconnected();

        void onEdit(int deleteBefore, String insert);

        void onKey(String name);
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 1 << 20;
    private static final int MAX_DELETE = 10_000;
    private static final int IDLE_TIMEOUT_MS = 45_000;

    private final Callable<byte[]> pageProvider;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    private volatile String token;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Client active;

    public PairingServer(Callable<byte[]> pageProvider, Listener listener) {
        this.pageProvider = pageProvider;
        this.listener = listener;
        this.token = newToken();
    }

    /** Binds to a free port on all interfaces and returns it. */
    public int start() throws IOException {
        ServerSocket ss = new ServerSocket(0, 8, (InetAddress) null);
        serverSocket = ss;
        running = true;
        Thread t = new Thread(() -> acceptLoop(ss), "p2k-accept");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            ServerSocket ss = serverSocket;
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
        Client c;
        synchronized (lock) {
            c = active;
            active = null;
        }
        if (c != null) c.close();
    }

    public int getPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    public String getToken() {
        return token;
    }

    public boolean isPhoneConnected() {
        synchronized (lock) {
            return active != null;
        }
    }

    /** Invalidates the current QR code and drops the paired phone. */
    public void rotateToken() {
        token = newToken();
        Client c;
        synchronized (lock) {
            c = active;
        }
        if (c != null) c.close();
    }

    /** Sends a text frame to the paired phone, if any. */
    public void sendToPhone(String message) {
        Client c;
        synchronized (lock) {
            c = active;
        }
        if (c != null) c.send(message);
    }

    // ------------------------------------------------------------------------------------------

    private String newToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                Socket s = ss.accept();
                Thread t = new Thread(() -> handle(s), "p2k-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5_000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 4096);
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) {
                socket.close();
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 64; i++) {
                String line = readLine(in);
                if (line == null) throw new EOFException();
                if (line.isEmpty()) break;
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
                }
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) {
                respond(out, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                socket.close();
                return;
            }
            String target = parts[1];
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            if (!tokenMatches(queryParam(query, "t"))) {
                respond(out, 403, "text/plain",
                        "Pairing code expired. Scan the QR code on the board again.".getBytes(StandardCharsets.UTF_8));
                socket.close();
                return;
            }

            boolean upgrade = "websocket".equalsIgnoreCase(headers.get("upgrade"));
            if (path.equals("/ws") && upgrade && headers.containsKey("sec-websocket-key")) {
                runWebSocket(socket, in, out, headers.get("sec-websocket-key"));
            } else if (path.equals("/") || path.equals("/index.html")) {
                byte[] page = pageProvider.call();
                respond(out, 200, "text/html; charset=utf-8", page);
                socket.close();
            } else {
                respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                socket.close();
            }
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean tokenMatches(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return sb.length() == 0 ? null : sb.toString();
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------

    private void runWebSocket(Socket socket, InputStream in, OutputStream out, String key) throws Exception {
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        String head = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        socket.setSoTimeout(IDLE_TIMEOUT_MS);

        Client me = new Client(socket, out);
        Client previous;
        synchronized (lock) {
            previous = active;
            active = me;
        }
        if (previous != null) previous.close();
        listener.onPhoneConnected();
        try {
            readFrames(me, in);
        } catch (IOException ignored) {
            // disconnect or timeout
        } finally {
            me.close();
            boolean wasActive;
            synchronized (lock) {
                wasActive = active == me;
                if (wasActive) active = null;
            }
            if (wasActive) listener.onPhoneDisconnected();
        }
    }

    private void readFrames(Client me, InputStream in) throws IOException {
        ByteArrayOutputStream fragments = null;
        while (true) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (!masked || len > MAX_FRAME) return; // clients must mask; refuse oversized frames
            byte[] mask = new byte[4];
            readFully(in, mask);
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            switch (opcode) {
                case 0x8: // close
                    me.sendFrame(0x8, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]);
                    return;
                case 0x9: // ping
                    me.sendFrame(0xA, payload);
                    break;
                case 0xA: // pong
                    break;
                case 0x1:
                case 0x2:
                case 0x0:
                    if (opcode != 0) fragments = new ByteArrayOutputStream();
                    if (fragments == null) return;
                    fragments.write(payload);
                    if (fragments.size() > MAX_FRAME) return;
                    if (fin) {
                        String text = new String(fragments.toByteArray(), StandardCharsets.UTF_8);
                        fragments = null;
                        if (opcode == 0x1 || opcode == 0x0) dispatch(me, text);
                    }
                    break;
                default:
                    return;
            }
        }
    }

    private void dispatch(Client me, String msg) {
        if (msg.equals("p")) {
            me.send("p");
        } else if (msg.startsWith("e:")) {
            int second = msg.indexOf(':', 2);
            if (second < 0) return;
            int del;
            try {
                del = Integer.parseInt(msg.substring(2, second));
            } catch (NumberFormatException e) {
                return;
            }
            if (del < 0 || del > MAX_DELETE) return;
            listener.onEdit(del, msg.substring(second + 1));
        } else if (msg.startsWith("k:")) {
            listener.onKey(msg.substring(2));
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int c = in.read();
        if (c < 0) throw new EOFException();
        return c;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private static final class Client {
        private final Socket socket;
        private final OutputStream out;

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void send(String text) {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void sendFrame(int opcode, byte[] payload) {
            try {
                ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 10);
                f.write(0x80 | opcode);
                if (payload.length < 126) {
                    f.write(payload.length);
                } else if (payload.length < 65536) {
                    f.write(126);
                    f.write(payload.length >> 8);
                    f.write(payload.length & 0xFF);
                } else {
                    f.write(127);
                    for (int i = 7; i >= 0; i--) f.write((int) ((long) payload.length >> (8 * i)) & 0xFF);
                }
                f.write(payload);
                out.write(f.toByteArray());
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
