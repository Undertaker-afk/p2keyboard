package app.p2keyboard.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Picks the nearest of Tailscale's public DERP relays for the board. */
public final class DerpMap {
    public static final String MAP_URL = "https://login.tailscale.com/derpmap/default";
    /** Used if the map can't be fetched. */
    public static final String FALLBACK_HOST = "derp1f.tailscale.com";

    private DerpMap() {}

    /** One relay host per region (regions marked Avoid are skipped). */
    public static List<String> regionHosts(String json) throws JSONException {
        List<String> hosts = new ArrayList<>();
        JSONObject regions = new JSONObject(json).getJSONObject("Regions");
        for (Iterator<String> it = regions.keys(); it.hasNext(); ) {
            JSONObject r = regions.getJSONObject(it.next());
            if (r.optBoolean("Avoid", false)) continue;
            JSONArray nodes = r.optJSONArray("Nodes");
            if (nodes == null) continue;
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                if (n.optBoolean("STUNOnly", false)) continue;
                String host = n.optString("HostName", "");
                if (!host.isEmpty()) {
                    hosts.add(host);
                    break;
                }
            }
        }
        return hosts;
    }

    /** Fetches the public map and returns the region host with the fastest TCP connect. */
    public static String pickNearest() {
        try {
            List<String> hosts = regionHosts(fetch(MAP_URL));
            String best = fastest(hosts, 1500);
            if (best != null) return best;
            if (!hosts.isEmpty()) return hosts.get(0);
        } catch (Exception ignored) {
        }
        return FALLBACK_HOST;
    }

    static String fastest(List<String> hosts, int timeoutMs) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(hosts.size(), 16)));
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (String h : hosts) {
                results.add(pool.submit(() -> {
                    long t0 = System.nanoTime();
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(h, 443), timeoutMs);
                    }
                    return System.nanoTime() - t0;
                }));
            }
            String best = null;
            long bestTime = Long.MAX_VALUE;
            for (int i = 0; i < hosts.size(); i++) {
                try {
                    long t = results.get(i).get(timeoutMs + 1000L, TimeUnit.MILLISECONDS);
                    if (t < bestTime) {
                        bestTime = t;
                        best = hosts.get(i);
                    }
                } catch (Exception ignored) {
                }
            }
            return best;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
