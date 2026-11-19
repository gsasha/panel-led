# MQTT / Home Assistant bridge

`panel-led` publishes its own LED to Home Assistant over MQTT, with discovery, and applies
incoming commands via the same `LedDevice` the probe screen uses. This now lives inside
`panel-led` itself (not a separate app) -- see `docs/api.md` for why (now that vendor signing is
in our own hands, there is no longer a reason to keep the privileged part minimal for a vendor
review).

## Configuring it

Two equivalent ways, same storage (`BridgeConfig`): **on screen** (`MainActivity` -> "Bridge
settings") for everything except the password, which is deliberately never pre-filled and never
shown; or **over adb**, all fields, which is the only way to set the password without typing it
on the panel:

```
# host / port / username / slug / enabled -- any subset, each extra optional
adb shell am broadcast -n app.panelled/.ConfigReceiver -a app.panelled.action.SET_CONFIG \
    --es host 10.2.0.7 --ei port 1883 --es user panel_led --es slug domatic_tablet_gf_play --ez enabled true

# the password -- never on a command line (shell history, `ps`): push a file, point at it, then
# delete the file yourself (see the limitation below, this app cannot reliably do that part)
adb push mqtt_password.txt /data/local/tmp/panelled_mqtt_password
adb shell am broadcast -n app.panelled/.ConfigReceiver -a app.panelled.action.SET_CONFIG \
    --es load_password_from /data/local/tmp/panelled_mqtt_password
adb shell rm /data/local/tmp/panelled_mqtt_password
```

`slug` is the device's name in topics and in Home Assistant's discovery id -- use the estate's
naming convention (e.g. `domatic_tablet_gf_play`), not a throwaway value.

## Topics (Home Assistant MQTT JSON light schema)

- `panelled/<slug>/led/set` -- command, JSON `{"state":"ON"|"OFF","color":{"r":,"g":,"b":},"brightness":0-255}`
- `panelled/<slug>/led/state` -- retained, same shape, reflects what was actually applied
- `panelled/<slug>/led/availability` -- retained, `online`/`offline` (LWT on disconnect)
- `homeassistant/light/panelled_<slug>/config` -- retained discovery config, published on every connect

RGB + brightness are combined and scaled to the hardware's 0-15 levels per channel
(`channel/255 * brightness/255 * 15`, rounded).

## Two known limitations, tested 2026-11-19, not assumed

1. **The adb-only receiver is reachable by any installed app, not only by adb.** On this Android
   build, `adb shell am broadcast -n` only reaches a manifest receiver that is exported with no
   `android:permission` at all -- `exported="false"` refused shell exactly like it refuses every
   other app, and so did a `protectionLevel="normal"` permission and a `protectionLevel="signature"`
   one tied to this app's own key (all three tested directly, not inferred). There is no tested
   configuration that lets adb in while keeping other apps out. Acceptable today (nothing else is
   installed on these panels besides Fully and this app); a real gap before recommending this
   setup for anyone whose panel runs other apps.
2. **The password file usually cannot be deleted by this app.** `/data/local/tmp` (the natural
   `adb push` target) is sticky-bit protected: only the file's owner (`shell`, since `adb push`
   creates it) or root may unlink it there, not this app's uid even though it can read the file
   fine. The password is read and stored correctly; the file is not reliably removed afterward.
   **Always run `adb shell rm <file>` yourself right after the broadcast** -- it is a required
   step, not a cleanup nicety, and the app logs a warning (`ConfigReceiver`, logcat) when its own
   delete attempt fails so this is visible rather than silent.

## Production broker credential -- not done yet

The estate's mosquitto broker (`10.2.0.7:1883`) needs its own narrow user/ACL for this bridge
(matching the house convention of one credential per consumer, e.g. the Nuki lock's publish-only
user) -- a PR to `upravdom-docker`, Sasha merges. Until that exists, point the bridge at a test
broker to verify it, as this build was (a throwaway local container, torn down after).

## Verified end to end (2026-11-19, playroom panel)

A throwaway local mosquitto container (anonymous, not the estate's broker): connected, discovery
config published and valid, a `{"state":"ON","color":{"r":255,"g":0,"b":0}}` command actually
turned the physical LED red (confirmed via the return code the probe screen already proved means
success -- see `docs/vendor-signing.md`), the retained state topic reflected it, and `"state":"OFF"`
turned it back off. Found and fixed one real bug along the way: Paho's `automaticReconnect`
re-establishes the connection by itself but does not resubscribe or republish discovery -- that
has to happen in `MqttCallbackExtended.connectComplete()`, not just once after the initial
`connect()`, or every reconnect silently loses the subscription.
