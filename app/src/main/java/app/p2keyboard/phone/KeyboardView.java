package app.p2keyboard.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's on-screen keyboard, drawn by the app itself (no Android input method involved).
 * A full PC-style layout for landscape: number row, symbols, Tab/Esc, Shift/Caps/Ctrl modifiers,
 * arrows and Paste. Multi-touch, so two thumbs can type fast; Backspace and arrows repeat.
 */
public class KeyboardView extends View {

    public interface Listener {
        /** Characters to type. */
        void onText(String text);

        /** A non-character key (keyCode from {@link KeyEvent}) with modifier meta state. */
        void onKey(int keyCode, int meta);

        /** Enter: the board decides between its field's action (Search/Go/Send) and a new line. */
        void onEnter();

        /** Paste the phone's clipboard. */
        void onPaste();
    }

    static final int CHAR = 0, SPECIAL = 1, SHIFT = 2, CAPS = 3, CTRL = 4, ENTER = 5, PASTE = 6;

    static final class Key {
        final int type;
        final String label;      // character (CHAR) or caption (others)
        final String shifted;    // CHAR only
        final int keyCode;       // SPECIAL only
        final float width;
        final boolean repeat;
        final RectF rect = new RectF();

        Key(int type, String label, String shifted, int keyCode, float width, boolean repeat) {
            this.type = type;
            this.label = label;
            this.shifted = shifted;
            this.keyCode = keyCode;
            this.width = width;
            this.repeat = repeat;
        }

        boolean isLetter() {
            return type == CHAR && label.length() == 1 && Character.isLetter(label.charAt(0));
        }
    }

    private static Key ch(String normal, String shifted) {
        return new Key(CHAR, normal, shifted, 0, 1f, false);
    }

    private static Key sp(String caption, int keyCode, float width, boolean repeat) {
        return new Key(SPECIAL, caption, null, keyCode, width, repeat);
    }

    private static Key mod(int type, String caption, float width) {
        return new Key(type, caption, null, 0, width, false);
    }

    private static List<Key> chars(String normal, String shifted) {
        List<Key> out = new ArrayList<>();
        for (int i = 0; i < normal.length(); i++) {
            out.add(ch(String.valueOf(normal.charAt(i)), String.valueOf(shifted.charAt(i))));
        }
        return out;
    }

