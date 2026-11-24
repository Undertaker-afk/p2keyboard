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
import static org.junit.Assume.assumeTrue;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import app.p2keyboard.BuildConfig;
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
        assumeTrue("board APKs only", BuildConfig.IS_BOARD);
        app = ApplicationProvider.getApplicationContext();
        InputMethodManager imm = app.getSystemService(InputMethodManager.class);
        shadowOf(imm).setEnabledInputMethodInfoList(Arrays.asList(
                ime(app.getPackageName(), RemoteKeyboardService.class.getName()),
                // Google voice typing: an input method, but not a keyboard (listed first on purpose)
                ime("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.ime.VoiceInputMethodService"),
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
    public void voiceInputIsNeverTheNormalKeyboard() {
        String voice = "com.google.android.googlequicksearchbox/com.google.android.voicesearch.ime.VoiceInputMethodService";
        // tapping the mic makes voice input the active input method for a moment: not remembered
        KeyboardSwitch.rememberNormal(app, voice);
        assertEquals(NORMAL, ComponentName.unflattenFromString(KeyboardSwitch.normalId(app)).flattenToShortString());
    }

    @Test
    public void picksChosenThenSystemKeyboardThenAnyKeyboard() {
        KeyboardSwitch.Candidate voice = new KeyboardSwitch.Candidate("g/.Voice", "Voice", true, false);
        KeyboardSwitch.Candidate gboard = new KeyboardSwitch.Candidate("gb/.Latin", "Gboard", false, true);
        KeyboardSwitch.Candidate builtIn = new KeyboardSwitch.Candidate("aosp/.Latin", "Board keyboard", true, true);
        java.util.List<KeyboardSwitch.Candidate> all = Arrays.asList(voice, gboard, builtIn);
        assertEquals("aosp/.Latin", KeyboardSwitch.pick(all, null));          // preinstalled keyboard
        assertEquals("gb/.Latin", KeyboardSwitch.pick(all, "gb/.Latin"));     // the chosen / last used one
        assertEquals("aosp/.Latin", KeyboardSwitch.pick(all, "g/.Voice"));    // a saved voice IME is ignored
        assertEquals("gb/.Latin", KeyboardSwitch.pick(Arrays.asList(voice, gboard), null));
        assertEquals("g/.Voice", KeyboardSwitch.pick(Arrays.asList(voice), null)); // nothing else at all
    }

    @Test
    public void chosenKeyboardSticks() {
        KeyboardSwitch.chooseNormal(app, NORMAL);
        KeyboardSwitch.rememberNormal(app, "other.pkg/.Keyboard"); // later switches don't override a choice
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
