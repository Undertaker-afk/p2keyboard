package app.p2keyboard.board;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
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
import app.p2keyboard.core.Pairing;
import app.p2keyboard.core.RemoteOps;

/**
 * The board's input method. It has no keys: it shows the pairing QR code and replays the paired
 * phone's keystrokes on the focused text field. The link itself lives in {@link BoardLinkService}
 * so it keeps running in the background.
 */
public class RemoteKeyboardService extends InputMethodService implements BoardLinkService.Ime {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable previewTask = this::sendPreview;
    private EditorInfo editor;
    private boolean bound;

    private View root, pairPanel, connectedPanel;
    private ImageView qr;
    private TextView status, relay, connectedText;
    private String shownQr;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            sendEditorState();
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            refreshUi();
        }
    };

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        BoardLinkService.setIme(this);
        BoardLinkService.start(this);
        // Binding is allowed even when Android refuses a background start, and keeps the link alive.
        bound = bindService(new Intent(this, BoardLinkService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        BoardLinkService.setIme(null);
        if (bound) unbindService(connection);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static BoardLinkService link() {
        return BoardLinkService.get();
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
        connectedText = root.findViewById(R.id.connected_text);
        shownQr = null;
        root.findViewById(R.id.btn_new_code).setOnClickListener(v -> {
            BoardLinkService l = link();
            if (l != null) l.newCode();
        });
        root.findViewById(R.id.btn_disconnect).setOnClickListener(v -> {
            BoardLinkService l = link();
            if (l != null) l.disconnectPhone();
        });
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
        schedulePreview();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        refreshUi();
        // No phone connected (or Phone Keyboard turned off): use the board's normal keyboard.
        main.post(this::leaveIfIdle);
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        main.post(this::leaveIfIdle);
    }

    private void leaveIfIdle() {
        BoardLinkService l = link();
        boolean phoneConnected = l != null && l.isPhoneConnected() && BoardLinkService.isEnabled(this);
        if (phoneConnected) return;
        String normal = KeyboardSwitch.normalId(this);
        if (normal != null) switchAway(normal);
        // else: no other keyboard is enabled; the pairing panel stays as a fallback
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean switchAway(String imeId) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                switchInputMethod(imeId); // the active keyboard may always hand over to another one
            } else {
                IBinder token = getWindow().getWindow().getAttributes().token;
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).setInputMethod(token, imeId);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        editor = null;
        BoardLinkService l = link();
        if (l != null) l.setEditorState(RemoteOps.NO_FIELD);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        schedulePreview(); // text or cursor changed (also when someone edits on the board itself)
    }

    // ---- BoardLinkService.Ime -------------------------------------------------------------------

    @Override
    public void onOp(String op) {
        OpApplier.apply(getCurrentInputConnection(), op, editor);
        schedulePreview();
    }

    @Override
    public void onLinkChanged() {
        BoardLinkService l = link();
        if (l != null && l.isPhoneConnected()) {
            sendEditorState();
            schedulePreview();
        }
        refreshUi();
    }

    // ---- editor state + live preview for the phone ----------------------------------------------

    private static boolean isPassword(EditorInfo ei) {
        int v = ei.inputType & InputType.TYPE_MASK_VARIATION;
        int c = ei.inputType & InputType.TYPE_MASK_CLASS;
        return (c == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
                || (c == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private void sendEditorState() {
        BoardLinkService l = link();
        if (l == null) return;
        EditorInfo ei = editor;
        if (ei == null || ei.inputType == InputType.TYPE_NULL) {
            l.setEditorState(RemoteOps.NO_FIELD);
            return;
        }
        l.setEditorState(RemoteOps.editorInfo(ei.inputType, ei.imeOptions, ei.actionId,
                ei.actionLabel == null ? "" : ei.actionLabel.toString()));
    }

    private void schedulePreview() {
        main.removeCallbacks(previewTask);
        main.postDelayed(previewTask, 60); // coalesce bursts of keystrokes into one update
    }

    private void sendPreview() {
        try {
            sendPreviewUnsafe();
        } catch (RuntimeException e) {
            // Some apps' text fields throw from getTextBeforeCursor; the preview is optional.
        }
    }

    private void sendPreviewUnsafe() {
        BoardLinkService l = link();
        InputConnection ic = getCurrentInputConnection();
        EditorInfo ei = editor;
        if (l == null || !l.isPhoneConnected() || ic == null || ei == null) return;
        CharSequence before = ic.getTextBeforeCursor(RemoteOps.SEED_CHARS, 0);
        String text = before == null ? "" : before.toString();
        if (isPassword(ei)) { // never send password contents
            char[] dots = new char[text.length()];
            java.util.Arrays.fill(dots, '•');
            text = new String(dots);
        }
        l.sendPreview(text);
    }

    // ---- UI -------------------------------------------------------------------------------------

    private void refreshUi() {
        if (root == null) return;
        BoardLinkService l = link();
        boolean connected = l != null && l.isPhoneConnected();
        pairPanel.setVisibility(connected ? View.GONE : View.VISIBLE);
        connectedPanel.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (connected) {
            connectedText.setText(getString(R.string.connected, l.connectedPhoneName()));
            return;
        }

        Pairing p = l == null ? null : l.pairing();
        if (p == null) {
            qr.setImageDrawable(null);
            shownQr = null;
            status.setText(R.string.finding_relay);
            relay.setText("");
            return;
        }
        relay.setText(getString(R.string.board_line, p.name, p.deviceId(), p.host));
        status.setText(l.isRelayOnline() ? R.string.pair_steps : R.string.connecting_relay);
        String uri = p.toUri();
        if (!uri.equals(shownQr)) {
            try {
                qr.setImageBitmap(QrCodes.render(uri, 600));
                shownQr = uri;
            } catch (Exception e) {
                qr.setImageDrawable(null);
            }
        }
    }
}
