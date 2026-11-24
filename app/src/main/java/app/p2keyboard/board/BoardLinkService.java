package app.p2keyboard.board;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import app.p2keyboard.MainActivity;
import app.p2keyboard.R;
import app.p2keyboard.core.BoardSession;
import app.p2keyboard.core.DerpMap;
import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;
import app.p2keyboard.core.RemoteOps;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keeps the board's link to the phone (over Tailscale DERP) running in the background, so the
 * phone stays paired while the keyboard is hidden, the screen is off, or another app is open.
 * The board keyboard ({@link RemoteKeyboardService}) binds to it and applies what arrives.
 */
public class BoardLinkService extends Service implements BoardSession.Listener {

    /** Implemented by the board keyboard. Called on the main thread. */
    public interface Ime {
        void onOp(String op);

        void onLinkChanged();
    }

    public static final String ACTION_NEW_CODE = "app.p2keyboard.NEW_CODE";
    static final String PREFS = "board";
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;

    private static BoardLinkService instance;
    private static Ime ime;

    private final Handler main = new Handler(Looper.getMainLooper());
    private BoardSession session;
    private boolean relayOnline;
    private boolean phoneConnected;
    private String editorState = RemoteOps.NO_FIELD;
    private WifiManager.WifiLock wifiLock;

    public final class LocalBinder extends Binder {
        public BoardLinkService service() {
            return BoardLinkService.this;
        }
    }

    /** Starts the link and marks this device as a board (so it also starts after a reboot). */
    public static void start(Context c) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("is_board", true).apply();
        Intent i = new Intent(c, BoardLinkService.class);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            // Android refuses background starts in some states; the keyboard's binding keeps it running.
        }
    }

    public static boolean isBoard(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("is_board", false);
    }

    public static BoardLinkService get() {
        return instance;
    }

    public static void setIme(Ime hook) {
        ime = hook;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW));
        goForeground();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            // Keep Wi-Fi awake with the screen off, so the phone can wake the board by typing.
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "p2keyboard:link");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        startSession(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent != null && ACTION_NEW_CODE.equals(intent.getAction())) newCode();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    @Override
    public void onDestroy() {
        if (session != null) session.stop();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        main.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
    }

    private void goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException e) {
            // Not allowed right now (app in background): keep running as a bound service.
        }
    }

    // ---- session --------------------------------------------------------------------------------

    private void startSession(boolean newCode) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String key = p.getString("key", null);
        if (key == null) {
            key = NaclBox.hex(NaclBox.newPrivateKey());
            p.edit().putString("key", key).apply();
        }
        String secret = p.getString("secret", null);
        if (secret == null || newCode) {
            secret = newSecret();
            p.edit().putString("secret", secret).apply();
        }
        String host = newCode ? null : p.getString("host", null);
        final byte[] priv = NaclBox.unhex(key);
        final String s = secret;
        if (host != null) {
            launch(host, priv, s);
            return;
        }
        // First run, or a new code: use the nearest Tailscale DERP region.
        new Thread(() -> {
            String nearest = DerpMap.pickNearest();
            p.edit().putString("host", nearest).apply();
            main.post(() -> launch(nearest, priv, s));
        }, "p2k-derpmap").start();
    }

    private void launch(String host, byte[] priv, String secret) {
        if (session != null) session.stop();
        relayOnline = false;
        phoneConnected = false;
        session = new BoardSession(host, priv, secret, this);
        session.start();
        changed();
    }

    private static String newSecret() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Invalidates the QR code, drops the phone, and re-picks the nearest relay. */
    public void newCode() {
        startSession(true);
    }

    public Pairing pairing() {
        return session == null ? null : session.pairing();
    }

    public boolean isRelayOnline() {
        return relayOnline;
    }

    public boolean isPhoneConnected() {
        return phoneConnected;
    }

    /** What the focused field is (RemoteOps.editorInfo or NO_FIELD); forwarded to the phone. */
    public void setEditorState(String state) {
        editorState = state;
        if (phoneConnected && session != null) session.sendToPhone(state);
    }

    /** The text before the board's cursor, shown on the phone as a live preview. */
    public void sendPreview(String textBeforeCursor) {
        if (phoneConnected && session != null) session.sendToPhone(RemoteOps.seed(textBeforeCursor));
    }

    // ---- BoardSession.Listener (network threads) ------------------------------------------------

    @Override
    public void onRelayOnline(boolean online) {
        main.post(() -> {
            relayOnline = online;
            changed();
        });
    }

    @Override
    public void onPhoneConnected() {
        main.post(() -> {
            phoneConnected = true;
            if (session != null) session.sendToPhone(editorState);
            changed();
        });
    }

    @Override
    public void onPhoneDisconnected() {
        main.post(() -> {
            phoneConnected = false;
            changed();
        });
    }

    @Override
    public void onOp(String op) {
        main.post(() -> {
            if (ime != null) ime.onOp(op);
            else if (session != null) session.sendToPhone(RemoteOps.NO_FIELD); // board keyboard not active
        });
    }

    private void changed() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        if (ime != null) ime.onLinkChanged();
    }

    private Notification notification() {
        String text;
        if (phoneConnected) text = getString(R.string.notif_connected);
        else if (relayOnline && session != null) text = getString(R.string.notif_waiting, session.pairing().host);
        else text = getString(R.string.connecting_relay);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent newCode = PendingIntent.getService(this, 1,
                new Intent(this, BoardLinkService.class).setAction(ACTION_NEW_CODE), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.new_code), newCode).build())
                .build();
    }
}
