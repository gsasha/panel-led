package app.panelled;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sets the bridge's config over adb, and only over adb: every call is refused unless the caller's
 * uid is the adb shell (2000) or root (0). Every field can equally be set from
 * {@link SettingsActivity}; this is the path for scripts, and for the broker password so it need
 * not be typed where the panel's screen shows it. See docs/mqtt-bridge.md for the commands.
 *
 * Why a provider and not a broadcast receiver: a receiver that adb can reach had to be exported
 * with no permission, which let every other installed app send to it too (no permission level
 * that admitted shell but not other apps was found). A provider sees its caller's uid
 * ({@link Binder#getCallingUid()}), so the same export can be checked in code. The password is
 * streamed in with `content write` (stdin), so it never sits in a file on the device or in any
 * command line.
 */
public class ConfigProvider extends ContentProvider {
    private static final String TAG = "ConfigProvider";
    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    private static final int MAX_PASSWORD_BYTES = 1024;

    @Override
    public boolean onCreate() {
        return true;
    }

    private void requireAdb() {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID && uid != Process.myUid()) {
            Log.w(TAG, "refused a call from uid " + uid);
            throw new SecurityException("only the adb shell may use this provider");
        }
    }

    private Context ctx() {
        return getContext();
    }

    /** content query --uri content://app.panelled.config/config : one row, never the password. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        requireAdb();
        BridgeConfig cfg = new BridgeConfig(ctx());
        MatrixCursor c = new MatrixCursor(
                new String[] {"host", "port", "user", "slug", "enabled", "password_set", "configured"});
        c.addRow(new Object[] {cfg.host(), cfg.port(), cfg.user(), cfg.slug(),
                cfg.enabled() ? 1 : 0, cfg.password() == null ? 0 : 1, cfg.isConfigured() ? 1 : 0});
        return c;
    }

    /** content update --uri content://app.panelled.config/config --bind host:s:... : sets only
     * the keys given (host, port, user, slug, enabled), then restarts or stops the bridge. */
    @Override
    public int update(Uri uri, ContentValues v, String selection, String[] selectionArgs) {
        requireAdb();
        if (v == null) return 0;
        BridgeConfig cfg = new BridgeConfig(ctx());
        if (v.containsKey("host")) cfg.setHost(v.getAsString("host"));
        if (v.containsKey("port")) cfg.setPort(v.getAsInteger("port"));
        if (v.containsKey("user")) cfg.setUser(v.getAsString("user"));
        if (v.containsKey("slug")) cfg.setSlug(v.getAsString("slug"));
        if (v.containsKey("enabled")) cfg.setEnabled(v.getAsBoolean("enabled"));
        Log.i(TAG, "config updated: " + cfg.describe());
        MqttBridgeService.applyConfigChange(ctx());
        return 1;
    }

    /** content write --uri content://app.panelled.config/password < file : stdin becomes the
     * broker password (trailing whitespace trimmed; empty input clears it). */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        requireAdb();
        if (!"/password".equals(uri.getPath()) || !mode.contains("w") || mode.contains("r")) {
            throw new SecurityException("only write-only access to /password is offered");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readEnd = pipe[0];
            final Context app = ctx();
            new Thread(() -> {
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readEnd)) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[256];
                    int n;
                    while ((n = in.read(chunk)) >= 0) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > MAX_PASSWORD_BYTES) {
                            Log.w(TAG, "password input over " + MAX_PASSWORD_BYTES + " bytes; ignored");
                            return;
                        }
                    }
                    String pw = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                    BridgeConfig cfg = new BridgeConfig(app);
                    cfg.setPassword(pw.isEmpty() ? null : pw);
                    Log.i(TAG, "password " + (pw.isEmpty() ? "cleared" : "set") + "; " + cfg.describe());
                    MqttBridgeService.applyConfigChange(app);
                } catch (Exception e) {
                    Log.w(TAG, "could not read the password: " + e);
                }
            }, "config-password").start();
            return pipe[1];
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        requireAdb();
        throw new UnsupportedOperationException("use update");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireAdb();
        throw new UnsupportedOperationException("use update");
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }
}
