package app.p2keyboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import app.p2keyboard.board.BoardLinkService;
import app.p2keyboard.board.RemoteKeyboardService;
import app.p2keyboard.phone.PhoneActivity;

/** Start screen: the same APK is the phone remote and the board's keyboard. */
public class MainActivity extends Activity {
    private TextView boardStatus, bgStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        boardStatus = findViewById(R.id.board_status);
        bgStatus = findViewById(R.id.bg_status);
        findViewById(R.id.btn_phone).setOnClickListener(
                v -> startActivity(new Intent(this, PhoneActivity.class)));
        findViewById(R.id.btn_enable).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        findViewById(R.id.btn_select).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        findViewById(R.id.btn_background).setOnClickListener(v -> allowBackground());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh(); // e.g. back from the keyboard picker
    }

    /** Lets the board's link run all the time: no battery limits, visible notification, started now. */
    @SuppressLint("BatteryLife") // this app's whole job on a board is to stay reachable
    private void allowBackground() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
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

    private void refresh() {
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
        boolean running = BoardLinkService.get() != null;
        bgStatus.setText(unrestricted
                ? (running ? R.string.bg_on_running : R.string.bg_on)
                : R.string.bg_off);
    }
}
