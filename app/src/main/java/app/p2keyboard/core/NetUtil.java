package app.p2keyboard.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finds the board's LAN address so the QR code points somewhere the phone can reach. */
public final class NetUtil {
    private NetUtil() {}

    /** Best private IPv4 address (Wi-Fi / Ethernet preferred), or null when offline. */
    public static String lanAddress() {
        String best = null;
        int bestScore = -1;
        try {
            List<NetworkInterface> ifaces = new ArrayList<>();
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) ifaces.add(n);
            for (NetworkInterface n : ifaces) {
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                String name = n.getName().toLowerCase();
                int score = name.startsWith("wlan") || name.startsWith("wifi") ? 3
                        : name.startsWith("eth") ? 2
                        : name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ? -2
                        : 1;
                if (score < 0) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress() && score > bestScore) {
                        best = a.getHostAddress();
                        bestScore = score;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }
}
