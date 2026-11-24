package app.p2keyboard.phone;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/**
 * Which keyboard the phone types with. The APK bundles HeliBoard (open-source AOSP-based keyboard,
 * see keyboard/UPSTREAM.md) for that. PixelBoard (patched Gboard) is accepted too if installed.
 */
public final class Keyboards {
    /** The bundled HeliBoard input method in this APK. */
    public static final String TYPING_IME_CLASS = "helium314.keyboard.latin.LatinIME";
    /** The board-side input method in this APK. */
    public static final String BOARD_IME_CLASS = "app.p2keyboard.board.RemoteKeyboardService";
    static final String PIXELBOARD_PACKAGE = "com.akshaykadam.pixelboard";

    public enum State { NOT_ENABLED, NOT_SELECTED, ACTIVE }

    private Keyboards() {}

    /** Component of the currently selected system keyboard, or null. */
    public static ComponentName current(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return id == null ? null : ComponentName.unflattenFromString(id);
    }

    public static boolean isOurs(ComponentName cn, Context c, String cls) {
        return cn != null && cn.getPackageName().equals(c.getPackageName()) && cn.getClassName().equals(cls);
    }

    /** Is a good keyboard (bundled HeliBoard, or PixelBoard) active for typing on the phone? */
    public static State typingState(Context c) {
        ComponentName cur = current(c);
        if (isOurs(cur, c, TYPING_IME_CLASS)) return State.ACTIVE;
        if (cur != null && PIXELBOARD_PACKAGE.equals(cur.getPackageName())) return State.ACTIVE;
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (i.getPackageName().equals(c.getPackageName()) && i.getServiceName().equals(TYPING_IME_CLASS)) {
                return State.NOT_SELECTED;
            }
        }
        return State.NOT_ENABLED;
    }

    /** Is this APK's board keyboard enabled / active? */
    public static State boardState(Context c) {
        if (isOurs(current(c), c, BOARD_IME_CLASS)) return State.ACTIVE;
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (i.getPackageName().equals(c.getPackageName()) && i.getServiceName().equals(BOARD_IME_CLASS)) {
                return State.NOT_SELECTED;
            }
        }
        return State.NOT_ENABLED;
    }
}