    /** Every row adds up to 14.6 units, so the keys line up like a real keyboard. */
    static List<List<Key>> layout() {
        List<List<Key>> rows = new ArrayList<>();

        List<Key> r0 = chars("`1234567890-=", "~!@#$%^&*()_+");
        r0.add(sp("⌫", KeyEvent.KEYCODE_DEL, 1.6f, true));
        rows.add(r0);

        List<Key> r1 = new ArrayList<>();
        r1.add(sp("Tab", KeyEvent.KEYCODE_TAB, 1.4f, false));
        r1.addAll(chars("qwertyuiop[]", "QWERTYUIOP{}"));
        Key backslash = new Key(CHAR, "\\", "|", 0, 1.2f, false);
        r1.add(backslash);
        rows.add(r1);

        List<Key> r2 = new ArrayList<>();
        r2.add(mod(CAPS, "Caps", 1.7f));
        r2.addAll(chars("asdfghjkl;'", "ASDFGHJKL:\""));
        r2.add(new Key(ENTER, "Enter", null, 0, 1.9f, false));
        rows.add(r2);

        List<Key> r3 = new ArrayList<>();
        r3.add(mod(SHIFT, "Shift", 2.2f));
        r3.addAll(chars("zxcvbnm,./", "ZXCVBNM<>?"));
        r3.add(mod(SHIFT, "Shift", 2.4f));
        rows.add(r3);

        List<Key> r4 = new ArrayList<>();
        r4.add(mod(CTRL, "Ctrl", 1.3f));
        r4.add(sp("Esc", KeyEvent.KEYCODE_ESCAPE, 1.2f, false));
        r4.add(new Key(PASTE, "Paste", null, 0, 1.5f, false));
        r4.add(new Key(CHAR, " ", " ", 0, 6.6f, false));
        r4.add(sp("←", KeyEvent.KEYCODE_DPAD_LEFT, 1f, true));
        r4.add(sp("↑", KeyEvent.KEYCODE_DPAD_UP, 1f, true));
        r4.add(sp("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f, true));
        r4.add(sp("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, true));
        rows.add(r4);
        return rows;
    }

    private final List<List<Key>> rows = layout();
    private final SparseArray<Key> pressed = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Listener listener;
    private boolean shift, caps, ctrl, enabledKeys = true;
    private boolean shiftUsedWhileHeld, ctrlUsedWhileHeld;
    private String enterLabel = "Enter";
    private Runnable repeater;

    public KeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        textPaint.setColor(0xFFE8EDF2);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        hintPaint.setColor(0xFF8D99A6);
        hintPaint.setTextAlign(Paint.Align.LEFT);
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Caption for Enter, matching the board field's action ("Search", "Go", "Send", ...). */
    public void setEnterLabel(String label) {
        enterLabel = label == null || label.isEmpty() ? "Enter" : label;
        invalidate();
    }

    /** Greys the keys out while there is no board to type to. */
    public void setKeysEnabled(boolean enabled) {
        enabledKeys = enabled;
        invalidate();
    }

    boolean isShift() { return shift; }
    boolean isCaps() { return caps; }
    boolean isCtrl() { return ctrl; }

    /** Key whose centre is at (x, y) in view coordinates; for tests. */
    Key keyAt(float x, float y) {
        for (List<Key> row : rows) {
            for (Key k : row) {
                if (k.rect.contains(x, y)) return k;
            }
        }
        // Touches in the gaps go to the nearest key.
        Key best = null;
        float bestD = Float.MAX_VALUE;
        for (List<Key> row : rows) {
            for (Key k : row) {
                float dx = Math.max(0, Math.max(k.rect.left - x, x - k.rect.right));
                float dy = Math.max(0, Math.max(k.rect.top - y, y - k.rect.bottom));
                float d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
        }
        return best;
    }

    /** Finds a key by its caption or character; for tests. */
    Key find(String label) {
        for (List<Key> row : rows) {
            for (Key k : row) {
                if (k.label.equals(label)) return k;
            }
        }
        return null;
    }

    // ---- layout + drawing -----------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float pad = 4 * density, gap = 5 * density;
        float rowH = (h - 2 * pad - gap * (rows.size() - 1)) / rows.size();
        for (int r = 0; r < rows.size(); r++) {
            List<Key> row = rows.get(r);
            float units = 0;
            for (Key k : row) units += k.width;
            float unitW = (w - 2 * pad - gap * (row.size() - 1)) / units;
            float x = pad, y = pad + r * (rowH + gap);
            for (Key k : row) {
                float kw = k.width * unitW;
                k.rect.set(x, y, x + kw, y + rowH);
                x += kw + gap;
            }
        }
        textPaint.setTextSize(Math.min(rowH * 0.42f, 26 * density));
        hintPaint.setTextSize(Math.min(rowH * 0.24f, 13 * density));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF101418);
        float radius = 7 * density;
        boolean upper = shift ^ caps;
        for (List<Key> row : rows) {
            for (Key k : row) {
                boolean down = pressed.indexOfValue(k) >= 0;
                boolean active = (k.type == SHIFT && shift) || (k.type == CAPS && caps) || (k.type == CTRL && ctrl);
                int color;
                if (down) color = 0xFF4A5868;
                else if (active) color = 0xFF2F5D8A;
                else if (k.type == ENTER) color = 0xFF5AA9FF;
                else if (k.type == CHAR && !" ".equals(k.label)) color = 0xFF2A323C;
                else color = 0xFF1E252D;
                keyPaint.setColor(enabledKeys ? color : 0xFF1A1F25);
                c.drawRoundRect(k.rect, radius, radius, keyPaint);

                String caption;
                if (k.type == CHAR) {
                    boolean useShift = k.isLetter() ? upper : shift;
                    caption = useShift ? k.shifted : k.label;
                    if (" ".equals(k.label)) caption = "";
                    // small hint of the shifted symbol on number/symbol keys
                    if (!k.isLetter() && !useShift && !" ".equals(k.label)) {
                        c.drawText(k.shifted, k.rect.left + 6 * density, k.rect.top + hintPaint.getTextSize() + 3 * density, hintPaint);
                    }
                } else if (k.type == ENTER) {
                    caption = enterLabel;
                } else {
                    caption = k.label;
                }
                int textColor = k.type == ENTER ? 0xFF06223F : 0xFFE8EDF2;
                textPaint.setColor(enabledKeys ? textColor : 0xFF55606C);
                float size = textPaint.getTextSize();
                if (k.type != CHAR && caption.length() > 1) textPaint.setTextSize(size * 0.72f);
                float ty = k.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2;
                c.drawText(caption, k.rect.centerX(), ty, textPaint);
                textPaint.setTextSize(size);
            }
        }
    }

    // ---- touch ----------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        int id = e.getPointerId(index);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Key k = keyAt(e.getX(index), e.getY(index));
                if (k != null) {
                    pressed.put(id, k);
                    press(k);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                release(id);
                if (action == MotionEvent.ACTION_UP) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed.clear();
                stopRepeat();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void release(int pointerId) {
        Key k = pressed.get(pointerId);
        pressed.remove(pointerId);
        if (k != null && k.repeat) stopRepeat();
        // A modifier held down while typing other keys turns off when let go.
        if (k != null && k.type == SHIFT && shiftUsedWhileHeld) { shift = false; shiftUsedWhileHeld = false; }
        if (k != null && k.type == CTRL && ctrlUsedWhileHeld) { ctrl = false; ctrlUsedWhileHeld = false; }
        invalidate();
    }

    private void stopRepeat() {
        if (repeater != null) handler.removeCallbacks(repeater);
        repeater = null;
    }

    /** Acts on a key press (keys fire on touch-down for speed). */
    void press(Key k) {
        if (!enabledKeys || listener == null) return;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        fire(k);
        if (k.repeat) {
            stopRepeat();
            repeater = new Runnable() {
                @Override
                public void run() {
                    fire(k);
                    handler.postDelayed(this, 50);
                }
            };
            handler.postDelayed(repeater, 400);
        }
    }

    private int meta() {
        int m = 0;
        if (ctrl) m |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (shift) m |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        return m;
    }

    private void fire(Key k) {
        switch (k.type) {
            case SHIFT:
                shift = !shift;
                break;
            case CAPS:
                caps = !caps;
                break;
            case CTRL:
                ctrl = !ctrl;
                break;
            case PASTE:
                listener.onPaste();
                break;
            case ENTER:
                listener.onEnter();
                clearOneShot();
                break;
            case SPECIAL:
                listener.onKey(k.keyCode, meta()); // Shift+arrows selects, Ctrl+arrows jumps words
                clearOneShot();
                break;
            case CHAR:
            default: {
                char c = k.label.charAt(0);
                if (ctrl && (Character.isLetterOrDigit(c))) {
                    int code = Character.isLetter(c) ? KeyEvent.KEYCODE_A + (c - 'a') : KeyEvent.KEYCODE_0 + (c - '0');
                    listener.onKey(code, meta()); // Ctrl+A, Ctrl+C, Ctrl+V, Ctrl+Z ...
                } else {
                    boolean useShift = k.isLetter() ? (shift ^ caps) : shift;
                    listener.onText(useShift ? k.shifted : k.label);
                }
                clearOneShot();
                break;
            }
        }
        invalidate();
    }

    /** Shift/Ctrl apply to one key, unless a finger is still holding them down (like a real keyboard). */
    private void clearOneShot() {
        if (held(SHIFT)) shiftUsedWhileHeld = true; else shift = false;
        if (held(CTRL)) ctrlUsedWhileHeld = true; else ctrl = false;
    }

    private boolean held(int type) {
        for (int i = 0; i < pressed.size(); i++) {
            if (pressed.valueAt(i).type == type) return true;
        }
        return false;
    }
}
