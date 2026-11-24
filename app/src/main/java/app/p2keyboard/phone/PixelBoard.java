package app.p2keyboard.phone;

import android.content.Context;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/**
 * PixelBoard (github.com/Akshayykadam/PixelBoard) is Gboard rebuilt with patches and installed as
 * its own package. It is the keyboard the phone types with.
 */
final class PixelBoard {
    static final String PACKAGE = "com.akshaykadam.pixelboard";
    static final String RELEASES_URL = "https://github.com/Akshayykadam/PixelBoard/releases/latest";

    enum State { NOT_INSTALLED, NOT_ENABLED, NOT_SELECTED, ACTIVE }

    private PixelBoard() {}

    static State state(Context c) {
        String current = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (current != null && current.startsWith(PACKAGE + "/")) return State.ACTIVE;
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (PACKAGE.equals(i.getPackageName())) return State.NOT_SELECTED;
        }
        for (InputMethodInfo i : imm.getInputMethodList()) {
            if (PACKAGE.equals(i.getPackageName())) return State.NOT_ENABLED;
        }
        return State.NOT_INSTALLED;
    }
}
