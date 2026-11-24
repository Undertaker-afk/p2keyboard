package app.p2keyboard.phone;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import app.p2keyboard.core.RemoteOps;

/**
 * An invisible text editor that the phone's keyboard (PixelBoard) types into. It keeps a local
 * mirror of the board's text around the cursor so the keyboard has context for suggestions and
 * autocorrect, and forwards every InputConnection call to the board as a {@link RemoteOps} op.
 */
public class RemoteInputView extends View {

    public interface Sink {
        void send(String op);

        void onMirrorChanged();
    }

    private static final int DEFAULT_TYPE = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE;

    private final SpannableStringBuilder mirror = new SpannableStringBuilder();
    private final InputMethodManager imm;
    private Sink sink;
    private int inputType = DEFAULT_TYPE;
    private int imeOptions = EditorInfo.IME_ACTION_NONE;
    private int actionId;
    private CharSequence actionLabel;

    public RemoteInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        Selection.setSelection(mirror, 0);
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public CharSequence mirror() {
        return mirror;
    }

    public int cursor() {
        return Math.max(0, Selection.getSelectionEnd(mirror));
    }

    public void showKeyboard() {
        requestFocus();
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    /** The board focused a field of this type: make the phone keyboard match (email, number, Search key, ...). */
    public void applyEditorInfo(int inputType, int imeOptions, int actionId, String actionLabel) {
        this.inputType = inputType == InputType.TYPE_NULL ? DEFAULT_TYPE : inputType;
        this.imeOptions = imeOptions;
        this.actionId = actionId;
        this.actionLabel = actionLabel == null || actionLabel.isEmpty() ? null : actionLabel;
        imm.restartInput(this);
    }

    /** Replaces the mirror with the board's text before its cursor. */
    public void seed(String textBeforeCursor) {
        mirror.clear();
        mirror.clearSpans();
        mirror.append(textBeforeCursor);
        Selection.setSelection(mirror, mirror.length());
        imm.restartInput(this);
        if (sink != null) sink.onMirrorChanged();
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            showKeyboard();
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = inputType;
        out.imeOptions = imeOptions | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        out.actionId = actionId;
        out.actionLabel = actionLabel;
        out.initialSelStart = Selection.getSelectionStart(mirror);
        out.initialSelEnd = Selection.getSelectionEnd(mirror);
        out.initialCapsMode = 0;
        if (Build.VERSION.SDK_INT >= 30) out.setInitialSurroundingText(mirror);
        return new Connection();
    }

    private void send(String op) {
        if (sink != null) sink.send(op);
    }

    private void reportChange() {
        int s = Selection.getSelectionStart(mirror), e = Selection.getSelectionEnd(mirror);
        int cs = BaseInputConnection.getComposingSpanStart(mirror), ce = BaseInputConnection.getComposingSpanEnd(mirror);
        imm.updateSelection(this, s, e, cs, ce);
        if (sink != null) sink.onMirrorChanged();
    }

    private final class Connection extends BaseInputConnection {
        private int batch;

        Connection() {
            super(RemoteInputView.this, true);
        }

        @Override
        public Editable getEditable() {
            return mirror;
        }

        @Override
        public boolean beginBatchEdit() {
            batch++;
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            if (batch > 0) batch--;
            if (batch == 0) reportChange();
            return batch > 0;
        }

        private void changed() {
            if (batch == 0) reportChange();
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            super.commitText(text, newCursorPosition);
            send(RemoteOps.commit(text, newCursorPosition));
            changed();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            super.setComposingText(text, newCursorPosition);
            send(RemoteOps.compose(text, newCursorPosition));
            changed();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            super.finishComposingText();
            send(RemoteOps.finish());
            changed();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            super.deleteSurroundingText(before, after);
            send(RemoteOps.delete(before, after));
            changed();
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            super.deleteSurroundingTextInCodePoints(before, after);
            send(RemoteOps.deleteCodePoints(before, after));
            changed();
            return true;
        }

        @Override
        public boolean setSelection(int start, int end) {
            int old = cursor();
            super.setSelection(start, end);
            send(RemoteOps.select(start - old, end - old));
            changed();
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            int old = cursor();
            super.setComposingRegion(start, end);
            send(RemoteOps.region(start - old, end - old));
            changed();
            return true;
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            send(RemoteOps.action(editorAction));
            return true;
        }

        @Override
        public boolean performContextMenuAction(int id) {
            send(RemoteOps.menu(id));
            return true;
        }

        @Override
        public boolean commitCompletion(CompletionInfo text) {
            return commitText(text.getText(), 1);
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) {
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            send(RemoteOps.key(event.getAction(), event.getKeyCode(), event.getMetaState()));
            if (event.getAction() == KeyEvent.ACTION_DOWN) applyKeyLocally(event);
            changed();
            return true;
        }
    }

    /** Keeps the mirror in step with key events the keyboard sends (e.g. Backspace on empty text). */
    private void applyKeyLocally(KeyEvent ev) {
        int a = Selection.getSelectionStart(mirror), b = Selection.getSelectionEnd(mirror);
        int s = Math.max(0, Math.min(a, b)), e = Math.max(0, Math.max(a, b));
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_DEL:
                if (s != e) mirror.delete(s, e);
                else if (s > 0) mirror.delete(Character.isLowSurrogate(mirror.charAt(s - 1)) && s > 1 ? s - 2 : s - 1, s);
                break;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (s != e) mirror.delete(s, e);
                else if (e < mirror.length()) mirror.delete(e, e + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                Selection.setSelection(mirror, Math.max(0, s - 1));
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                Selection.setSelection(mirror, Math.min(mirror.length(), e + 1));
                break;
            case KeyEvent.KEYCODE_ENTER:
                if ((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) mirror.replace(s, e, "\n");
                break;
            default:
                int u = ev.getUnicodeChar(ev.getMetaState());
                if (u > 0 && !Character.isISOControl(u)) mirror.replace(s, e, new String(Character.toChars(u)));
                break;
        }
    }
}
