package app.p2keyboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import app.p2keyboard.board.BoardLinkService;
import app.p2keyboard.board.PairedPhones;
import app.p2keyboard.board.QrCodes;
import app.p2keyboard.board.RemoteKeyboardService;
import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;
import app.p2keyboard.phone.PairedBoards;
import app.p2keyboard.phone.PhoneActivity;
import app.p2keyboard.phone.ScanActivity;

/**
 * Home screen, RustDesk style: this device's name and ID, "Pair device", the list of paired
 * devices to connect to, and (for a smart board) its keyboard setup and paired phones.
 */
public class MainActivity extends Activity {
    private static final String ACTION_PAIR = "app.p2keyboard.PAIR";
    private static final int REQ_CAMERA = 1, REQ_NOTIFICATIONS = 2, REQ_SCAN = 3;

    private PairedBoards boards;
    private TextView deviceName, deviceId, boardStatus, bgStatus, boardInfo;
    private LinearLayout boardList, phoneList, boardQrBox;
    private ImageView boardQr;
    private String shownQr;
    private final Runnable watcher = () -> runOnUiThread(this::refresh);

    /** Opens the home screen and starts "Pair device" (camera permission, then the scanner). */
    public static void startPairing(Activity from) {
        from.startActivity(new Intent(from, MainActivity.class).setAction(ACTION_PAIR)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        boards = new PairedBoards(this);
        deviceName = findViewById(R.id.device_name);
        deviceId = findViewById(R.id.device_id);
        boardList = findViewById(R.id.board_list);
        phoneList = findViewById(R.id.phone_list);
        boardStatus = findViewById(R.id.board_status);
        bgStatus = findViewById(R.id.bg_status);
        boardInfo = findViewById(R.id.board_info);
        boardQrBox = findViewById(R.id.board_qr_box);
        boardQr = findViewById(R.id.board_qr);

        findViewById(R.id.device_card).setOnClickListener(v -> rename());
        findViewById(R.id.btn_pair).setOnClickListener(v -> pairDevice());
        findViewById(R.id.btn_enable).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        findViewById(R.id.btn_select).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        findViewById(R.id.btn_background).setOnClickListener(v -> allowBackground());
        findViewById(R.id.btn_board_new_code).setOnClickListener(v -> {
            BoardLinkService l = BoardLinkService.get();
            if (l != null) l.newCode();
        });
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** "Pair device" requests, and board QR codes scanned with the stock camera (p2k://pair?...). */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (ACTION_PAIR.equals(intent.getAction())) {
            intent.setAction(null);
            pairDevice();
        } else if (intent.getData() != null) {
            String uri = intent.getData().toString();
            intent.setData(null);
            accept(uri);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        BoardLinkService.addWatcher(watcher);
        refresh();
    }

    @Override
    protected void onPause() {
        BoardLinkService.removeWatcher(watcher);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh(); // e.g. back from the keyboard picker
    }

    // ---- pair device ----------------------------------------------------------------------------

    private void pairDevice() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // "Don't ask again": the only way left is the app's settings page.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.camera_title)
                    .setMessage(R.string.camera_denied_forever)
                    .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            Toast.makeText(this, R.string.camera_needed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            accept(data.getStringExtra(ScanActivity.EXTRA_PAIRING));
        }
    }

    /** Saves a scanned board and connects to it (the first connection completes the pairing). */
    private void accept(String uri) {
        Pairing p = Pairing.parse(uri);
        if (p == null) {
            Toast.makeText(this, R.string.not_a_board_code, Toast.LENGTH_LONG).show();
            return;
        }
        if (NaclBox.hex(p.boardKey).equals(NaclBox.hex(NaclBox.publicKey(Identity.privateKey(this))))) {
            Toast.makeText(this, R.string.cant_pair_self, Toast.LENGTH_LONG).show();
            return;
        }
        boards.put(p);
        PhoneActivity.open(this, NaclBox.hex(p.boardKey));
    }

    // ---- this device ----------------------------------------------------------------------------

    private void rename() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(Identity.name(this));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setMessage(R.string.rename_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = Pairing.cleanName(input.getText().toString());
                    if (n.isEmpty()) return;
                    BoardLinkService l = BoardLinkService.get();
                    if (l != null) l.setBoardName(n);
                    else Identity.setName(this, n);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- board role -----------------------------------------------------------------------------

    /** Lets the board's link run all the time: no battery limits, visible notification, started now. */
    @SuppressLint("BatteryLife") // this app's whole job on a board is to stay reachable
    private void allowBackground() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }
        BoardLinkService.start(this); // we're in the foreground here, so this is always allowed
        refresh();
    }

