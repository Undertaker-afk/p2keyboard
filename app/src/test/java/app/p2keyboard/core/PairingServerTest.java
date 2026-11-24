package app.p2keyboard.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PairingServerTest {
    private PairingServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        server = new PairingServer(() -> "<html>page</html>".getBytes(StandardCharsets.UTF_8),
                new PairingServer.Listener() {
                    public void onPhoneConnected() {}
                    public void onPhoneDisconnected() {}
                    public void onEdit(int d, String t) {}
                    public void onKey(String k) {}
                });
        port = server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private int status(String query) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/" + query).openConnection();
        return c.getResponseCode();
    }

    @Test
    public void servesPageOnlyWithValidToken() throws Exception {
        assertEquals(200, status("?t=" + server.getToken()));
        assertEquals(403, status("?t=wrong"));
        assertEquals(403, status(""));
    }

    @Test
    public void rotatingTokenInvalidatesOldCode() throws Exception {
        String old = server.getToken();
        server.rotateToken();
        assertEquals(403, status("?t=" + old));
        assertEquals(200, status("?t=" + server.getToken()));
        assertTrue(server.getToken().length() >= 22);
    }
}
