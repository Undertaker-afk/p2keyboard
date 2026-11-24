package app.p2keyboard.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class CoreTest {

    // RFC 7748 Alice/Bob keys; expected values from the NaCl spec ("firstkey") and tweetnacl.
    private static final byte[] ALICE_SK = NaclBox.unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    private static final byte[] BOB_SK = NaclBox.unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");

    @Test
    public void naclBoxMatchesReferenceVectors() {
        byte[] bobPk = NaclBox.publicKey(BOB_SK);
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", NaclBox.hex(bobPk));
        byte[] k = NaclBox.sharedKey(bobPk, ALICE_SK);
        assertEquals("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389", NaclBox.hex(k));
        byte[] nonce = new byte[24];
        for (int i = 0; i < 24; i++) nonce[i] = (byte) i;
        byte[] box = NaclBox.seal("hello board".getBytes(StandardCharsets.UTF_8), nonce, k);
        assertEquals("6d454c3a62f0cfe8522634d454c22c186d2b3cc97456371221f5fc", NaclBox.hex(box));

        byte[] k2 = NaclBox.sharedKey(NaclBox.publicKey(ALICE_SK), BOB_SK);
        assertArrayEquals("hello board".getBytes(StandardCharsets.UTF_8), NaclBox.open(box, 0, box.length, nonce, k2));
        box[box.length - 1] ^= 1;
        assertNull(NaclBox.open(box, 0, box.length, nonce, k2));
    }

    @Test
    public void pairingRoundTripsAndRejectsJunk() {
        Pairing p = new Pairing(NaclBox.publicKey(ALICE_SK), "derp4f.tailscale.com", "abcdefghijklmnop0123");
        Pairing q = Pairing.parse(p.toUri());
        assertNotNull(q);
        assertArrayEquals(p.boardKey, q.boardKey);
        assertEquals("derp4f.tailscale.com", q.host);
        assertEquals("abcdefghijklmnop0123", q.secret);

        assertNull(Pairing.parse("https://example.com"));
        assertNull(Pairing.parse("p2k://pair?k=00&h=derp1f.tailscale.com&s=abcdefghijklmnop"));
        assertNull(Pairing.parse(p.toUri().replace("derp4f.tailscale.com", "evil host")));
        assertNull(Pairing.parse(p.toUri().replace("abcdefghijklmnop0123", "short")));
    }

    @Test
    public void remoteOpsRoundTrip() {
        RemoteOps.Op c = RemoteOps.parse(RemoteOps.commit("a:b 😀", 1));
        assertEquals(RemoteOps.COMMIT, c.code);
        assertEquals(1, c.a);
        assertEquals("a:b 😀", c.text);

        RemoteOps.Op k = RemoteOps.parse(RemoteOps.key(0, 67, 0));
        assertEquals(RemoteOps.KEY, k.code);
        assertEquals(67, k.b);

        RemoteOps.Op s = RemoteOps.parse(RemoteOps.select(-3, -1));
        assertEquals(-3, s.a);
        assertEquals(-1, s.b);

        assertNull(RemoteOps.parse("d-1:0"));
        assertNull(RemoteOps.parse("k1:2"));
        assertNull(RemoteOps.parse("cnope"));
        assertNull(RemoteOps.parse("zzz"));
    }

    @Test
    public void derpMapSkipsAvoidedRegionsAndStunOnlyNodes() throws Exception {
        String json = "{\"Regions\":{"
                + "\"1\":{\"RegionID\":1,\"Nodes\":[{\"HostName\":\"stun.example\",\"STUNOnly\":true},{\"HostName\":\"derp1f.tailscale.com\"}]},"
                + "\"2\":{\"RegionID\":2,\"Avoid\":true,\"Nodes\":[{\"HostName\":\"derp2d.tailscale.com\"}]}}}";
        assertEquals(java.util.Collections.singletonList("derp1f.tailscale.com"), DerpMap.regionHosts(json));
    }
}
