package app.p2keyboard.board;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/**
 * Switches the board between its normal keyboard and Phone Keyboard. Phone Keyboard is only the
 * active keyboard while a phone is connected; otherwise the board uses its normal keyboard.
 *
 * <p>Android only lets the user change the system keyboard, with three sanctioned exceptions,
 * used in this order:
 * <ol>
 *   <li>WRITE_SECURE_SETTINGS, granted once over adb
 *       ({@code adb shell pm grant app.p2keyboard android.permission.WRITE_SECURE_SETTINGS});</li>
 *   <li>our accessibility service, which may switch keyboards on Android 11+;</li>
 *   <li>the active keyboard itself may switch away (handled in {@link RemoteKeyboardService}).</li>
 * </ol>
 */
public final class KeyboardSwitch {
    private static final String PREF_NORMAL = "normal_ime";

    private KeyboardSwitch() {}

    public enum Action { TO_PHONE_KEYBOARD, TO_NORMAL_KEYBOARD, NONE }

    /** Phone Keyboard should be active exactly while it's turned on and a phone is connected. */
    public static Action decide(boolean enabled, boolean phoneConnected, boolean phoneKeyboardActive) {
        boolean want = enabled && phoneConnected;
        if (want && !phoneKeyboardActive) return Action.TO_PHONE_KEYBOARD;
        if (!want && phoneKeyboardActive) return Action.TO_NORMAL_KEYBOARD;
        return Action.NONE;
    }

    /** Input-method ID of Phone Keyboard's board keyboard. */
    public static String ourId(Context c) {
        return new ComponentName(c, RemoteKeyboardService.class).flattenToShortString();
    }

    public static String currentId(Context c) {
        return Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    }

    public static boolean ourIsActive(Context c) {
        return same(c, currentId(c), ourId(c));
    }

    /** IDs can be written in short or long form ("pkg/.Cls" vs "pkg/pkg.Cls"). */
    static boolean same(Context c, String a, String b) {
        if (a == null || b == null) return false;
        ComponentName x = ComponentName.unflattenFromString(a), y = ComponentName.unflattenFromString(b);
        return x != null && x.equals(y);
    }

    /** Remembers the keyboard the board normally uses (anything that isn't Phone Keyboard). */
    public static void rememberNormal(Context c, String id) {
        if (id == null || same(c, id, ourId(c))) return;
        prefs(c).edit().putString(PREF_NORMAL, id).apply();
    }

    /** The board's normal keyboard: the last one it used, else the first other enabled keyboard. */
    public static String normalId(Context c) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        String saved = prefs(c).getString(PREF_NORMAL, null);
        String first = null;
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (same(c, i.getId(), ourId(c))) continue;
            if (saved != null && same(c, i.getId(), saved)) return i.getId();
            if (first == null) first = i.getId();
        }
        return first;
    }

    public static boolean canWriteSettings(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Can this board switch keyboards on its own (needed to switch *to* Phone Keyboard)? */
    public static boolean canSwitchAutomatically(Context c) {
        return canWriteSettings(c) || (Build.VERSION.SDK_INT >= 30 && SwitcherAccessibilityService.isRunning());
    }

    /** Makes {@code id} the active keyboard if allowed; returns false if the user must pick it. */
    public static boolean switchTo(Context c, String id) {
        if (id == null) return false;
        if (same(c, currentId(c), id)) return true;
        if (canWriteSettings(c)) {
            try {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, id);
                return true;
            } catch (SecurityException ignored) {
            }
        }
        return Build.VERSION.SDK_INT >= 30 && SwitcherAccessibilityService.switchTo(id);
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(BoardLinkService.PREFS, Context.MODE_PRIVATE);
    }
}
