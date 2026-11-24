package app.p2keyboard.board;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets Phone Keyboard switch the board's keyboard by itself (Android 11+): to Phone Keyboard when
 * a phone connects, back to the normal keyboard when it goes away. It reads no screen content and
 * does nothing else; switching keyboards is the only thing it is used for.
 */
public class SwitcherAccessibilityService extends AccessibilityService {
    private static volatile SwitcherAccessibilityService instance;

    static boolean isRunning() {
        return instance != null;
    }

    @TargetApi(30)
    static boolean switchTo(String imeId) {
        SwitcherAccessibilityService s = instance;
        if (s == null) return false;
        try {
            return s.getSoftKeyboardController().switchToInputMethod(imeId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // A phone may already be connected: apply the right keyboard now.
        BoardLinkService link = BoardLinkService.get();
        if (link != null) link.applyKeyboard();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: this service only switches keyboards.
    }

    @Override
    public void onInterrupt() {
    }
}
