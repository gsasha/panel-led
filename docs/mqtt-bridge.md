# MQTT / Home Assistant bridge

`panel-led` publishes its own LED to Home Assistant over MQTT, with discovery, and applies
incoming commands via the same `LedDevice` the probe screen uses. It runs no other network code.

## Before you start: your MQTT broker needs a user for this

This app authenticates to your broker like any other MQTT client. If your broker (e.g. Mosquitto)
already requires a username and password, create one for this app -- most brokers support a
password file plus an ACL, so you can scope the credential narrowly:

- **Read/write** on its own topic namespace, `panelled/<slug>/#` (it both subscribes to commands
  and publishes state/availability/discovery there).
- **Write** on `homeassistant/light/#` (or wherever your discovery prefix is), for the discovery
  config.

Consult your broker's own documentation for exactly how to add a user and an ACL rule -- for
Mosquitto, that's `mosquitto_passwd` and an `acl_file`. If your broker has no authentication set up
at all, that's a separate decision for you to make; this app works with or without a
username/password, but an MQTT broker reachable from your network with no authentication is its
own risk, unrelated to this app.

**Home Assistant's own MQTT integration also needs read/write on `panelled/#`** (to receive state
and send commands), in addition to whatever access it already has to your discovery prefix. If
your broker's ACL is scoped per user (recommended), give the user Home Assistant authenticates as
a grant on `panelled/#` too, or the entity will appear in Home Assistant, created from the
discovery message, but stay permanently `unavailable` with no error anywhere -- its subscribe to
the actual state topics will be silently denied. This is the single most common way to get this
bridge "half working": check it first if an entity shows up but never changes state.

## Configuring the app

Two equivalent ways, same storage (`BridgeConfig`): **on screen** (`MainActivity` -> "Bridge
settings") for everything except the password, which is deliberately never pre-filled and never
shown; or **over adb**, for every field, which is the only way to set the password without typing
it on the panel's screen.

### On screen

Open the app, tap "Bridge settings", fill in the broker host, port, username, password, and a
slug (your own name for this panel -- anything short and unique, e.g. `living_room_panel`), then
tap Save.

### Over adb

```
# host / port / username / slug / enabled -- any subset, each extra optional
adb shell am broadcast -n app.panelled/.ConfigReceiver -a app.panelled.action.SET_CONFIG \
    --es host YOUR_BROKER_HOST --ei port 1883 --es user YOUR_MQTT_USERNAME \
    --es slug living_room_panel --ez enabled true

# the password -- never on a command line (shell history, `ps`): push a file, point at it, then
# delete the file yourself (see the limitation below, this app cannot reliably do that part)
adb push mqtt_password.txt /data/local/tmp/panelled_mqtt_password
adb shell am broadcast -n app.panelled/.ConfigReceiver -a app.panelled.action.SET_CONFIG \
    --es load_password_from /data/local/tmp/panelled_mqtt_password
adb shell rm /data/local/tmp/panelled_mqtt_password
```

`slug` becomes part of every MQTT topic and Home Assistant's discovery id for this panel -- use
something that uniquely identifies it if you have more than one panel running this app, since two
panels sharing a slug would collide on the same topics.

Once configured (and the broker reachable), the app connects, publishes discovery, and the light
entity appears in Home Assistant on its own -- nothing to do on the Home Assistant side beyond
having its MQTT integration configured and discovery enabled (the default).

## Topics (Home Assistant MQTT JSON light schema)

- `panelled/<slug>/led/set` -- command, JSON `{"state":"ON"|"OFF","color":{"r":,"g":,"b":},"brightness":0-255,"transition":seconds}` (`transition` optional, see below)
- `panelled/<slug>/led/state` -- retained, same shape, reflects what was actually applied
- `panelled/<slug>/led/availability` -- retained, `online`/`offline` (LWT on disconnect)
- `homeassistant/light/panelled_<slug>/config` -- retained discovery config, published on every connect

RGB and brightness are combined and scaled to the hardware's 0-15 levels per channel
(`channel/255 * brightness/255 * 15`, rounded).

## Transitions

A command may carry an optional `"transition": <seconds>` field: `{"state":"ON","color":{...},
"brightness":...,"transition":2}`. This is the [Home Assistant MQTT Light `json`
schema](https://www.home-assistant.io/integrations/light.mqtt/)'s own convention for
`light.turn_on(transition=...)` -- Home Assistant does not fade anything itself over the network;
it sends this one payload with the target state and the requested duration, and the device is
expected to animate locally. The `default` MQTT light schema has no concept of transitions at all;
only `json` and `template` do (confirmed from that page's schema-comparison table). The discovery
config declares `"transition": true` so Home Assistant knows to include the field (that is also
its default, but it's declared explicitly here rather than relied on silently).

The app fades from whatever it is currently showing to the target over that many seconds, running
on its own background thread so a multi-second fade never blocks the next incoming MQTT command
(an `OFF` arriving mid-fade cancels it and takes over immediately, rather than queuing behind it).
It steps once per **hardware** level crossed on whichever RGB channel changes the most (there are
only 16 levels per channel -- `LedDevice.MAX_LEVEL`), not on a fixed small interval: stepping any
finer would just repeat the same `ioctl` call with no visible difference, for no reason. Each step
is eased through the same quintic smoothstep
([`LightTransformer::smoothed_progress()`](https://github.com/esphome/esphome/blob/dev/esphome/components/light/light_transformer.h),
`6x⁵ - 15x⁴ + 10x³`) that ESPHome's own MQTT JSON-schema light implementation uses for
exactly this field, rather than a straight linear ramp -- picked for parity with how every other
transition-capable light in the house actually feels, not reinvented from scratch. Only the final
target state is published to the state topic, once, immediately; there is no stream of
intermediate state messages over MQTT in either direction, which was the point of doing this
app-side at all.

## Two known limitations

1. **The adb-only receiver is reachable by any installed app, not only by adb.** On the Android
   builds this was tested against, `adb shell am broadcast -n` only reaches a manifest receiver
   that is exported with no `android:permission` at all -- `exported="false"` refused shell
   exactly like it refuses every other app, and so did a `protectionLevel="normal"` permission and
   a `protectionLevel="signature"` one tied to this app's own key (all three tested directly, not
   inferred). There is no tested configuration that lets adb in while keeping other apps out. This
   is fine on a dedicated kiosk panel that runs nothing else; a real gap if the device runs other
   apps too, since any of them could send this broadcast.
2. **The pushed password file usually cannot be deleted by this app.** `/data/local/tmp` (the
   natural `adb push` target) is sticky-bit protected: only the file's owner (`shell`, since
   `adb push` creates it) or root may unlink it there, not this app's uid, even though it can read
   the file fine. The password is read and stored correctly; the file is not reliably removed
   afterward. **Always run `adb shell rm <file>` yourself right after the broadcast** -- it's a
   required step, not a cleanup nicety, and the app logs a warning in `logcat` (tag
   `ConfigReceiver`) when its own delete attempt fails, so this is visible rather than silent.

## What "verified end to end" means here

This was tested against a real broker and a real device: connect, discovery publish, a color
command that actually turned the physical LED red, the retained state topic reflecting it, and an
off command turning it back off. One real bug was found and fixed along the way: Paho's
`automaticReconnect` re-establishes the connection by itself but does **not** resubscribe or
republish discovery -- that has to happen in `MqttCallbackExtended.connectComplete()`, called on
every successful connect including automatic reconnects, not just once after the initial
`connect()`. Without that fix, the app silently loses its subscription on the first reconnect.
