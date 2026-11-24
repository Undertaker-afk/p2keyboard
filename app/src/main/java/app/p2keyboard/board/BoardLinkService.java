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
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.p2keyboard.Identity;
import app.p2keyboard.MainActivity;
import app.p2keyboard.R;
import app.p2keyboard.core.BoardSession;
import app.p2keyboard.core.DerpMap;
import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;
import app.p2keyboard.core.RemoteOps;

/**
 * Keeps the board's link to its paired phones (over Tailscale DERP) running in the background, so
 * a phone can connect while the keyboard is hidden, the screen is off, or another app is open.
 * The board keyboard ({@link RemoteKeyboardService}) binds to it and applies what arrives.
 */
public class BoardLinkService extends Service implements BoardSession.Listener {

    /** Implemented by the board keyboard. Called on the main thread. */
    public interface Ime {
        void onOp(String op);

        void onLinkChanged();

        /** Switch from Phone Keyboard to {@code imeId}; true if it worked. */
        boolean switchAway(String imeId);
    }

    public static final String ACTION_NEW_CODE = "app.p2keyboard.NEW_CODE";
    public static final String ACTION_DISCONNECT = "app.p2keyboard.DISCONNECT";
    public static final String ACTION_TURN_OFF = "app.p2keyboard.TURN_OFF";
    static final String PREFS = "board";
    private static final String TAG = "p2k-board";
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;

    private static BoardLinkService instance;
    private static Ime ime;
    private static final List<Runnable> watchers = new CopyOnWriteArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private PairedPhones phones;
    private BoardSession session;
    private boolean relayOnline;
    private String connectedName; // null when no phone is connected
    private String editorState = RemoteOps.NO_FIELD;
    private WifiManager.WifiLock wifiLock;
    private boolean needsKeyboardPick; // phone connected but Android wouldn't let us switch keyboards
    private ContentObserver keyboardObserver;

    public final class LocalBinder extends Binder {
        public BoardLinkService service() {
            return BoardLinkService.this;
        }
    }

