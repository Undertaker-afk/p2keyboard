package app.p2keyboard.board;

import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import app.p2keyboard.core.RemoteOps;

/** Replays one phone keyboard op on the board's focused field. */
public final class OpApplier {
    private OpApplier() {}

    public static void apply(InputConnection ic, String op, EditorInfo editor) {
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
                    ic.sendKeyEvent(keyEvent(now, o.a, o.b, o.c));
                    break;
                }
                case RemoteOps.ACTION:
                    ic.performEditorAction(o.a);
                    break;
                case RemoteOps.ENTER:
                    enter(ic, editor);
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

    /** Enter: run the field's action (Search, Go, Send, Next) or insert a line break. */
    static void enter(InputConnection ic, EditorInfo ei) {
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = ei != null && (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean noEnterAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!multiLine && !noEnterAction
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            long now = SystemClock.uptimeMillis();
            ic.sendKeyEvent(keyEvent(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
            ic.sendKeyEvent(keyEvent(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0));
        }
    }

    static KeyEvent keyEvent(long time, int action, int code, int meta) {
        return new KeyEvent(time, time, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
    }

    /** Absolute cursor offset in the board's field. */
    private static int cursor(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(1 << 20, 0);
        return before == null ? 0 : before.length();
    }
}
