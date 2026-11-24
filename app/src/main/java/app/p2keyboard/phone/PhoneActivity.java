package app.p2keyboard.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import app.p2keyboard.R;
import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;
import app.p2keyboard.core.PhoneSession;
import app.p2keyboard.core.RemoteOps;

/**
 * Phone screen: locked to landscape, full screen, with the app's own on-screen keyboard. Every key
 * goes straight to the board over the DERP link; the line above the keys shows what the board's
 * text box actually holds.
 */
public class PhoneActivity extends Activity implements PhoneSession.Listener, KeyboardView.Listener {

    private static final String PREFS = "phone";
    private static final int MAX_PASTE = 5000;

    private PhoneSession session;
    private KeyboardView keyboard;
    private TextView status, preview;
    private boolean boardHasField = true;
    private String relayHost = "";
    private String boardText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_phone);
        keyboard = findViewById(R.id.keyboard);
        status = findViewById(R.id.status);
        preview = findViewById(R.id.preview);
        keyboard.setListener(this);
        findViewById(R.id.btn_scan).setOnClickListener(v -> scan());
        acceptPairingFrom(getIntent());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Full screen: more room for keys. Bars come back with a swipe from the edge. */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (acceptPairingFrom(intent)) restartSession();
    }

    @Override
    protected void onStart() {
        super.onStart();
        restartSession();
    }

    @Override
    protected void onStop() {
        if (session != null) session.stop();
        session = null;
        super.onStop();
    }

    // ---- pairing --------------------------------------------------------------------------------

    /** A board QR scanned with the stock camera opens p2k://pair?... straight into this screen. */
    private boolean acceptPairingFrom(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        return data != null && savePairing(data.toString());
    }

    private boolean savePairing(String text) {
        Pairing p = Pairing.parse(text);
        if (p == null) {
            Toast.makeText(this, R.string.not_a_board_code, Toast.LENGTH_LONG).show();
            return false;
        }
        prefs().edit().putString("pairing", p.toUri()).apply();
        return true;
    }

    private void scan() {
        new IntentIntegrator(this)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (r != null) {
            if (r.getContents() != null && savePairing(r.getContents())) restartSession();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void restartSession() {
        if (session != null) session.stop();
        session = null;
        boardText = "";
        renderPreview();
        Pairing p = Pairing.parse(prefs().getString("pairing", null));
        if (p == null) {
            setStatus(getString(R.string.phone_not_paired), Color.parseColor("#F0B429"));
            keyboard.setKeysEnabled(false);
            return;
        }
        relayHost = p.host;
        session = new PhoneSession(p, phoneKey(), this);
        renderStatus();
        session.start();
    }

    private byte[] phoneKey() {
        String k = prefs().getString("key", null);
        if (k == null) {
            k = NaclBox.hex(NaclBox.newPrivateKey());
            prefs().edit().putString("key", k).apply();
        }
        return NaclBox.unhex(k);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---- PhoneSession.Listener (network threads) ------------------------------------------------

    @Override
    public void onStatus(PhoneSession.Status s) {
        runOnUiThread(this::renderStatus);
    }

    @Override
    public void onBoardMessage(String m) {
        runOnUiThread(() -> {
            if (m.equals(RemoteOps.NO_FIELD)) {
                boardHasField = false;
                keyboard.setEnterLabel(null);
            } else if (m.charAt(0) == RemoteOps.EDITOR) {
                String[] parts = m.substring(1).split(":", 4);
                if (parts.length == 4) {
                    try {
                        keyboard.setEnterLabel(enterLabel(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), parts[3]));
                        boardHasField = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (m.charAt(0) == RemoteOps.SEED) {
                boardText = m.substring(1);
                renderPreview();
            }
            renderStatus();
        });
    }

    /** Caption for Enter so it matches what it will do on the board. */
    static String enterLabel(int inputType, int imeOptions, String actionLabel) {
        if ((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                || (imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "Enter";
        if (actionLabel != null && !actionLabel.isEmpty()) return actionLabel;
        switch (imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_SEARCH: return "Search";
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            case EditorInfo.IME_ACTION_DONE: return "Done";
            case EditorInfo.IME_ACTION_PREVIOUS: return "Prev";
            default: return "Enter";
        }
    }

    private void renderStatus() {
        PhoneSession s = session;
        if (s == null) return;
        boolean connected = s.status() == PhoneSession.Status.CONNECTED;
        keyboard.setKeysEnabled(connected);
        switch (s.status()) {
            case CONNECTING_RELAY:
                setStatus(getString(R.string.phone_connecting_relay, relayHost), Color.parseColor("#F0B429"));
                break;
            case WAITING_FOR_BOARD:
                setStatus(getString(R.string.phone_waiting_board), Color.parseColor("#F0B429"));
                break;
            case CONNECTED:
                setStatus(getString(boardHasField ? R.string.phone_connected : R.string.phone_no_field),
                        Color.parseColor(boardHasField ? "#35C46A" : "#F0B429"));
                break;
            case REJECTED:
                setStatus(getString(R.string.phone_rejected), Color.parseColor("#EF5350"));
                break;
        }
    }

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private void renderPreview() {
        // Last line of what the board's text box holds, with a caret where its cursor is.
        String t = boardText;
        int nl = t.lastIndexOf('\n');
        if (nl >= 0) t = t.substring(nl + 1);
        preview.setText(t + "\u258F");
    }

    // ---- KeyboardView.Listener ------------------------------------------------------------------

    private void send(String op) {
        PhoneSession s = session;
        if (s != null) s.send(op);
    }

    @Override
    public void onText(String text) {
        send(RemoteOps.commit(text, 1));
    }

    @Override
    public void onKey(int keyCode, int meta) {
        for (String op : KeyPress.ops(keyCode, meta)) send(op);
    }

    @Override
    public void onEnter() {
        send(RemoteOps.enter());
    }

    @Override
    public void onPaste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) return;
        String s = text.toString();
        if (s.length() > MAX_PASTE) s = s.substring(0, MAX_PASTE);
        send(RemoteOps.commit(s, 1));
    }
}
