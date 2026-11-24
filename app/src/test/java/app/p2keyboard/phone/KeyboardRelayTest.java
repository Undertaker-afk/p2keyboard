package app.p2keyboard.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import app.p2keyboard.board.OpApplier;
import app.p2keyboard.core.RemoteOps;

/**
 * Taps on the phone's on-screen keyboard -> wire ops -> OpApplier -> a real EditText "on the board".
 * In the app the middle hop is the encrypted DERP link.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w800dp-h360dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class KeyboardRelayTest {
    private KeyboardView kb;
    private EditText board;
    private InputConnection boardIc;
    private EditorInfo boardEditor;
    private final List<String> wire = new ArrayList<>();
    private long downTime;

    @Before
    public void setUp() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        board = new EditText(activity);
        activity.setContentView(board);
        board.requestFocus();
        Object root = View.class.getMethod("getViewRootImpl").invoke(board); // give the test window focus,
        root.getClass().getMethod("windowFocusChanged", boolean.class).invoke(root, true); // as on a device
        boardEditor = new EditorInfo();
        boardIc = board.onCreateInputConnection(boardEditor);

        kb = new KeyboardView(activity, null);
        kb.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.EXACTLY));
        kb.layout(0, 0, 1600, 560);
        kb.setListener(new KeyboardView.Listener() { // same mapping as PhoneActivity
            public void onText(String t) { send(RemoteOps.commit(t, 1)); }
            public void onKey(int code, int meta) {
                for (String op : KeyPress.ops(code, meta)) send(op);
            }
            public void onEnter() { send(RemoteOps.enter()); }
            public void onPaste() { send(RemoteOps.commit("pasted", 1)); }
        });
    }

    private void send(String op) {
        wire.add(op);
        OpApplier.apply(boardIc, op, boardEditor);
        shadowOf(Looper.getMainLooper()).idle(); // key events travel through the view root's queue
    }

    private MotionEvent ev(int action, float x, float y) {
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
    }

    /** A real touch at the centre of the key with this caption. */
    private void tap(String label) {
        KeyboardView.Key k = kb.find(label);
        downTime = SystemClock.uptimeMillis();
        kb.onTouchEvent(ev(MotionEvent.ACTION_DOWN, k.rect.centerX(), k.rect.centerY()));
        kb.onTouchEvent(ev(MotionEvent.ACTION_UP, k.rect.centerX(), k.rect.centerY()));
    }

    private void type(String s) {
        for (char c : s.toCharArray()) tap(String.valueOf(c));
    }

    private String text() {
        return board.getText().toString();
    }

    @Test
    public void typesWordsWithShiftCapsAndSymbols() {
        tap("Shift");
        type("hello");
        assertEquals("Hello", text()); // Shift is one-shot
        tap(" ");
        tap("Shift");
        tap("1");                       // shifted number row
        assertEquals("Hello !", text());
        tap("Caps");
        type("ok");
        tap("Caps");
        type("x");
        assertEquals("Hello !OKx", text());
    }

    @Test
    public void backspaceArrowsAndCtrlShortcuts() {
        type("abc");
        tap("⌫");
        assertEquals("ab", text());
        tap("←");
        type("x");
        assertEquals("axb", text());
        tap("Ctrl");
        int before = wire.size();
        tap("a");
        // Ctrl+A goes out exactly like a hardware keyboard sends it: Ctrl down, A, Ctrl up.
        // (On a device, Android's key maps give no character for Ctrl+letter, so the field runs
        // its Select All shortcut; Robolectric's simplified key map can't show that part.)
        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        assertEquals(java.util.Arrays.asList(
                RemoteOps.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl),
                RemoteOps.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, ctrl),
                RemoteOps.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, ctrl),
                RemoteOps.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0)),
                wire.subList(before, wire.size()));
        assertFalse(kb.isCtrl());
    }

    @Test
    public void enterMatchesTheBoardField() {
        // multi-line field: Enter is a new line
        board.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        boardEditor = new EditorInfo();
        boardIc = board.onCreateInputConnection(boardEditor);
        type("hi");
        tap("Enter");
        type("yo");
        assertEquals("hi\nyo", text());

        // single-line search field: Enter runs the field's Search action
        final int[] action = {-1};
        board.setText("");
        board.setInputType(InputType.TYPE_CLASS_TEXT);
        board.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        board.setOnEditorActionListener((v, id, e) -> { action[0] = id; return true; });
        boardEditor = new EditorInfo();
        boardIc = board.onCreateInputConnection(boardEditor);
        type("cats");
        tap("Enter");
        assertEquals(EditorInfo.IME_ACTION_SEARCH, action[0]);
        assertEquals("cats", text());
        assertEquals("Search", PhoneActivity.enterLabel(boardEditor.inputType, boardEditor.imeOptions, ""));
    }

    @Test
    public void heldShiftSelectsWithArrows() {
        type("abcd");
        KeyboardView.Key shift = kb.find("Shift"), left = kb.find("←");
        downTime = SystemClock.uptimeMillis();
        // finger 1 holds Shift, finger 2 taps ← twice
        kb.onTouchEvent(ev(MotionEvent.ACTION_DOWN, shift.rect.centerX(), shift.rect.centerY()));
        for (int i = 0; i < 2; i++) {
            MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
            props[0].id = 0; props[1].id = 1;
            MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
            coords[0].x = shift.rect.centerX(); coords[0].y = shift.rect.centerY();
            coords[1].x = left.rect.centerX(); coords[1].y = left.rect.centerY();
            long t = SystemClock.uptimeMillis();
            kb.onTouchEvent(MotionEvent.obtain(downTime, t, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1, 1, 0, 0, 0, 0));
            kb.onTouchEvent(MotionEvent.obtain(downTime, t, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1, 1, 0, 0, 0, 0));
        }
        kb.onTouchEvent(ev(MotionEvent.ACTION_UP, shift.rect.centerX(), shift.rect.centerY()));
        // anchor stays at 4, cursor moved to 2: "cd" is selected
        assertEquals(2, Math.min(board.getSelectionStart(), board.getSelectionEnd()));
        assertEquals(4, Math.max(board.getSelectionStart(), board.getSelectionEnd()));
        assertFalse(kb.isShift()); // released after use
        type("x");
        assertEquals("abx", text());
    }

    @Test
    public void everyKeyIsReachableAndLayoutFillsTheView() {
        for (List<KeyboardView.Key> row : KeyboardView.layout()) {
            float units = 0;
            for (KeyboardView.Key k : row) units += k.width;
            assertEquals(14.6f, units, 0.001f);
        }
        KeyboardView.Key bs = kb.find("⌫");
        assertTrue(bs.rect.right > 1580 && bs.rect.top < 20); // top-right corner
        assertEquals(kb.find(" "), kb.keyAt(800, 540));        // space bar at the bottom middle
    }

    @Test
    public void rendersKeyboard() throws Exception {
        kb.setEnterLabel("Search");
        Bitmap bmp = Bitmap.createBitmap(1600, 560, Bitmap.Config.ARGB_8888);
        kb.draw(new Canvas(bmp));
        String out = System.getProperty("keyboardPng");
        if (out != null) {
            try (FileOutputStream f = new FileOutputStream(new File(out))) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
        }
        assertTrue(bmp.getPixel(800, 540) != 0);
    }
}