    /** Starts the link (if turned on) and marks this device as a board, so it also starts after a reboot. */
    public static void start(Context c) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("is_board", true).apply();
        if (!isEnabled(c)) return;
        try {
            c.startForegroundService(new Intent(c, BoardLinkService.class));
        } catch (RuntimeException e) {
            // Android refuses background starts in some states; the keyboard's binding keeps it running.
        }
    }

    /** The on/off switch: when off, nothing runs and the board just uses its normal keyboard. */
    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", true);
    }

    /** Turns the phone link on or off (from the app's switch or the notification). */
    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
        BoardLinkService s = instance;
        if (s != null) s.applyEnabled();
        else if (on) start(c);
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

    /** Screens that show board state register here; called on the main thread on every change. */
    public static void addWatcher(Runnable r) {
        watchers.add(r);
    }

    public static void removeWatcher(Runnable r) {
        watchers.remove(r);
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        phones = new PairedPhones(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));
        // Learn which keyboard the board normally uses, and keep Phone Keyboard out of the way
        // whenever no phone is connected.
        KeyboardSwitch.rememberNormal(this, KeyboardSwitch.currentId(this));
        keyboardObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                KeyboardSwitch.rememberNormal(BoardLinkService.this, KeyboardSwitch.currentId(BoardLinkService.this));
                applyKeyboard();
                changed();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, keyboardObserver);
        applyEnabled();
    }

    /** Starts or stops everything according to the on/off switch. */
    private void applyEnabled() {
        if (isEnabled(this)) {
            goForeground();
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    // Keep Wi-Fi awake with the screen off, so a phone can still connect and type.
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "p2keyboard:link");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            }
            if (session == null) startSession();
        } else {
            if (session != null) session.stop();
            session = null;
            relayOnline = false;
            connectedName = null;
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            wifiLock = null;
            applyKeyboard(); // back to the normal keyboard
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(); // ends once the keyboard unbinds
        }
        changed();
    }

    /**
     * Phone Keyboard is the active keyboard only while a phone is connected; otherwise the board
     * uses its normal keyboard.
     */
    public void applyKeyboard() {
        needsKeyboardPick = false;
        switch (KeyboardSwitch.decide(isEnabled(this), isPhoneConnected(), KeyboardSwitch.ourIsActive(this))) {
            case TO_PHONE_KEYBOARD:
                needsKeyboardPick = !KeyboardSwitch.switchTo(this, KeyboardSwitch.ourId(this));
                break;
            case TO_NORMAL_KEYBOARD: {
                String normal = KeyboardSwitch.normalId(this);
                if (normal != null && (ime == null || !ime.switchAway(normal))) KeyboardSwitch.switchTo(this, normal);
                break;
            }
            default:
                break;
        }
    }

    public boolean needsKeyboardPick() {
        return needsKeyboardPick;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (intent != null && ACTION_NEW_CODE.equals(intent.getAction())) newCode();
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) disconnectPhone();
        if (intent != null && ACTION_TURN_OFF.equals(intent.getAction())) setEnabled(this, false);
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
        if (keyboardObserver != null) getContentResolver().unregisterContentObserver(keyboardObserver);
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

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void startSession() {
        SharedPreferences p = prefs();
        String secret = p.getString("secret", null);
        if (secret == null) {
            secret = newSecret();
            p.edit().putString("secret", secret).apply();
        }
        String host = p.getString("host", null);
        final String s = secret;
        if (host != null) {
            launch(host, s);
            return;
        }
        // First run: use the nearest Tailscale DERP region. It stays fixed afterwards, because
        // paired phones remember which relay to find this board on.
        new Thread(() -> {
            String nearest = DerpMap.pickNearest();
            p.edit().putString("host", nearest).apply();
            main.post(() -> launch(nearest, s));
        }, "p2k-derpmap").start();
    }

    private void launch(String host, String secret) {
        if (session != null) session.stop();
        relayOnline = false;
        connectedName = null;
        session = new BoardSession(host, Identity.privateKey(this), secret, Identity.name(this), phones, this);
        session.start();
        changed();
    }

    private static String newSecret() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** New pairing QR code: the old one can no longer pair new phones. Paired phones keep working. */
    public void newCode() {
        String secret = newSecret();
        prefs().edit().putString("secret", secret).apply();
        if (session != null) session.setSecret(secret);
        changed();
    }

    /** Ends the current phone's session (it stays paired). */
    public void disconnectPhone() {
        if (session != null) session.disconnectPhone();
    }

    /** Removes a paired phone; it needs the QR code again to reconnect. */
    public void unpair(String phoneKeyHex) {
        phones.remove(phoneKeyHex);
        if (session != null) session.revoked(NaclBox.unhex(phoneKeyHex));
        changed();
    }

    public List<PairedPhones.Phone> pairedPhones() {
        return phones.list();
    }

    /** Renames this board; phones see the new name the next time they connect. */
    public void setBoardName(String name) {
        Identity.setName(this, name);
        if (session != null) session.setName(Identity.name(this));
        changed();
    }

    public Pairing pairing() {
        return session == null ? null : session.pairing();
    }

    public boolean isRelayOnline() {
        return relayOnline;
    }

    public boolean isPhoneConnected() {
        return connectedName != null;
    }

    /** Key of the connected phone, or null. */
    public String connectedPhoneKey() {
        byte[] k = session == null ? null : session.connectedPhone();
        return k == null ? null : NaclBox.hex(k);
    }

    public String connectedPhoneName() {
        return connectedName;
    }

    /** What the focused field is (RemoteOps.editorInfo or NO_FIELD); forwarded to the phone. */
    public void setEditorState(String state) {
        editorState = state;
        if (isPhoneConnected() && session != null) session.sendToPhone(state);
    }

    /** The text before the board's cursor, shown on the phone as a live preview. */
    public void sendPreview(String textBeforeCursor) {
        if (isPhoneConnected() && session != null) session.sendToPhone(RemoteOps.seed(textBeforeCursor));
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
    public void onPhoneConnected(byte[] phoneKey, String phoneName) {
        main.post(() -> {
            connectedName = phoneName.isEmpty() ? getString(R.string.unnamed_phone) : phoneName;
            if (session != null) session.sendToPhone(editorState);
            applyKeyboard(); // switch to Phone Keyboard
            changed();
        });
    }

    @Override
    public void onPhoneDisconnected() {
        main.post(() -> {
            connectedName = null;
            applyKeyboard(); // back to the normal keyboard
            changed();
        });
    }

    @Override
    public void onOp(String op) {
        main.post(() -> {
            try {
                if (ime != null) ime.onOp(op);
                else if (session != null) session.sendToPhone(RemoteOps.NO_FIELD); // board keyboard not active
            } catch (RuntimeException e) {
                Log.w(TAG, "could not apply a keystroke", e); // never let one keystroke take the keyboard down
            }
        });
    }

    private void changed() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (isEnabled(this)) nm.notify(NOTIFICATION_ID, notification());
        else nm.cancel(NOTIFICATION_ID);
        if (ime != null) ime.onLinkChanged();
        for (Runnable r : watchers) r.run();
    }

    private Notification notification() {
        String text;
        if (connectedName != null && needsKeyboardPick) text = getString(R.string.notif_pick_keyboard, connectedName);
        else if (connectedName != null) text = getString(R.string.notif_connected, connectedName);
        else if (relayOnline && session != null) text = getString(R.string.notif_waiting);
        else text = getString(R.string.connecting_relay);
        Intent openIntent = new Intent(this, MainActivity.class);
        // Without a way to switch keyboards ourselves, tapping offers the keyboard picker instead.
        if (connectedName != null && needsKeyboardPick) openIntent.setAction(MainActivity.ACTION_PICK_KEYBOARD);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.notif_title, Identity.name(this), Identity.deviceId(this)))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true);
        if (connectedName != null) {
            PendingIntent disconnect = PendingIntent.getService(this, 2,
                    new Intent(this, BoardLinkService.class).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.disconnect), disconnect).build());
        }
        PendingIntent off = PendingIntent.getService(this, 3,
                new Intent(this, BoardLinkService.class).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, getString(R.string.turn_off), off).build());
        return b.build();
    }
}
