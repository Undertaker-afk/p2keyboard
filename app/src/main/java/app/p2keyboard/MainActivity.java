package app.p2keyboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import app.p2keyboard.phone.PhoneActivity;

/** Launcher: the same APK goes on the board (as a keyboard) and on the phone (as the remote). */
public class MainActivity extends Activity {
    private TextView boardStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        boardStatus = findViewById(R.id.board_status);
        findViewById(R.id.btn_enable).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        findViewById(R.id.btn_select).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        findViewById(R.id.btn_phone).setOnClickListener(
                v -> startActivity(new Intent(this, PhoneActivity.class)));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String me = getPackageName() + "/";
        if (current != null && current.startsWith(me)) boardStatus.setText(R.string.status_active);
        else if (enabled != null && enabled.contains(me)) boardStatus.setText(R.string.status_enabled);
        else boardStatus.setText(R.string.status_off);
    }
}
