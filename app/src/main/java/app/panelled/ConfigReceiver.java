package app.panelled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sets the bridge's config over adb, useful for the broker password specifically if you'd rather
 * not type it where it could be seen on the panel's screen. Every field here can equally be set
 * from {@link SettingsActivity} (same storage, neither path is "the real one").
 *
 * KNOWN LIMITATION: on the Android builds this was tested against, `adb shell am broadcast -n`
 * only reaches a manifest receiver that is exported with no android:permission at all --
 * exported="false" and every permission level tried (normal, signature tied to this app's own
 * key) refused shell exactly as they refuse any other app. So this receiver is reachable by any
 * installed app, not only by adb -- see the manifest's comment on it and docs/mqtt-bridge.md.
 */
public class ConfigReceiver extends BroadcastReceiver {
    static final String ACTION_SET_CONFIG = "app.panelled.action.SET_CONFIG";
    private static final String TAG = "ConfigReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SET_CONFIG.equals(intent.getAction())) return;
        BridgeConfig cfg = new BridgeConfig(context);

        if (intent.hasExtra("host")) cfg.setHost(intent.getStringExtra("host"));
        if (intent.hasExtra("port")) cfg.setPort(intent.getIntExtra("port", cfg.port()));
        if (intent.hasExtra("user")) cfg.setUser(intent.getStringExtra("user"));
        if (intent.hasExtra("slug")) cfg.setSlug(intent.getStringExtra("slug"));
        if (intent.hasExtra("enabled")) cfg.setEnabled(intent.getBooleanExtra("enabled", cfg.enabled()));

        // The password never travels as a plain extra (it would sit in the shell's command history
        // and briefly in `ps`). Instead: `adb push` a file somewhere shell can write (e.g.
        // /data/local/tmp/), then point this extra at it; read, store, and try to delete it.
        // KNOWN LIMITATION: this app's delete attempt below usually FAILS on
        // /data/local/tmp -- that directory is sticky-bit protected, and only the file's owner
        // (shell, since `adb push` creates it) or root may unlink a file there, not this app's uid
        // even though it can read the file fine. So the plaintext password is left sitting there
        // unless the operator removes it themselves. docs/mqtt-bridge.md says to run
        // `adb shell rm <file>` right after this broadcast, every time; that step is required, not
        // a nicety, and this code cannot do it for you.
        String pwFile = intent.getStringExtra("load_password_from");
        if (pwFile != null) {
            try {
                File f = new File(pwFile);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] chunk = new byte[256];
                    int n;
                    while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
                }
                String pw = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                cfg.setPassword(pw);
                if (f.delete()) {
                    Log.i(TAG, "password loaded from " + pwFile + " and the file deleted");
                } else {
                    Log.w(TAG, "password loaded from " + pwFile + " but could NOT delete it (likely "
                            + "the sticky bit on its directory, not owned by this app) -- "
                            + "run `adb shell rm " + pwFile + "` yourself now");
                }
            } catch (Exception e) {
                Log.w(TAG, "could not read password file " + pwFile + ": " + e);
            }
        }

        Log.i(TAG, "config updated: " + cfg.describe());
        MqttBridgeService.applyConfigChange(context);
    }
}
