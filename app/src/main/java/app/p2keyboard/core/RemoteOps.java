package app.p2keyboard.core;

/**
 * Wire format for keyboard operations. The phone's keyboard (PixelBoard / Gboard) talks to a local
 * InputConnection on the phone; every call is mirrored here and replayed on the board's field.
 *
 * <pre>
 * phone -> board
 *   c&lt;newCursorPos&gt;:&lt;text&gt;     commitText
 *   m&lt;newCursorPos&gt;:&lt;text&gt;     setComposingText
 *   F                           finishComposingText
 *   d&lt;before&gt;:&lt;after&gt;          deleteSurroundingText
 *   D&lt;before&gt;:&lt;after&gt;          deleteSurroundingTextInCodePoints
 *   k&lt;action&gt;:&lt;keyCode&gt;:&lt;meta&gt; sendKeyEvent
 *   a&lt;actionId&gt;                 performEditorAction
 *   M&lt;id&gt;                       performContextMenuAction (select all, cut, copy, paste)
 *   E                           Enter: the board's field action (Search, Go, Send) or a new line
 *   s&lt;dStart&gt;:&lt;dEnd&gt;           setSelection, relative to the cursor
 *   r&lt;dStart&gt;:&lt;dEnd&gt;           setComposingRegion, relative to the cursor
 * board -> phone
 *   e&lt;inputType&gt;:&lt;imeOptions&gt;:&lt;actionId&gt;:&lt;actionLabel&gt;   a field is focused
 *   n                                                    no field focused
 *   t&lt;text before the cursor&gt;                           what the field holds, for the phone's preview
 * </pre>
 * Selections are sent relative to the cursor because the phone only mirrors the text near it.
 */
public final class RemoteOps {
    public static final char COMMIT = 'c', COMPOSE = 'm', FINISH = 'F', DELETE = 'd', DELETE_CP = 'D',
            KEY = 'k', ACTION = 'a', MENU = 'M', SELECT = 's', REGION = 'r', ENTER = 'E';
    public static final char EDITOR = 'e', SEED = 't';
    public static final String NO_FIELD = "n";
    /** How much text before the board's cursor is sent to the phone's preview. */
    public static final int SEED_CHARS = 300;

    private RemoteOps() {}

    public static final class Op {
        public final char code;
        public final int a, b, c;
        public final String text;

        Op(char code, int a, int b, int c, String text) {
            this.code = code;
            this.a = a;
            this.b = b;
            this.c = c;
            this.text = text;
        }
    }

    public static String commit(CharSequence text, int pos) { return "c" + pos + ":" + text; }
    public static String compose(CharSequence text, int pos) { return "m" + pos + ":" + text; }
    public static String finish() { return "F"; }
    public static String enter() { return "E"; }
    public static String delete(int before, int after) { return "d" + before + ":" + after; }
    public static String deleteCodePoints(int before, int after) { return "D" + before + ":" + after; }
    public static String key(int action, int keyCode, int meta) { return "k" + action + ":" + keyCode + ":" + meta; }
    public static String action(int id) { return "a" + id; }
    public static String menu(int id) { return "M" + id; }
    public static String select(int dStart, int dEnd) { return "s" + dStart + ":" + dEnd; }
    public static String region(int dStart, int dEnd) { return "r" + dStart + ":" + dEnd; }

    public static String editorInfo(int inputType, int imeOptions, int actionId, String label) {
        return "e" + inputType + ":" + imeOptions + ":" + actionId + ":" + label;
    }

    public static String seed(String textBeforeCursor) { return "t" + textBeforeCursor; }

    /** Parses a phone -> board op; returns null if malformed. */
    public static Op parse(String s) {
        if (s == null || s.isEmpty()) return null;
        char code = s.charAt(0);
        String rest = s.substring(1);
        try {
            switch (code) {
                case COMMIT:
                case COMPOSE: {
                    int colon = rest.indexOf(':');
                    if (colon < 0) return null;
                    return new Op(code, Integer.parseInt(rest.substring(0, colon)), 0, 0, rest.substring(colon + 1));
                }
                case FINISH:
                case ENTER:
                    return new Op(code, 0, 0, 0, "");
                case DELETE:
                case DELETE_CP: {
                    int[] v = ints(rest, 2);
                    if (v[0] < 0 || v[1] < 0 || v[0] > 100_000 || v[1] > 100_000) return null;
                    return new Op(code, v[0], v[1], 0, "");
                }
                case SELECT:
                case REGION: {
                    int[] v = ints(rest, 2);
                    return new Op(code, v[0], v[1], 0, "");
                }
                case KEY: {
                    int[] v = ints(rest, 3);
                    return new Op(code, v[0], v[1], v[2], "");
                }
                case ACTION:
                case MENU:
                    return new Op(code, Integer.parseInt(rest), 0, 0, "");
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] ints(String s, int n) {
        String[] parts = s.split(":", -1);
        if (parts.length != n) throw new IllegalArgumentException();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }
}
