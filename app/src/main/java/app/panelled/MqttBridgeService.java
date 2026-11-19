package app.panelled;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes the panel's LED to Home Assistant over MQTT (JSON light schema, with discovery), and
 * applies incoming commands via {@link LedDevice}. This is the one place in the app that does any
 * networking -- see docs/mqtt-bridge.md for the topics and docs/api.md for why this lives here
 * (and not in a separate app) now that vendor signing is in our own hands.
 */
public class MqttBridgeService extends Service {
    private static final String TAG = "MqttBridge";
    private static final String CHANNEL_ID = "panelled_bridge";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MqttClient client;

    /** Call after any config change (SettingsActivity, ConfigReceiver, boot): starts, restarts or
     * stops the bridge to match the current, now-current BridgeConfig. */
    static void applyConfigChange(Context ctx) {
        BridgeConfig cfg = new BridgeConfig(ctx);
        Intent intent = new Intent(ctx, MqttBridgeService.class);
        if (cfg.enabled() && cfg.isConfigured()) {
            ctx.startForegroundService(intent);
        } else {
            ctx.stopService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Panel bridge",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification("starting"));
        worker.execute(this::reconnect);
        return START_STICKY;
    }

    private Notification notification(String status) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Panel bridge")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, notification(status));
    }

    private void reconnect() {
        BridgeConfig cfg = new BridgeConfig(this);
        if (!cfg.enabled() || !cfg.isConfigured()) {
            disconnectQuietly();
            stopSelf();
            return;
        }
        disconnectQuietly();
        try {
            String uri = "tcp://" + cfg.host() + ":" + cfg.port();
            String clientId = "panelled-" + cfg.slug();
            client = new MqttClient(uri, clientId, new MemoryPersistence());

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            if (cfg.user() != null) opts.setUserName(cfg.user());
            if (cfg.password() != null) opts.setPassword(cfg.password().toCharArray());
            opts.setWill(availabilityTopic(cfg), "offline".getBytes(), 1, true);

            client.setCallback(new MqttCallbackExtended() {
                // Paho's own automaticReconnect re-establishes the TCP/MQTT session by itself,
                // but it does NOT resubscribe or republish discovery -- that is explicitly the
                // application's job, done here, which Paho calls after EVERY successful connect
                // (the first one too, not just reconnects; `reconnect` below is true only for the
                // automatic ones). Doing this work in reconnect() after client.connect() instead
                // would only ever run once, silently losing the subscription on any later
                // automatic reconnect -- found by testing against a real broker, not assumed.
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    try {
                        client.subscribe(commandTopic(cfg), 1);
                        publishRetained(availabilityTopic(cfg), "online");
                        publishRetained(discoveryTopic(cfg), discoveryPayload(cfg));
                        publishState(cfg, lastKnownOn, lastKnownColor, lastKnownBrightness);
                        updateNotification("connected to " + cfg.host());
                        Log.i(TAG, (reconnect ? "reconnected" : "connected") + ", subscribed " + commandTopic(cfg));
                    } catch (Exception e) {
                        Log.w(TAG, "post-connect setup failed: " + e);
                    }
                }

                @Override public void connectionLost(Throwable cause) {
                    Log.w(TAG, "connection lost: " + cause);
                    updateNotification("reconnecting");
                }

                @Override public void messageArrived(String topic, MqttMessage message) {
                    handleCommand(cfg, message);
                }

                @Override public void deliveryComplete(IMqttDeliveryToken token) { }
            });

            client.connect(opts);
        } catch (Exception e) {
            Log.w(TAG, "connect failed: " + e);
            updateNotification("connect failed: " + e.getMessage());
        }
    }

    // Reflects whatever the bridge last set, purely for the retained state message after a
    // reconnect; not a source of truth for anything else.
    private volatile boolean lastKnownOn = false;
    private volatile int lastKnownColor = 0xFFFFFF;
    private volatile int lastKnownBrightness = 255;

    private void handleCommand(BridgeConfig cfg, MqttMessage message) {
        try {
            JSONObject cmd = new JSONObject(new String(message.getPayload(), "UTF-8"));
            String state = cmd.optString("state", "ON").toUpperCase();
            boolean on = state.equals("ON");
            int brightness = cmd.optInt("brightness", lastKnownBrightness);
            int color = lastKnownColor;
            if (cmd.has("color")) {
                JSONObject c = cmd.getJSONObject("color");
                color = (c.optInt("r", 0) << 16) | (c.optInt("g", 0) << 8) | c.optInt("b", 0);
            }

            int rc;
            if (!on) {
                rc = LedDevice.off().code();
            } else {
                int r = scale((color >> 16) & 0xFF, brightness);
                int g = scale((color >> 8) & 0xFF, brightness);
                int b = scale(color & 0xFF, brightness);
                rc = LedDevice.setRgb(r, g, b).code();
            }
            if (rc != 0) Log.w(TAG, "LED command returned " + rc + " (see docs/vendor-signing.md if this is -13)");

            lastKnownOn = on;
            lastKnownColor = color;
            lastKnownBrightness = brightness;
            publishState(cfg, on, color, brightness);
        } catch (JSONException e) {
            Log.w(TAG, "bad command payload: " + e);
        } catch (Exception e) {
            Log.w(TAG, "command handling failed: " + e);
        }
    }

    /** 0..255 channel, 0..255 brightness -> 0..15 hardware level. */
    private static int scale(int channel255, int brightness255) {
        return Math.round(channel255 / 255f * brightness255 / 255f * LedDevice.MAX_LEVEL);
    }

    private void publishState(BridgeConfig cfg, boolean on, int color, int brightness) {
        try {
            JSONObject state = new JSONObject();
            state.put("state", on ? "ON" : "OFF");
            // Required alongside supported_color_modes in the discovery config (above this
            // channel's only mode is "rgb"): without it HA has nothing telling it which of a
            // light's supported modes a given state update is reporting in.
            state.put("color_mode", "rgb");
            state.put("brightness", brightness);
            JSONObject c = new JSONObject();
            c.put("r", (color >> 16) & 0xFF);
            c.put("g", (color >> 8) & 0xFF);
            c.put("b", color & 0xFF);
            state.put("color", c);
            publishRetained(stateTopic(cfg), state.toString());
        } catch (Exception e) {
            Log.w(TAG, "publishState failed: " + e);
        }
    }

    private void publishRetained(String topic, String payload) {
        try {
            MqttMessage m = new MqttMessage(payload.getBytes("UTF-8"));
            m.setQos(1);
            m.setRetained(true);
            client.publish(topic, m);
        } catch (Exception e) {
            Log.w(TAG, "publish to " + topic + " failed: " + e);
        }
    }

    private static String commandTopic(BridgeConfig cfg) { return "panelled/" + cfg.slug() + "/led/set"; }
    private static String stateTopic(BridgeConfig cfg) { return "panelled/" + cfg.slug() + "/led/state"; }
    private static String availabilityTopic(BridgeConfig cfg) { return "panelled/" + cfg.slug() + "/led/availability"; }
    private static String discoveryTopic(BridgeConfig cfg) { return "homeassistant/light/panelled_" + cfg.slug() + "/config"; }

    /** Home Assistant MQTT JSON light schema discovery payload -- see docs/mqtt-bridge.md. */
    private String discoveryPayload(BridgeConfig cfg) {
        try {
            JSONObject d = new JSONObject();
            d.put("name", "Side light");
            d.put("unique_id", "panelled_" + cfg.slug() + "_led");
            d.put("schema", "json");
            d.put("command_topic", commandTopic(cfg));
            d.put("state_topic", stateTopic(cfg));
            d.put("availability_topic", availabilityTopic(cfg));
            d.put("payload_available", "online");
            d.put("payload_not_available", "offline");
            // Found live, not assumed: the legacy "brightness"/"rgb" boolean flags (what the HA
            // docs show as the simplest JSON-schema example) are deprecated and only half-honored
            // by the HA version actually running here -- the entity came up with
            // supported_color_modes == ["brightness"], silently losing color entirely, no error
            // anywhere. "rgb" implies brightness in the current schema; do not add "brightness"
            // alongside it, which HA logs as invalid (two color modes where one is redundant).
            d.put("supported_color_modes", new org.json.JSONArray().put("rgb"));
            JSONObject device = new JSONObject();
            device.put("identifiers", new org.json.JSONArray().put("panelled_" + cfg.slug()));
            device.put("name", cfg.slug());
            device.put("manufacturer", "panel-led (unofficial)");
            d.put("device", device);
            return d.toString();
        } catch (Exception e) {
            Log.w(TAG, "discoveryPayload failed: " + e);
            return "{}";
        }
    }

    private void disconnectQuietly() {
        if (client != null) {
            try {
                if (client.isConnected()) client.disconnect();
                client.close();
            } catch (Exception ignored) { }
            client = null;
        }
    }

    @Override
    public void onDestroy() {
        disconnectQuietly();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
