package app.panelled;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The MQTT bridge's settings. Every field can be set from {@link SettingsActivity}'s on-screen
 * form, or over adb via {@link ConfigProvider} -- useful if you'd rather not type the broker
 * password where it could be seen on the panel's screen. See docs/mqtt-bridge.md.
 */
final class BridgeConfig {
    private static final String PREFS = "bridge_config";
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_USER = "user";
    private static final String K_PASS = "pass";
    private static final String K_SLUG = "slug";
    private static final String K_ENABLED = "enabled";
    private static final int DEFAULT_PORT = 1883;

    private final SharedPreferences prefs;

    BridgeConfig(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String host() { return prefs.getString(K_HOST, null); }
    int port() { return prefs.getInt(K_PORT, DEFAULT_PORT); }
    String user() { return prefs.getString(K_USER, null); }
    String password() { return prefs.getString(K_PASS, null); }
    /** The device's MQTT/entity slug, e.g. "living_room_panel" -- your own name for this panel. */
    String slug() { return prefs.getString(K_SLUG, null); }
    boolean enabled() { return prefs.getBoolean(K_ENABLED, false); }

    /** True once there is enough to actually connect (host + slug; user/pass may be absent). */
    boolean isConfigured() {
        return host() != null && !host().isEmpty() && slug() != null && !slug().isEmpty();
    }

    SharedPreferences.Editor edit() { return prefs.edit(); }

    void setHost(String v) { edit().putString(K_HOST, v).apply(); }
    void setPort(int v) { edit().putInt(K_PORT, v).apply(); }
    void setUser(String v) { edit().putString(K_USER, v).apply(); }
    void setPassword(String v) { edit().putString(K_PASS, v).apply(); }
    void setSlug(String v) { edit().putString(K_SLUG, v).apply(); }
    void setEnabled(boolean v) { edit().putBoolean(K_ENABLED, v).apply(); }

    /** A one-line, secret-free summary for the probe screen / diagnosis. */
    String describe() {
        return "host=" + host() + " port=" + port() + " user=" + user()
                + " pass=" + (password() == null ? "(unset)" : "(set)")
                + " slug=" + slug() + " enabled=" + enabled()
                + " configured=" + isConfigured();
    }
}
