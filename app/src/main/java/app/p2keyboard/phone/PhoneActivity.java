package app.p2keyboard.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.TextView;

import app.p2keyboard.Identity;
import app.p2keyboard.MainActivity;
import app.p2keyboard.R;
import app.p2keyboard.core.Pairing;
import app.p2keyboard.core.PhoneSession;
import app.p2keyboard.core.RemoteOps;

/**
 * Keyboard screen for one paired device: locked to landscape, full screen, with the app's own
 * on-screen keyboard. Every key goes straight to the board over the DERP link; the line above the
 * keys shows what the board's text box actually holds.
 */
public class PhoneActivity extends Activity implements PhoneSession.Listener, KeyboardView.Listener {

    public static final String EXTRA_BOARD = "board"; // board key (hex) from PairedBoards
    private static final int MAX_PASTE = 5000;

    private PairedBoards boards;
    private String boardKey;
    private PhoneSession session;
    private KeyboardView keyboard;
    private TextView status, preview;
    private Button action;
    private boolean boardHasField = true;
    private String boardLabel = "";
    private String boardText = "";

    /** Opens the keyboard connected to a paired board. */
    public static void open(Activity from, String boardKeyHex) {
        from.startActivity(new Intent(from, PhoneActivity.class).putExtra(EXTRA_BOARD, boardKeyHex)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_phone);
        boards = new PairedBoards(this);
        keyboard = findViewById(R.id.keyboard);
        status = findViewById(R.id.status);
        preview = findViewById(R.id.preview);
        action = findViewById(R.id.btn_action);
        keyboard.setListener(this);
        findViewById(R.id.btn_devices).setOnClickListener(v -> finish());
        boardKey = getIntent().getStringExtra(EXTRA_BOARD);
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
        String key = intent.getStringExtra(EXTRA_BOARD);
        if (key != null && !key.equals(boardKey)) {
            boardKey = key;
            connect();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        connect();
    }

    @Override
    protected void onStop() {
        if (session != null) session.stop();
        session = null;
        super.onStop();
    }

    // ---- connecting -----------------------------------------------------------------------------

    private void connect() {
        if (session != null) session.stop();
        session = null;
        boardText = "";
        renderPreview();
        Pairing p = boardKey == null ? null : boards.find(boardKey);
        if (p == null) { // removed meanwhile: back to the device list
            finish();
            return;
        }
        boardLabel = (p.name.isEmpty() ? getString(R.string.unnamed_board) : p.name);
        session = new PhoneSession(p, Identity.privateKey(this), Identity.name(this), this);
        renderStatus();
        session.start();
    }

    // ---- PhoneSession.Listener (network threads) ------------------------------------------------

    @Override
    public void onStatus(PhoneSession.Status s) {
        runOnUiThread(() -> {
            if (s == PhoneSession.Status.CONNECTED && boardKey != null) {
                boards.markPaired(boardKey, null); // paired: the one-time code is no longer needed
            }
            renderStatus();
        });
    }

    @Override
    public void onBoardName(String name) {
        runOnUiThread(() -> {
            if (boardKey != null) boards.markPaired(boardKey, name);
            if (!name.isEmpty()) boardLabel = name;
            renderStatus();
        });
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
        action.setVisibility(View.GONE);
        String who = boardLabel;
        switch (s.status()) {
            case CONNECTING_RELAY:
                setStatus(getString(R.string.phone_connecting, who), Color.parseColor("#F0B429"));
                break;
            case WAITING_FOR_BOARD:
                setStatus(getString(R.string.phone_waiting_board, who), Color.parseColor("#F0B429"));
                break;
            case CONNECTED:
                setStatus(getString(boardHasField ? R.string.phone_connected : R.string.phone_no_field, who),
                        Color.parseColor(boardHasField ? "#35C46A" : "#F0B429"));
                break;
            case DISCONNECTED:
                setStatus(getString(R.string.phone_disconnected, who), Color.parseColor("#F0B429"));
                action.setText(R.string.reconnect);
                action.setOnClickListener(v -> connect());
                action.setVisibility(View.VISIBLE);
                break;
            case NOT_PAIRED:
                setStatus(getString(R.string.phone_not_paired, who), Color.parseColor("#EF5350"));
                action.setText(R.string.pair_again);
                action.setOnClickListener(v -> {
                    MainActivity.startPairing(this);
                    finish();
                });
                action.setVisibility(View.VISIBLE);
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
