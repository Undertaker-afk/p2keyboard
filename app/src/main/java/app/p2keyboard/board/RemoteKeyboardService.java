package app.p2keyboard.board;

import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import app.p2keyboard.R;
import app.p2keyboard.core.BoardSession;
import app.p2keyboard.core.DerpMap;
import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.RemoteOps;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The board's keyboard. It has no keys: it shows a pairing QR code and replays the paired
 * phone's keyboard operations (commit, compose, delete, key events, editor actions) on the
 * focused text field. The link runs over Tailscale's public DERP relays.
 */
public class RemoteKeyboardService extends InputMethodService implements BoardSession.Listener {

    private static final String PREFS = "board";

    private final Handler main = new Handler(Looper.getMainLooper());
    private BoardSession session;
    private boolean relayOnline;
    private boolean phoneConnected;
    private EditorInfo editor;

    private View root, pairPanel, connectedPanel;
    private ImageView qr;
    private TextView status, relay;

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        startSession(false);
    }

    @Override
    public void onDestroy() {
        if (session != null) session.stop();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Starts the relay session; with {@code newCode} it also rotates the secret and re-picks the relay. */
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
        // First run (or new code): find the nearest Tailscale DERP region off the main thread.
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
        refreshUi();
    }

    private static String newSecret() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- input view -----------------------------------------------------------------------------

    @Override
    public View onCreateInputView() {
        root = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null);
        pairPanel = root.findViewById(R.id.pair_panel);
        connectedPanel = root.findViewById(R.id.connected_panel);
        qr = root.findViewById(R.id.qr);
        status = root.findViewById(R.id.status);
        relay = root.findViewById(R.id.relay);
        View.OnClickListener newCode = v -> startSession(true);
        root.findViewById(R.id.btn_new_code).setOnClickListener(newCode);
        root.findViewById(R.id.btn_unpair).setOnClickListener(newCode);
        View.OnClickListener pick = v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        };
        root.findViewById(R.id.btn_switch).setOnClickListener(pick);
        root.findViewById(R.id.btn_switch2).setOnClickListener(pick);
        root.findViewById(R.id.btn_hide).setOnClickListener(v -> requestHideSelf(0));
        refreshUi();
        return root;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        editor = info;
        sendEditorState();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        refreshUi();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        editor = null;
        if (session != null) session.sendToPhone(RemoteOps.NO_FIELD);
    }

    /** Tells the phone what kind of field is focused, plus the text before the cursor. */
    private void sendEditorState() {
        if (session == null || !phoneConnected) return;
        EditorInfo ei = editor;
        if (ei == null || ei.inputType == InputType.TYPE_NULL) {
            session.sendToPhone(RemoteOps.NO_FIELD);
            return;
        }
        session.sendToPhone(RemoteOps.editorInfo(ei.inputType, ei.imeOptions, ei.actionId,
                ei.actionLabel == null ? "" : ei.actionLabel.toString()));
        InputConnection ic = getCurrentInputConnection();
        CharSequence before = ic == null ? null : ic.getTextBeforeCursor(RemoteOps.SEED_CHARS, 0);
        boolean password = (ei.inputType & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || (ei.inputType & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || (ei.inputType & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        session.sendToPhone(RemoteOps.seed(password || before == null ? "" : before.toString()));
    }

    // ---- BoardSession.Listener (network threads -> main thread) ---------------------------------

    @Override
    public void onRelayOnline(boolean online) {
        main.post(() -> {
            relayOnline = online;
            refreshUi();
        });
    }

    @Override
    public void onPhoneConnected() {
        main.post(() -> {
            phoneConnected = true;
            sendEditorState();
            refreshUi();
        });
    }

    @Override
    public void onPhoneDisconnected() {
        main.post(() -> {
            phoneConnected = false;
            refreshUi();
        });
    }

    @Override
    public void onOp(String op) {
        main.post(() -> OpApplier.apply(getCurrentInputConnection(), op));
    }

    // ---- UI -------------------------------------------------------------------------------------

    private void refreshUi() {
        if (root == null) return;
        pairPanel.setVisibility(phoneConnected ? View.GONE : View.VISIBLE);
        connectedPanel.setVisibility(phoneConnected ? View.VISIBLE : View.GONE);
        if (phoneConnected) return;

        if (session == null) {
            qr.setImageDrawable(null);
            status.setText(R.string.finding_relay);
            relay.setText("");
            return;
        }
        String host = session.pairing().host;
        relay.setText(getString(R.string.relay_line, host));
        if (!relayOnline) {
            status.setText(R.string.connecting_relay);
        } else {
            status.setText(R.string.pair_steps);
        }
        try {
            qr.setImageBitmap(QrCodes.render(session.pairing().toUri(), 600));
        } catch (Exception e) {
            qr.setImageDrawable(null);
        }
    }
}
