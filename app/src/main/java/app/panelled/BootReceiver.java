package app.panelled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the bridge, if it was configured and enabled before, after the two events that kill
 * its running service without anything else noticing: a reboot, and an app update. An update's
 * MY_PACKAGE_REPLACED is only ever delivered to this app itself, so no exported/permission
 * concerns beyond BOOT_COMPLETED's own.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        MqttBridgeService.applyConfigChange(context);
    }
}