    private boolean boardImeIs(boolean selected) {
        ComponentName me = new ComponentName(this, RemoteKeyboardService.class);
        if (selected) {
            String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            return me.equals(id == null ? null : ComponentName.unflattenFromString(id));
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (me.equals(i.getComponent())) return true;
        }
        return false;
    }

    // ---- rendering ------------------------------------------------------------------------------

    private void refresh() {
        deviceName.setText(Identity.name(this));
        deviceId.setText(getString(R.string.device_id, Identity.deviceId(this)));
        renderBoards();

        if (boardImeIs(true)) {
            boardStatus.setText(R.string.status_active);
            BoardLinkService.start(this); // a board: make sure the link is up
        } else if (boardImeIs(false)) {
            boardStatus.setText(R.string.status_enabled);
        } else {
            boardStatus.setText(R.string.status_off);
        }
        PowerManager pm = getSystemService(PowerManager.class);
        boolean unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        BoardLinkService link = BoardLinkService.get();
        bgStatus.setText(unrestricted ? (link != null ? R.string.bg_on_running : R.string.bg_on) : R.string.bg_off);
        renderBoardRole(link);
    }

    /** The boards this phone can connect to. */
    private void renderBoards() {
        boardList.removeAllViews();
        List<Pairing> list = boards.list();
        if (list.isEmpty()) {
            boardList.addView(note(getString(R.string.no_devices)));
            return;
        }
        for (Pairing p : list) {
            String key = NaclBox.hex(p.boardKey);
            String name = p.name.isEmpty() ? getString(R.string.unnamed_board) : p.name;
            String sub = getString(R.string.device_id, p.deviceId())
                    + (p.secret.isEmpty() ? "" : " · " + getString(R.string.not_yet_connected));
            View row = row(name, sub, getString(R.string.connect));
            row.setOnClickListener(v -> PhoneActivity.open(this, key));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(name)
                        .setMessage(R.string.remove_board_message)
                        .setPositiveButton(R.string.remove, (d, w) -> {
                            boards.remove(key);
                            refresh();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            boardList.addView(row);
        }
    }

    /** On a board: its pairing QR code and the phones paired with it. */
    private void renderBoardRole(BoardLinkService link) {
        boolean board = link != null;
        boardQrBox.setVisibility(board ? View.VISIBLE : View.GONE);
        phoneList.removeAllViews();
        if (!board) return;

        Pairing p = link.pairing();
        if (p == null) {
            boardInfo.setText(R.string.finding_relay);
            boardQr.setImageDrawable(null);
            shownQr = null;
        } else {
            boardInfo.setText(getString(link.isRelayOnline() ? R.string.board_qr_ready : R.string.connecting_relay));
            String uri = p.toUri();
            if (!uri.equals(shownQr)) {
                try {
                    boardQr.setImageBitmap(QrCodes.render(uri, 500));
                    shownQr = uri;
                } catch (Exception e) {
                    boardQr.setImageDrawable(null);
                }
            }
        }

        List<PairedPhones.Phone> phones = link.pairedPhones();
        if (phones.isEmpty()) {
            phoneList.addView(note(getString(R.string.no_phones)));
            return;
        }
        String connected = link.connectedPhoneKey();
        for (PairedPhones.Phone ph : phones) {
            boolean on = ph.keyHex.equals(connected);
            String name = ph.name.isEmpty() ? getString(R.string.unnamed_phone) : ph.name;
            View row = row(name, getString(R.string.device_id, ph.deviceId()),
                    on ? getString(R.string.connected_badge) : "");
            row.setOnClickListener(v -> {
                AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle(name)
                        .setMessage(R.string.phone_actions_message)
                        .setPositiveButton(R.string.unpair, (dd, w) -> link.unpair(ph.keyHex))
                        .setNegativeButton(android.R.string.cancel, null);
                if (on) d.setNeutralButton(R.string.disconnect, (dd, w) -> link.disconnectPhone());
                d.show();
            });
            phoneList.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF8D99A6);
        t.setTextSize(14);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    /** A device row: name, ID line, and a right-hand badge. */
    private View row(String title, String subtitle, String badge) {
        Context c = this;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.row_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(0xFFE8EDF2);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        TextView s = new TextView(c);
        s.setText(subtitle);
        s.setTextColor(0xFF8D99A6);
        s.setTextSize(13);
        text.addView(t);
        text.addView(s);
        row.addView(text);

        if (!badge.isEmpty()) {
            TextView b = new TextView(c);
            b.setText(badge);
            b.setTextColor(0xFF5AA9FF);
            b.setTextSize(14);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(b);
        }
        return row;
    }
}
