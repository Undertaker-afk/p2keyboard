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
    private static final String PREF_CHOSEN = "normal_ime_chosen";

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

    /** A candidate for "the board's normal keyboard". */
    public static final class Candidate {
        public final String id;
        public final CharSequence label;
        final boolean system, keyboard;

        public boolean isKeyboard() {
            return keyboard;
        }

        public Candidate(String id, CharSequence label, boolean system, boolean keyboard) {
            this.id = id;
            this.label = label;
            this.system = system;
            this.keyboard = keyboard;
        }
    }

    /**
     * Real keyboards only: voice input (Google voice typing etc.) is registered as an input method
     * too, but only offers "voice" subtypes. It also becomes the active input method for a moment
     * whenever someone taps the mic, so it must never be remembered as the board's keyboard.
     */
    static boolean isKeyboard(InputMethodInfo i) {
        int n = i.getSubtypeCount();
        if (n == 0) return !i.getId().toLowerCase(java.util.Locale.ROOT).contains("voice");
        for (int k = 0; k < n; k++) {
            String mode = i.getSubtypeAt(k).getMode();
            if (mode == null || mode.isEmpty() || "keyboard".equals(mode)) return true;
        }
        return false;
    }

    /** Enabled input methods other than Phone Keyboard, as candidates for the normal keyboard. */
    public static java.util.List<Candidate> candidates(Context c) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        java.util.List<Candidate> out = new java.util.ArrayList<>();
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (same(c, i.getId(), ourId(c))) continue;
            boolean system = i.getServiceInfo() != null && i.getServiceInfo().applicationInfo != null
                    && (i.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            CharSequence label;
            try {
                label = i.loadLabel(c.getPackageManager());
            } catch (RuntimeException e) {
                label = i.getId();
            }
            out.add(new Candidate(i.getId(), label, system, isKeyboard(i)));
        }
        return out;
    }

    /**
     * Picks the normal keyboard: the one chosen or last used (if it's a real keyboard), else the
     * board's preinstalled keyboard, else any keyboard. Voice input only as a last resort.
     */
    static String pick(java.util.List<Candidate> list, String saved) {
        Candidate firstSystemKeyboard = null, firstKeyboard = null;
        for (Candidate k : list) {
            if (!k.keyboard) continue;
            if (saved != null && sameId(k.id, saved)) return k.id;
            if (k.system && firstSystemKeyboard == null) firstSystemKeyboard = k;
            if (firstKeyboard == null) firstKeyboard = k;
        }
        if (firstSystemKeyboard != null) return firstSystemKeyboard.id;
        if (firstKeyboard != null) return firstKeyboard.id;
        return list.isEmpty() ? null : list.get(0).id;
    }

    private static boolean sameId(String a, String b) {
        ComponentName x = ComponentName.unflattenFromString(a), y = ComponentName.unflattenFromString(b);
        return x != null && x.equals(y);
    }

    /** Remembers the keyboard the board uses when someone switches to it (real keyboards only). */
    public static void rememberNormal(Context c, String id) {
        if (id == null || same(c, id, ourId(c))) return;
        if (prefs(c).getBoolean(PREF_CHOSEN, false)) return; // the user picked one explicitly
        for (Candidate k : candidates(c)) {
            if (k.keyboard && sameId(k.id, id)) {
                prefs(c).edit().putString(PREF_NORMAL, id).apply();
                return;
            }
        }
    }

    /** The user's explicit choice from the board screen (null = automatic). */
    public static void chooseNormal(Context c, String id) {
        prefs(c).edit().putString(PREF_NORMAL, id).putBoolean(PREF_CHOSEN, id != null).apply();
    }

    /** The board's normal keyboard, used whenever no phone is connected. */
    public static String normalId(Context c) {
        return pick(candidates(c), prefs(c).getString(PREF_NORMAL, null));
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
