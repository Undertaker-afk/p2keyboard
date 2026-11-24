package app.p2keyboard.phone;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

import app.p2keyboard.core.RemoteOps;

/**
 * Turns a key press into wire ops the way a hardware keyboard sends it: modifier keys go down
 * first and up last. Android's text fields rely on that, e.g. Shift+arrow only selects when the
 * Shift key itself was pressed.
 */
public final class KeyPress {
    private KeyPress() {}

    public static List<String> ops(int keyCode, int meta) {
        boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
        boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
        int ctrlMeta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        int shiftMeta = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        List<String> out = new ArrayList<>();
        int held = 0;
        if (ctrl) {
            held |= ctrlMeta;
            out.add(RemoteOps.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, held));
        }
        if (shift) {
            held |= shiftMeta;
            out.add(RemoteOps.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, held));
        }
        out.add(RemoteOps.key(KeyEvent.ACTION_DOWN, keyCode, held));
        out.add(RemoteOps.key(KeyEvent.ACTION_UP, keyCode, held));
        if (shift) {
            held &= ~shiftMeta;
            out.add(RemoteOps.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, held));
        }
        if (ctrl) {
            held &= ~ctrlMeta;
            out.add(RemoteOps.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, held));
        }
        return out;
    }
}
