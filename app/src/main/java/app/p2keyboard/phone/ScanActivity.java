package app.p2keyboard.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;

import app.p2keyboard.R;
import app.p2keyboard.core.Pairing;

/**
 * The "Pair device" scan screen: camera preview that looks for a board's pairing QR code and
 * returns it. The caller asks for the camera permission before opening this.
 */
public class ScanActivity extends Activity {
    public static final String EXTRA_PAIRING = "pairing";

    private DecoratedBarcodeView scanner;
    private TextView hint;
    private boolean torch;
    private String lastRejected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.camera_needed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_scan);
        scanner = findViewById(R.id.scanner);
        hint = findViewById(R.id.scan_hint);
        scanner.getBarcodeView().setDecoderFactory(
                new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        scanner.setStatusText("");
        scanner.decodeContinuous(this::onScanned);
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        Button light = findViewById(R.id.btn_torch);
        light.setOnClickListener(v -> {
            torch = !torch;
            if (torch) scanner.setTorchOn();
            else scanner.setTorchOff();
            light.setText(torch ? R.string.torch_off : R.string.torch_on);
        });
    }

    private void onScanned(BarcodeResult result) {
        String text = result.getText();
        Pairing p = Pairing.parse(text);
        if (p == null) {
            if (!text.equals(lastRejected)) { // don't repeat the message for the same code every frame
                lastRejected = text;
                hint.setText(R.string.not_a_board_code);
            }
            return;
        }
        scanner.pause();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PAIRING, p.toUri()));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scanner != null) scanner.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanner != null) scanner.pause();
    }
}
