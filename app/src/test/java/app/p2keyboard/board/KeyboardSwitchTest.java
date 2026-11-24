package app.p2keyboard.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.ComponentName;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import app.p2keyboard.board.KeyboardSwitch.Action;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeyboardSwitchTest {
    private static final String NORMAL = "com.android.inputmethod.latin/.LatinIME";
    private Application app;

    private static InputMethodInfo ime(String pkg, String cls) {
        return new InputMethodInfo(pkg, cls, "", "");
    }

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        InputMethodManager imm = app.getSystemService(InputMethodManager.class);
        shadowOf(imm).setEnabledInputMethodInfoList(Arrays.asList(
                ime(app.getPackageName(), RemoteKeyboardService.class.getName()),
                ime("com.android.inputmethod.latin", "com.android.inputmethod.latin.LatinIME")));
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, NORMAL);
    }

    @Test
    public void phoneKeyboardOnlyWhileAPhoneIsConnected() {
        assertEquals(Action.TO_PHONE_KEYBOARD, KeyboardSwitch.decide(true, true, false));
        assertEquals(Action.NONE, KeyboardSwitch.decide(true, true, true));
        assertEquals(Action.TO_NORMAL_KEYBOARD, KeyboardSwitch.decide(true, false, true)); // phone left
        assertEquals(Action.NONE, KeyboardSwitch.decide(true, false, false));
        assertEquals(Action.TO_NORMAL_KEYBOARD, KeyboardSwitch.decide(false, true, true)); // turned off
        assertEquals(Action.NONE, KeyboardSwitch.decide(false, true, false));
    }

    @Test
    public void normalKeyboardIsTheOtherOne() {
        assertEquals(NORMAL, ComponentName.unflattenFromString(KeyboardSwitch.normalId(app)).flattenToShortString());
        // remembering Phone Keyboard as "normal" is refused
        KeyboardSwitch.rememberNormal(app, KeyboardSwitch.ourId(app));
        assertEquals(NORMAL, ComponentName.unflattenFromString(KeyboardSwitch.normalId(app)).flattenToShortString());
    }

    @Test
    public void switchesBackAndForthWithTheAdbPermission() {
        assertFalse(KeyboardSwitch.canWriteSettings(app));
        assertFalse(KeyboardSwitch.switchTo(app, KeyboardSwitch.ourId(app))); // no permission: user must pick

        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        assertTrue(KeyboardSwitch.switchTo(app, KeyboardSwitch.ourId(app)));
        assertTrue(KeyboardSwitch.ourIsActive(app));
        assertTrue(KeyboardSwitch.switchTo(app, KeyboardSwitch.normalId(app)));
        assertFalse(KeyboardSwitch.ourIsActive(app));
    }
}
