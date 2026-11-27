# Changelog

This project is pre-1.0; versions below `1.0.0` carry no binary-compatibility promise for the
inter-app API (see [`docs/api.md`](docs/api.md#versioning)) and no stability promise in general.

## 0.6.1-alpha -- the light bars are `Backlight`, category `diagnostic`

The Home Assistant entity is now named **Backlight** (was "Side light") and is published with
`entity_category: diagnostic`, so the bars stay out of area- and device-wide "turn off the lights"
calls and out of the default light lists; an explicit `entity_id` still controls them. The
`unique_id` is unchanged, so an existing entity keeps its history and id. A name you set in
Home Assistant's entity registry wins over the published name.

## 0.6.0-alpha -- adb config is adb-only; Gradle signing

**Breaking: the adb config interface changed.** The `ConfigReceiver` broadcast is gone, replaced by
a content provider (`content://app.panelled.config`, driven with `adb shell content update /
write / query`). Two gaps from the old receiver are closed: any installed app could send to it
(the provider answers only the adb shell or root, checked by caller uid), and the password had to
be pushed as a file that the app could not delete (it is now streamed over adb's stdin and never
stored on the device as a file). Verified on a real panel, including that an ordinary app is
refused. Stored config carries over unchanged. Commands: [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#over-adb);
the reasoning: [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#why-the-adb-config-interface-is-a-content-provider).

**Gradle can sign the release build** when `PANEL_LED_KEYSTORE` and `PANEL_LED_KEYSTORE_PASSWORD`
are set (with `scripts/make-keystore.sh` to make the keystore from a `platform.pk8` +
`platform.x509.pem` pair), replacing the manual zipalign + apksigner steps. Without them the
release is left unsigned as before. See [`docs/vendor-signing.md`](docs/vendor-signing.md).

## 0.5.0-alpha -- dithered, gamma-corrected transitions

0.4.0's fade sampled once per hardware level crossed (only 16 per channel), which visibly froze
at both ends of the curve and did most of its motion in one short burst through the middle. Fades
now sample at a fixed 1000 Hz and dither between the two hardware levels bracketing the true
continuous target (temporal dithering, the same idea as Floyd-Steinberg error diffusion but in
time instead of space), and the brightness mapping is gamma-corrected (2.2) so equal raw-level
steps read as equal perceived-brightness steps instead of mostly-invisible ones near the top of
the range. See [`docs/mqtt-bridge.md#why-its-dithered-and-gamma-corrected`](docs/mqtt-bridge.md#why-its-dithered-and-gamma-corrected).

## 0.4.0-alpha -- local, smooth transitions

Commands may now carry an optional `"transition"` field (seconds), the same convention Home
Assistant's MQTT JSON light schema uses for `light.turn_on(transition=...)`. The app fades from
its current state to the target locally -- eased with the same quintic smoothstep curve ESPHome
uses for this field -- instead of snapping instantly or requiring a stream of updates over MQTT.
See [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#transitions).

## 0.3.1-alpha -- restart the bridge after an app update, not only a reboot

The bridge's service gets killed by an app update like any other process, and until now only a
device reboot restarted it afterward (`BootReceiver`'s `BOOT_COMPLETED` handler). These panels are
rarely rebooted, so an update could leave the bridge down indefinitely with no visible error on
the panel itself -- only a MQTT-unavailable light in Home Assistant. `BootReceiver` now also
handles `MY_PACKAGE_REPLACED`.

## 0.3.0-alpha -- MQTT / Home Assistant bridge

Publishes the light to Home Assistant over MQTT with discovery (`MqttBridgeService`). Adds a
settings screen, plus an adb-only path for setting the broker password without typing it on the
panel's screen. No longer split into two apps (a minimal signed one plus a separate bridge) --
see [`docs/vendor-signing.md`](docs/vendor-signing.md) for why that split is no longer necessary
once you can get your own build signed. Two known limitations documented in
[`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#two-known-limitations).

## 0.2.1-alpha -- vendor-signed

Confirmed that signing a release build with a panel vendor's platform signing key, plus
`android:sharedUserId="android.uid.system"` in the manifest, unlocks every LED and relay/IO call
that an ordinary unsigned app gets `EACCES` on. Verified on two physical panel models from the
same vendor family. See [`docs/vendor-signing.md`](docs/vendor-signing.md). The signing key
itself is never part of this repo and must never be committed here.

## 0.2.0-alpha -- control API

Adds `PanelControlService` (`IPanelControl` over Binder/AIDL, see [`docs/api.md`](docs/api.md)):
another app on the device can bind to it and set the LED color or turn it off, with no network
code anywhere in this app. Also exports a read-only relay/IO check (`GpioDevice`, GET only, no
SET) for diagnosis; not part of the control API.

## 0.1.0-alpha -- hardware probe

Shows what the device exposes for the light (device nodes, sysfs paths, system properties) and
has buttons that fire the decoded `ioctl` sequence directly, with the result code of every call
shown on screen. Writes nothing except those `ioctl` calls. Not a complete app on its own --
this is the diagnostic tool for finding out whether your panel can be driven this way at all.
