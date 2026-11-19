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
# host / port / username / slug / enabled -- any subset, set together or one at a time
adb shell content update --uri content://app.panelled.config/config \
    --bind host:s:YOUR_BROKER_HOST --bind port:i:1883 --bind user:s:YOUR_MQTT_USERNAME \
    --bind slug:s:living_room_panel --bind enabled:b:true

# the password -- streamed from a file on YOUR machine over adb's stdin: it never appears on a
# command line (shell history, `ps`) and is never written to a file on the device
adb shell content write --uri content://app.panelled.config/password < mqtt_password.txt

# read the stored config back (the password is never returned, only whether one is set)
adb shell content query --uri content://app.panelled.config/config
```

Trailing whitespace in the password file is trimmed, so a plain `echo`-style file with a final
newline works. An empty file clears the password. Over adb this provider is the only way in: it
refuses every caller whose uid is not the adb shell (or root), so no other app installed on the
panel can read or change the config (verified on a real panel: an ordinary app's query, update and
password write were all refused with a `SecurityException`, and the config stayed as it was).

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
Progress through the fade's duration is eased through the same quintic smoothstep
([`LightTransformer::smoothed_progress()`](https://github.com/esphome/esphome/blob/dev/esphome/components/light/light_transformer.h),
`6x⁵ - 15x⁴ + 10x³`) that ESPHome's own MQTT JSON-schema light implementation uses for exactly
this field, rather than a straight linear ramp -- picked for parity with how every other
transition-capable light in the house actually feels, not reinvented from scratch. Only the final
target state is published to the state topic, once, immediately; there is no stream of
intermediate state messages over MQTT in either direction, which was the point of doing this
app-side at all.

### Why it's dithered, and gamma-corrected

There are only **16 hardware levels per channel** (`LedDevice.MAX_LEVEL`). The first cut of this
feature sampled the eased curve once per level crossed and just set that level -- which looked
wrong: a smoothstep curve is nearly flat right where it starts and ends, and quantizing that flat
region to the nearest of only 16 integers rounds to the *same* level for many consecutive samples.
The light visibly sat at each end and did almost all its visible motion in one short burst through
the middle.

The fix is temporal dithering, sampled at a fixed, fast rate: at each tick, the true continuous
target (not yet rounded to a hardware level) is split into its integer floor and a fractional
remainder; the remainder accumulates across ticks and, once it reaches 1, the next level up is
emitted and the overflow carried back. Over many samples the fraction of time spent on the upper
of the two levels equals the true fractional value -- the same idea as 1D Floyd-Steinberg error
diffusion, just in time instead of space. Even while the eased curve is nearly flat, the resulting
duty cycle between the two neighboring levels keeps shifting, so the time-averaged brightness a
person actually sees keeps changing smoothly instead of freezing on one quantized level. Both
fade endpoints are still exact native levels -- dithering only ever represents a value *between*
two of them while the fade is moving; at rest the hardware always sits at an exact level, never a
fractional one held open by a stalled accumulator.

The dither rate is 1000 Hz, raised from an initial 100 Hz (too coarse) to 400 Hz to 1000 Hz after
watching it on a real panel each time. **This has not been independently verified against this
vendor's ioctl driver's own tolerance for a sustained high call rate** -- `LedDevice`'s one open
+ 3 ioctls + close sequence runs on every single sample, so a multi-second fade means thousands of
these per second, continuously, for as long as the light is transitioning. It held up fine across
repeated test fades and breathing-effect runs, but a long unattended soak test hasn't been done;
watch for stability issues under sustained real-world use, and dial `DITHER_HZ` back down if so.

Separately, the mapping from a linear 0..1 brightness fraction to the 0..15 hardware level is
**gamma-corrected** (power of 2.2, the commonly-cited value for this exact problem): equal steps
of raw hardware level are not equal steps of *perceived* brightness -- human brightness perception
is closer to a power law than linear with physical output, so several levels near the top of the
range all look close to "fully on" to the eye, and a linear fade visibly spends most of its time
looking stuck at both ends. Fades interpolate in the pre-gamma, linear-perceptual space and apply
gamma only once per sample, at the point of converting to a hardware level for dithering -- not by
gamma-mapping the two endpoints and then interpolating between *those*, which would retrace a
different, wrong curve in between them. The exact value (2.2) is the standard starting point for
this class of correction, not something measured against this panel's specific diffuser and LEDs;
it's worth re-tuning by eye if it still looks off in either direction.

## Why the adb config interface is a content provider

Versions up to 0.5.0-alpha took config through a broadcast receiver, which had two real problems,
both gone in 0.6.0-alpha:

1. **Any installed app could use it.** On the Android builds tested, `adb shell am broadcast -n`
   only reaches a manifest receiver that is exported with no `android:permission` at all:
   `exported="false"` refused shell exactly like it refuses every other app, and so did a
   `protectionLevel="normal"` and a `protectionLevel="signature"` permission (all three tested
   directly). So no receiver setup admitted adb while keeping other apps out. A content provider
   can check its caller's uid in code (`Binder.getCallingUid()`), so it is exported but answers
   only the adb shell (2000) or root (0). `content` run from `adb shell` really does arrive as
   uid 2000, and an ordinary app arrives as its own uid and is refused, both seen on a real panel.
2. **The password file could not be removed.** The receiver read a file pushed to
   `/data/local/tmp`, a sticky-bit directory where only the file's owner (`shell`) may delete, so
   the app could not delete it and the plaintext password stayed on the device unless the operator
   removed it. `content write` streams the file from your machine through adb's stdin straight
   into the provider, so the password is never a file on the device or a command-line argument.

What this does not defend against: anything that already has the adb shell or root on the panel
can still read the stored config (the password is stored in the app's private preferences, not
encrypted), and an app signed with the same platform key shares the app's uid.

## What "verified end to end" means here

This was tested against a real broker and a real device: connect, discovery publish, a color
command that actually turned the physical LED red, the retained state topic reflecting it, and an
off command turning it back off. One real bug was found and fixed along the way: Paho's
`automaticReconnect` re-establishes the connection by itself but does **not** resubscribe or
republish discovery -- that has to happen in `MqttCallbackExtended.connectComplete()`, called on
every successful connect including automatic reconnects, not just once after the initial
`connect()`. Without that fix, the app silently loses its subscription on the first reconnect.
