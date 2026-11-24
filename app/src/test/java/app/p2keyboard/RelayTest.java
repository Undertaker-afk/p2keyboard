package app.p2keyboard;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

import app.p2keyboard.board.OpApplier;
import app.p2keyboard.core.RemoteOps;
import app.p2keyboard.phone.RemoteInputView;

/**
 * Phone keyboard -> RemoteInputView -> wire ops -> OpApplier -> a real EditText on the "board".
 * The keyboard calls below mimic what Gboard/PixelBoard actually sends while typing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text layout, so arrow keys move the cursor like on a device
public class RelayTest {
    private EditText boardField;
    private InputConnection boardIc;
    private RemoteInputView phoneView;
    private InputConnection phoneIc;
    private final List<String> wire = new ArrayList<>();

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        LinearLayout root = new LinearLayout(activity);
        boardField = new EditText(activity);
        phoneView = new RemoteInputView(activity, null); // the phone: deliberately not in the board's window
        root.addView(boardField);
        activity.setContentView(root);
        boardField.requestFocus();
        giveWindowFocus(boardField);
        boardIc = boardField.onCreateInputConnection(new EditorInfo());

        phoneView.setSink(new RemoteInputView.Sink() {
            @Override
            public void send(String op) {
                wire.add(op);
                OpApplier.apply(boardIc, op); // in the app this hop is DERP, end-to-end encrypted
            }

            @Override
            public void onMirrorChanged() {}
        });
        phoneIc = phoneView.onCreateInputConnection(new EditorInfo());
    }

    /**
     * On a device the board's app window has window focus while our IME types into it; Robolectric's
     * test window doesn't, and Android drops IME key events for unfocused windows.
     */
    private static void giveWindowFocus(View v) {
        try {
            Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(v);
            viewRoot.getClass().getMethod("windowFocusChanged", boolean.class).invoke(viewRoot, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Key events reach a real field through the view root's event queue. */
    private void key(int code) {
        phoneIc.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        phoneIc.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private String board() {
        return boardField.getText().toString();
    }

    private String mirror() {
        return phoneView.mirror().toString();
    }

    @Test
    public void composingAutocorrectAndBackspace() {
        phoneIc.setComposingText("hel", 1);
        phoneIc.setComposingText("hello", 1);
        phoneIc.commitText("hello ", 1);
        assertEquals("hello ", board());

        phoneIc.setComposingText("wrold", 1);
        phoneIc.setComposingText("world", 1); // autocorrect rewrites the composing word
        phoneIc.finishComposingText();
        phoneIc.commitText(" ", 1);
        assertEquals("hello world ", board());

        phoneIc.deleteSurroundingText(1, 0); // backspace
        assertEquals("hello world", board());

        // Tap back into the last word and replace it (suggestion strip re-composition)
        int cur = phoneView.cursor();
        phoneIc.setComposingRegion(cur - 5, cur);
        phoneIc.setComposingText("earth", 1);
        phoneIc.finishComposingText();
        assertEquals("hello earth", board());

        assertEquals(mirror(), board());
    }

    @Test
    public void keyEventsAndCursorMoves() {
        phoneIc.commitText("abc", 1);
        key(KeyEvent.KEYCODE_DEL);
        assertEquals("ab", board());
        assertEquals("ab", mirror());

        phoneIc.setSelection(0, 0); // cursor to start (sent relative to the cursor)
        phoneIc.commitText("> ", 1);
        assertEquals("> ab", board());
        assertEquals("> ab", mirror());

        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        phoneIc.commitText("X", 1);
        assertEquals("> aXb", board());
    }

    @Test
    public void seededMirrorEditsExistingBoardText() {
        boardField.setText("Dear Bob, ");
        boardField.setSelection(boardField.length());
        boardIc = boardField.onCreateInputConnection(new EditorInfo());
        phoneView.seed("Dear Bob, ");
        phoneIc = phoneView.onCreateInputConnection(new EditorInfo());

        phoneIc.deleteSurroundingText(5, 0);
        phoneIc.commitText("Alice, ", 1);
        assertEquals("Dear Alice, ", board());
        assertEquals(board(), mirror());
    }

    @Test
    public void emojiAndWireFormat() {
        phoneIc.commitText("hi 👋", 1);
        phoneIc.deleteSurroundingText(2, 0); // one emoji = two UTF-16 units
        assertEquals("hi ", board());
        assertEquals(RemoteOps.commit("hi 👋", 1), wire.get(0));
    }
}
