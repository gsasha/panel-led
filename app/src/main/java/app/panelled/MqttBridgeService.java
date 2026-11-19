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

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the panel's LED to Home Assistant over MQTT (JSON light schema, with discovery), and
 * applies incoming commands via {@link LedDevice}. This is the one place in the app that does any
 * networking -- see docs/mqtt-bridge.md for the topics and docs/api.md for why this lives here
 * (and not in a separate app) now that vendor signing is in our own hands.
 */
public class MqttBridgeService extends Service {
    private static final String TAG = "MqttBridge";
    private static final String CHANNEL_ID = "panelled_bridge";

    /** Temporal-dither sample rate for a fade's hardware output -- see {@link #fade} and
     * docs/mqtt-bridge.md#transitions. Sasha's own call, raised from 100 Hz (too coarse) to 400 Hz
     * to 1000 Hz (each step his, after watching the previous one); not independently verified
     * against this vendor ioctl driver's own tolerance for a sustained high call rate beyond the
     * live testing this app has had, so watch for stability issues under long-running use. */
    private static final int DITHER_HZ = 1000;

    /** Gamma for mapping a linear 0..1 brightness fraction onto the hardware's 0..15 raw level --
     * see {@link #gammaToLevel} and docs/mqtt-bridge.md#transitions. 2.2 is the commonly-cited value for
     * this exact correction (equal *raw* steps are not equal *perceived* steps -- human brightness
     * perception is closer to a power law than linear with physical output), not something
     * measured against this specific panel's diffuser/LEDs; worth re-tuning by eye if it's still
     * off in either direction. */
    private static final double GAMMA = 2.2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService animator = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
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
            // Home Assistant's MQTT JSON light schema (schema: json) puts the fade duration, in
            // seconds, in this same payload rather than sending a stream of updates itself -- the
            // device is expected to animate locally. See docs/mqtt-bridge.md#transitions.
            double transitionSeconds = cmd.optDouble("transition", 0);

            boolean fromOn = lastKnownOn;
            int fromColor = lastKnownColor;
            int fromBrightness = lastKnownBrightness;
            long myGen = generation.incrementAndGet();

            lastKnownOn = on;
            lastKnownColor = color;
            lastKnownBrightness = brightness;
            // Published once, now, for the target -- not once per fade step. HA's own frontend
            // already shows a transitioning light at its target immediately, same as any other
            // transition-capable light; the hardware catches up locally over transitionSeconds.
            publishState(cfg, on, color, brightness);

