package app.p2keyboard.board;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the board's background link after a reboot or an app update (board devices only). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a))
                && BoardLinkService.isBoard(context)) {
            BoardLinkService.start(context);
        }
    }
}
