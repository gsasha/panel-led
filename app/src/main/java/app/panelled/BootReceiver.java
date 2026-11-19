package app.panelled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the bridge after a reboot, if it was configured and enabled before. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        MqttBridgeService.applyConfigChange(context);
    }
}
