package dev.guxi.sonyhavoice;

import android.content.*;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        if (new ConfigStore(context).autostart()) {
            Log.i("SonyHAVoice", "boot_connection_start");
            ConnectionService.start(context);
        }
    }
}
