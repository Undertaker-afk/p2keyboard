package app.p2keyboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import app.p2keyboard.phone.Keyboards;
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
        // Settings of the bundled HeliBoard keyboard (layouts, languages, swipe, themes)
        findViewById(R.id.btn_kb_settings).setOnClickListener(v -> startActivity(
                new Intent().setClassName(this, "helium314.keyboard.settings.SettingsActivity")));
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
        switch (Keyboards.boardState(this)) {
            case ACTIVE:
                boardStatus.setText(R.string.status_active);
                break;
            case NOT_SELECTED:
                boardStatus.setText(Keyboards.isOurs(Keyboards.current(this), this, Keyboards.TYPING_IME_CLASS)
                        ? R.string.status_other_ours : R.string.status_enabled);
                break;
            default:
                boardStatus.setText(R.string.status_off);
                break;
        }
    }
}