            if (transitionSeconds > 0 && !Double.isNaN(transitionSeconds)) {
                final int toColor = color;
                final int toBrightness = brightness;
                animator.execute(() -> fade(myGen, fromOn, fromColor, fromBrightness, on, toColor, toBrightness, transitionSeconds));
            } else {
                applyImmediate(on, color, brightness);
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad command payload: " + e);
        } catch (Exception e) {
            Log.w(TAG, "command handling failed: " + e);
        }
    }

    private void applyImmediate(boolean on, int color, int brightness) {
        int rc;
        if (!on) {
            rc = LedDevice.off().code();
        } else {
            int[] lv = levelsOf(color, brightness);
            rc = LedDevice.setRgb(lv[0], lv[1], lv[2]).code();
        }
        if (rc != 0) Log.w(TAG, "LED command returned " + rc + " (see docs/vendor-signing.md if this is -13)");
    }

    /**
     * Fades the hardware from (fromOn, fromColor, fromBrightness) to (toOn, toColor,
     * toBrightness) over {@code seconds}, running on {@link #animator} so it never blocks Paho's
     * callback thread (a multi-second block there would delay the very next command -- e.g. an
     * OFF arriving mid-fade -- from being handled promptly).
     *
     * There are only 16 hardware levels per channel ({@link LedDevice#MAX_LEVEL}), so sampling the
     * eased curve once per level crossed (the first cut of this feature) looked wrong: the same
     * ease-in/ease-out curve that looks smooth for a screen animation is nearly flat right where
     * it starts and ends, and quantizing that flat region to the nearest of only 16 integers
     * rounds to the *same* level for many consecutive samples -- so the light visibly sat at each
     * end and did almost all its visible motion in one short burst through the middle, not what
     * anyone would call a fade. Sampling at a fixed {@link #DITHER_HZ} and dithering between the
     * two hardware levels bracketing the true continuous target (an accumulator-based temporal
     * dither, the same idea as Floyd-Steinberg error diffusion but in time instead of space, not
     * randomized) fixes this: even while the eased curve is nearly flat, the *duty cycle* between
     * the two neighboring levels keeps shifting, so the time-averaged brightness a person actually
     * sees keeps changing smoothly instead of freezing on one quantized level.
     *
     * The interpolation itself happens in linear *perceptual* space (each channel's 0..1
     * brightness fraction, before gamma), not in raw hardware-level space -- gamma is applied
     * once, per sample, only when converting that instant's perceptual value to the continuous
     * level {@link #ditheredLevel} then dithers. Interpolating the already gamma-mapped endpoints
     * instead would retrace a different, wrong curve in between them.
     *
     * Both endpoints are the exact native 0-15 level (what {@link #applyImmediate} would also land
     * on for the same color/brightness) -- dithering only ever represents a value *between* two
     * native levels, while the fade is actually moving. At rest -- before the first sample and
     * after the last -- the hardware always sits at an exact native level, never a fractional one
     * held open by a stalled accumulator.
     *
     * Bails out as soon as a newer command supersedes {@code myGen} -- that command's own path has
     * already published and is already applying its own target.
     */
    private void fade(long myGen, boolean fromOn, int fromColor, int fromBrightness,
                       boolean toOn, int toColor, int toBrightness, double seconds) {
        double[] start = fromOn ? perceptualOf(fromColor, fromBrightness) : new double[] {0, 0, 0};
        double[] target = toOn ? perceptualOf(toColor, toBrightness) : new double[] {0, 0, 0};
        long tickNanos = 1_000_000_000L / DITHER_HZ;
        long startNanos = System.nanoTime();
        double[] acc = {0, 0, 0}; // per-channel dither error accumulators
        int[] last = {-1, -1, -1}; // guaranteed mismatch so the first sample is always applied

        while (true) {
            double t = Math.min(1.0, (System.nanoTime() - startNanos) / (seconds * 1_000_000_000L));
            if (generation.get() != myGen) return; // superseded by a newer command
            double eased = smoothstep(t);
            int[] cur = new int[3];
            for (int i = 0; i < 3; i++) {
                double p = start[i] + (target[i] - start[i]) * eased; // linear in perceptual space
                cur[i] = ditheredLevel(gammaToLevel(p), i, acc);
            }
            if (!Arrays.equals(cur, last)) {
                LedDevice.setRgb(cur[0], cur[1], cur[2]);
                last = cur;
            }
            if (t >= 1.0) break;
            try {
                Thread.sleep(tickNanos / 1_000_000, (int) (tickNanos % 1_000_000));
            } catch (InterruptedException e) {
                return; // service stopping
            }
        }
        if (generation.get() != myGen) return;
        if (!toOn) LedDevice.off(); // canonical off, not setRgb(0,0,0) -- matches applyImmediate
    }

    /** One channel's dithered sample: {@code v} is split into its integer floor and a fractional
     * remainder; the remainder accumulates across calls (into {@code acc[channel]}) and, once it
     * reaches 1, emits the next level up and carries the overflow back -- so over many samples the
     * fraction of time spent on the upper of the two levels equals the true fractional value, same
     * as 1D Floyd-Steinberg dithering. */
    private static int ditheredLevel(double v, int channel, double[] acc) {
        double base = Math.floor(v);
        acc[channel] += v - base;
        if (acc[channel] >= 1.0) {
            acc[channel] -= 1.0;
            base += 1;
        }
        return (int) Math.max(0, Math.min(LedDevice.MAX_LEVEL, base));
    }

    /** 0..255 channel, 0..255 brightness -> 0..15 hardware level, one per r/g/b channel. */
    private static int[] levelsOf(int color, int brightness) {
        return new int[] {
                scale((color >> 16) & 0xFF, brightness),
                scale((color >> 8) & 0xFF, brightness),
                scale(color & 0xFF, brightness),
        };
    }

    /** 0..255 channel, 0..255 brightness -> 0..15 hardware level. */
    private static int scale(int channel255, int brightness255) {
        return (int) Math.round(gammaToLevel(perceptual(channel255, brightness255)));
    }

    /** channel and brightness, each 0..255, combined into one linear 0..1 brightness fraction --
     * *before* gamma. This is the space fades interpolate in; {@link #gammaToLevel} converts a
     * point in it to the hardware's non-linear 0..15 scale only at the point of use. */
    private static double perceptual(int channel255, int brightness255) {
        return channel255 / 255.0 * brightness255 / 255.0;
    }

    private static double[] perceptualOf(int color, int brightness) {
        return new double[] {
                perceptual((color >> 16) & 0xFF, brightness),
                perceptual((color >> 8) & 0xFF, brightness),
                perceptual(color & 0xFF, brightness),
        };
    }

    /** Linear 0..1 brightness fraction -> continuous 0..15 hardware level, gamma-corrected (see
     * {@link #GAMMA}) so equal steps of {@code p} read as equal steps of perceived brightness,
     * not equal steps of raw physical output. */
    private static double gammaToLevel(double p) {
        return Math.pow(Math.max(0, Math.min(1, p)), GAMMA) * LedDevice.MAX_LEVEL;
    }

    /** Same quintic smoothstep as ESPHome's LightTransformer::smoothed_progress() -- an
     * ease-in/ease-out curve, not a mechanical linear ramp. x is clamped to [0, 1] first. */
    private static double smoothstep(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * x * (x * (x * 6 - 15) + 10);
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
            // Explicit even though true is HA's own default for this flag -- see
            // docs/mqtt-bridge.md#transitions for what it buys us and where the behavior is from.
            d.put("transition", true);
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
        animator.shutdownNow(); // interrupts Thread.sleep() in fade(), which returns at once
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
