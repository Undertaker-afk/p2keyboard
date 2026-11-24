package app.p2keyboard.board;

import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import app.p2keyboard.core.RemoteOps;

/** Replays one phone keyboard op on the board's focused field. */
public final class OpApplier {
    private OpApplier() {}

    public static void apply(InputConnection ic, String op) {
        if (ic == null) return;
        RemoteOps.Op o = RemoteOps.parse(op);
        if (o == null) return;
        try {
            switch (o.code) {
                case RemoteOps.COMMIT:
                    ic.commitText(o.text, o.a);
                    break;
                case RemoteOps.COMPOSE:
                    ic.setComposingText(o.text, o.a);
                    break;
                case RemoteOps.FINISH:
                    ic.finishComposingText();
                    break;
                case RemoteOps.DELETE:
                    ic.deleteSurroundingText(o.a, o.b);
                    break;
                case RemoteOps.DELETE_CP:
                    ic.deleteSurroundingTextInCodePoints(o.a, o.b);
                    break;
                case RemoteOps.KEY: {
                    long now = SystemClock.uptimeMillis();
                    ic.sendKeyEvent(new KeyEvent(now, now, o.a, o.b, 0, o.c, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                            KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
                    break;
                }
                case RemoteOps.ACTION:
                    ic.performEditorAction(o.a);
                    break;
                case RemoteOps.MENU:
                    ic.performContextMenuAction(o.a);
                    break;
                case RemoteOps.SELECT: {
                    int cursor = cursor(ic);
                    ic.setSelection(Math.max(0, cursor + o.a), Math.max(0, cursor + o.b));
                    break;
                }
                case RemoteOps.REGION: {
                    int cursor = cursor(ic);
                    ic.setComposingRegion(Math.max(0, cursor + o.a), Math.max(0, cursor + o.b));
                    break;
                }
                default:
                    break;
            }
        } catch (RuntimeException ignored) {
            // an app's InputConnection throwing must never crash the keyboard
        }
    }

    /** Absolute cursor offset in the board's field. */
    private static int cursor(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(1 << 20, 0);
        return before == null ? 0 : before.length();
    }
}
