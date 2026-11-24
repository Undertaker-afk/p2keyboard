package app.p2keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;

import java.io.FileOutputStream;

import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;
import app.p2keyboard.phone.PairedBoards;
import app.p2keyboard.phone.PhoneActivity;
import app.p2keyboard.phone.ScanActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h1300dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeScreenTest {

    private static Pairing board(String name, String secret) {
        return new Pairing(NaclBox.publicKey(NaclBox.newPrivateKey()), "derp4f.tailscale.com", secret, name);
    }

    @Test
    public void pairDeviceAsksForCameraThenOpensScanner() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowActivity sa = shadowOf(a);
        a.findViewById(R.id.btn_pair).performClick();
        assertTrue(java.util.Arrays.asList(sa.getLastRequestedPermission().requestedPermissions)
                .contains(Manifest.permission.CAMERA));
        Intent next = sa.getNextStartedActivity(); // only the system's permission dialog so far
        assertTrue(next == null || !ScanActivity.class.getName().equals(
                next.getComponent() == null ? null : next.getComponent().getClassName()));

        shadowOf((Application) ApplicationProvider.getApplicationContext()).grantPermissions(Manifest.permission.CAMERA);
        a.onRequestPermissionsResult(1, new String[]{Manifest.permission.CAMERA}, new int[]{0});
        Intent scan = sa.getNextStartedActivity();
        assertNotNull(scan);
        assertEquals(ScanActivity.class.getName(), scan.getComponent().getClassName());
    }

    @Test
    public void scannedBoardIsSavedAndOpened() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Pairing p = board("Room 12 Newline", "abcdefghijklmnop0123");
        Intent result = new Intent().putExtra(ScanActivity.EXTRA_PAIRING, p.toUri());
        a.onActivityResult(3, android.app.Activity.RESULT_OK, result); // what ScanActivity returns

        Pairing saved = new PairedBoards(a).find(NaclBox.hex(p.boardKey));
        assertNotNull(saved);
        assertEquals("Room 12 Newline", saved.name);
        Intent open = shadowOf(a).getNextStartedActivity();
        assertEquals(PhoneActivity.class.getName(), open.getComponent().getClassName());
        assertEquals(NaclBox.hex(p.boardKey), open.getStringExtra(PhoneActivity.EXTRA_BOARD));
    }

    @Test
    public void stockCameraLinkPairsToo() {
        Pairing p = board("Lab board", "abcdefghijklmnop0123");
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(p.toUri()));
        MainActivity a = Robolectric.buildActivity(MainActivity.class, link).setup().get();
        assertNotNull(new PairedBoards(a).find(NaclBox.hex(p.boardKey)));
        assertEquals(PhoneActivity.class.getName(), shadowOf(a).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void listsPairedDevicesAndRenders() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        Identity.setName(app, "Alex's Pixel 9");
        PairedBoards boards = new PairedBoards(app);
        Pairing room12 = board("Room 12 Newline", "");
        boards.put(board("Library board", ""));
        boards.put(board("Lab 3 (new)", "abcdefghijklmnop0123"));
        boards.put(room12);

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().visible().get();
        LinearLayout list = a.findViewById(R.id.board_list);
        assertEquals(3, list.getChildCount());

        list.getChildAt(0).performClick(); // most recently added first
        Intent open = shadowOf(a).getNextStartedActivity();
        assertEquals(NaclBox.hex(room12.boardKey), open.getStringExtra(PhoneActivity.EXTRA_BOARD));

        String out = System.getProperty("homePng");
        if (out != null) {
            View root = a.getWindow().getDecorView();
            Bitmap bmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bmp));
            try (FileOutputStream f = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
        }
    }
}
