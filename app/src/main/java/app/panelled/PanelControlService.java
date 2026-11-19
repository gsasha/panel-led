package app.panelled;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Exposes the panel's hardware control over Binder/AIDL only (IPanelControl) -- this app runs no
 * network server of any kind. An external app does its own MQTT/HTTP/whatever on its own schedule
 * and calls in here; this app knows nothing about what, if anything, is calling it that way.
 *
 * Bind with the explicit action ACTION_BIND; the caller's manifest must hold
 * PERMISSION_CONTROL_LIGHT (declared by this app, protectionLevel "normal"). See docs/api.md for a
 * worked bind example.
 */
public class PanelControlService extends Service {
    public static final String ACTION_BIND = "app.panelled.action.BIND_CONTROL";
    public static final String PERMISSION_CONTROL_LIGHT = "app.panelled.permission.CONTROL_LIGHT";

    private final IPanelControl.Stub binder = new IPanelControl.Stub() {
        @Override
        public int getCapabilities() {
            return IPanelControl.CAP_LED;
        }

        @Override
        public int setLedColor(int color) {
            return LedDevice.setColor(color).code();
        }

        @Override
        public int setLedOff() {
            return LedDevice.off().code();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
