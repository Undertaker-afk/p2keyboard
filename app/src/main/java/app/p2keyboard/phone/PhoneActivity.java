package app.p2keyboard.phone;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import app.p2keyboard.R;
import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;
import app.p2keyboard.core.PhoneSession;
import app.p2keyboard.core.RemoteOps;

/** Phone screen: scan the board's QR code, then type with PixelBoard; everything goes to the board. */
public class PhoneActivity extends Activity implements PhoneSession.Listener, RemoteInputView.Sink {

    private static final String PREFS = "phone";

    private PhoneSession session;
    private RemoteInputView input;
    private TextView status, mirrorView, kbNote;
    private Button kbButton;
    private boolean boardHasField = true;
    private String relayHost = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone);
        input = findViewById(R.id.remote_input);
        status = findViewById(R.id.status);
        mirrorView = findViewById(R.id.mirror);
        kbNote = findViewById(R.id.kb_note);
        kbButton = findViewById(R.id.kb_button);
        input.setSink(this);
        mirrorView.setOnClickListener(v -> input.showKeyboard());
        findViewById(R.id.btn_scan).setOnClickListener(v -> scan());
        kbButton.setOnClickListener(v -> fixKeyboard());

        bindKey(R.id.k_tab, KeyEvent.KEYCODE_TAB);
        bindKey(R.id.k_left, KeyEvent.KEYCODE_DPAD_LEFT);
        bindKey(R.id.k_up, KeyEvent.KEYCODE_DPAD_UP);
        bindKey(R.id.k_down, KeyEvent.KEYCODE_DPAD_DOWN);
        bindKey(R.id.k_right, KeyEvent.KEYCODE_DPAD_RIGHT);
        bindKey(R.id.k_esc, KeyEvent.KEYCODE_ESCAPE);

        acceptPairingFrom(getIntent());
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
    protected void onResume() {
        super.onResume();
        refreshKeyboardBanner();
        input.postDelayed(input::showKeyboard, 200);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshKeyboardBanner(); // e.g. back from the keyboard picker
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
        if (data == null) return false;
        return savePairing(data.toString());
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
        Pairing p = Pairing.parse(prefs().getString("pairing", null));
        if (p == null) {
            setStatus(getString(R.string.phone_not_paired), Color.parseColor("#F0B429"));
            return;
        }
        relayHost = p.host;
        session = new PhoneSession(p, phoneKey(), this);
        setStatus(getString(R.string.phone_connecting_relay, p.host), Color.parseColor("#F0B429"));
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
            } else if (m.charAt(0) == RemoteOps.EDITOR) {
                String[] parts = m.substring(1).split(":", 4);
                if (parts.length == 4) {
                    try {
                        input.applyEditorInfo(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), parts[3]);
                        boardHasField = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (m.charAt(0) == RemoteOps.SEED) {
                input.seed(m.substring(1));
            }
            renderStatus();
        });
    }

    private void renderStatus() {
        PhoneSession s = session;
        if (s == null) return;
        switch (s.status()) {
            case CONNECTING_RELAY:
                setStatus(getString(R.string.phone_connecting_relay, relayHost), Color.parseColor("#F0B429"));
                break;
            case WAITING_FOR_BOARD:
                setStatus(getString(R.string.phone_waiting_board), Color.parseColor("#F0B429"));
                break;
            case CONNECTED:
                setStatus(getString(boardHasField ? R.string.phone_connected : R.string.phone_no_field),
                        boardHasField ? Color.parseColor("#35C46A") : Color.parseColor("#F0B429"));
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

    // ---- RemoteInputView.Sink -------------------------------------------------------------------

    @Override
    public void send(String op) {
        PhoneSession s = session;
        if (s != null) s.send(op);
    }

    @Override
    public void onMirrorChanged() {
        CharSequence text = input.mirror();
        int cursor = Math.min(input.cursor(), text.length());
        int from = Math.max(0, cursor - 600); // show the text around the cursor
        SpannableStringBuilder shown = new SpannableStringBuilder(text, from, text.length());
        shown.insert(cursor - from, "\u2502");
        mirrorView.setText(shown);
    }

    // ---- extra keys + keyboard banner -----------------------------------------------------------

    private void bindKey(int id, int keyCode) {
        findViewById(id).setOnClickListener(v -> {
            send(RemoteOps.key(KeyEvent.ACTION_DOWN, keyCode, 0));
            send(RemoteOps.key(KeyEvent.ACTION_UP, keyCode, 0));
        });
    }

    private void refreshKeyboardBanner() {
        PixelBoard.State st = PixelBoard.state(this);
        View banner = findViewById(R.id.kb_banner);
        banner.setVisibility(st == PixelBoard.State.ACTIVE ? View.GONE : View.VISIBLE);
        switch (st) {
            case NOT_INSTALLED:
                kbNote.setText(R.string.pb_not_installed);
                kbButton.setText(R.string.pb_get);
                break;
            case NOT_ENABLED:
                kbNote.setText(R.string.pb_not_enabled);
                kbButton.setText(R.string.pb_enable);
                break;
            case NOT_SELECTED:
                kbNote.setText(R.string.pb_not_selected);
                kbButton.setText(R.string.pb_select);
                break;
            default:
                break;
        }
    }

    private void fixKeyboard() {
        switch (PixelBoard.state(this)) {
            case NOT_INSTALLED:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PixelBoard.RELEASES_URL)));
                break;
            case NOT_ENABLED:
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                break;
            case NOT_SELECTED:
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
                break;
            default:
                break;
        }
    }
}
