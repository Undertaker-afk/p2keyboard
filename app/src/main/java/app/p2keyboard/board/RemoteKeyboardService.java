package app.p2keyboard.board;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import app.p2keyboard.R;
import app.p2keyboard.core.NetUtil;
import app.p2keyboard.core.PairingServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The board-side keyboard. It has no keys: it shows a pairing QR code and applies whatever the
 * paired phone sends to the focused text field.
 */
public class RemoteKeyboardService extends InputMethodService implements PairingServer.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private PairingServer server;
    private String serverError;
    private byte[] page;

    private View root, pairPanel;
    private ImageView qr;
    private TextView url, steps, connectedBar;
    private boolean phoneConnected;
    private boolean fieldFocused;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            server = new PairingServer(this::loadPage, this);
            server.start();
        } catch (IOException e) {
            serverError = e.getMessage();
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        root = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null);
        pairPanel = root.findViewById(R.id.pair_panel);
        qr = root.findViewById(R.id.qr);
        url = root.findViewById(R.id.url);
        steps = root.findViewById(R.id.steps);
        connectedBar = root.findViewById(R.id.connected_bar);
        root.findViewById(R.id.btn_new_code).setOnClickListener(v -> {
            if (server != null) server.rotateToken();
            refreshUi();
        });
        root.findViewById(R.id.btn_switch).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        refreshUi();
        return root;
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        fieldFocused = info != null && info.inputType != 0;
        if (server != null) server.sendToPhone(fieldFocused ? "f:1" : "f:0");
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        refreshUi(); // the board's IP may have changed since last time
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        fieldFocused = false;
        if (server != null) server.sendToPhone("f:0");
    }

    // ---- PairingServer.Listener (server threads -> main thread) --------------------------------

    @Override
    public void onPhoneConnected() {
        main.post(() -> {
            phoneConnected = true;
            if (server != null) server.sendToPhone(fieldFocused ? "f:1" : "f:0");
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
    public void onEdit(int deleteBefore, String insert) {
        main.post(() -> applyEdit(deleteBefore, insert));
    }

    @Override
    public void onKey(String name) {
        main.post(() -> applyKey(name));
    }

    // ---- Applying input -------------------------------------------------------------------------

    private void applyEdit(int del, String insert) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.beginBatchEdit();
        try {
            ic.finishComposingText();
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0 && del > 0) {
                ic.commitText("", 1); // a backspace over a selection removes the selection
                del--;
            }
            if (del > 0) ic.deleteSurroundingText(del, 0);
            if (!insert.isEmpty()) ic.commitText(insert, 1);
        } finally {
            ic.endBatchEdit();
        }
    }

    private void applyKey(String name) {
        switch (name) {
            case "enter": pressEnter(); break;
            case "tab": sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB); break;
            case "del": sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); break;
            case "left": sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT); break;
            case "right": sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT); break;
            case "up": sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP); break;
            case "down": sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN); break;
            case "home": sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME); break;
            case "end": sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END); break;
            default: break;
        }
    }

    private void pressEnter() {
        EditorInfo ei = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ei == null || ic == null) return;
        int action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = (ei.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean noEnterAction = (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!multiLine && !noEnterAction
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    // ---- UI -------------------------------------------------------------------------------------

    private void refreshUi() {
        if (root == null) return;
        connectedBar.setVisibility(phoneConnected ? View.VISIBLE : View.GONE);
        pairPanel.setVisibility(phoneConnected ? View.GONE : View.VISIBLE);
        if (phoneConnected) return;

        if (server == null || server.getPort() < 0) {
            qr.setImageDrawable(null);
            steps.setText("Could not start the pairing server: " + serverError);
            url.setText("");
            return;
        }
        String ip = NetUtil.lanAddress();
        if (ip == null) {
            qr.setImageDrawable(null);
            steps.setText(R.string.no_network);
            url.setText("");
            return;
        }
        steps.setText(R.string.pair_steps);
        String link = "http://" + ip + ":" + server.getPort() + "/?t=" + server.getToken();
        url.setText("http://" + ip + ":" + server.getPort());
        try {
            qr.setImageBitmap(QrCodes.render(link, 600));
        } catch (Exception e) {
            qr.setImageDrawable(null);
        }
    }

    private byte[] loadPage() throws IOException {
        if (page == null) {
            try (InputStream in = getAssets().open("remote.html")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                page = out.toByteArray();
            }
        }
        return page;
    }
}
