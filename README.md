# panel-led

Control the RGB side light bars on cheap Android PoE wall panels (the "10.1 inch smart home
panel" family, Rockchip-based) and publish them to Home Assistant over MQTT -- **without the
seller's closed-source app or library.**

> **Unofficial.** Not affiliated with or endorsed by any panel vendor. Provided as is, without
> warranty (see [LICENSE](LICENSE)). It talks to a hardware device node on your own device; use it
> at your own risk. Whether it works depends on your panel's firmware -- see
> [Does this work on my panel?](#does-this-work-on-my-panel) below.

## What it does

- Drives the panel's side LED bars (`/dev/ledjni`), decoded from the vendor's closed library by
  disassembly, not from any documentation they published -- see [`docs/protocol.md`](docs/protocol.md).
- Publishes the light to Home Assistant over MQTT with discovery: it shows up as an ordinary
  `light` entity, RGB + brightness, with no YAML and nothing to configure on the Home Assistant
  side beyond having MQTT set up. See [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md).
- Exposes the same control over a plain Android inter-app API (`PanelControlService`), for anyone
  who would rather call into it from their own app than use MQTT. See [`docs/api.md`](docs/api.md).
- A small hardware probe screen for diagnosing what your specific panel exposes, independent of
  the MQTT/HA side.

## Does this work on my panel?

These panels, across several resellers, share closed libraries from a small set of chipset/ODM
vendors, but not every unit behaves the same. What's known:

- **An ordinary app cannot use the LED or relay/IO device nodes on a locked-down Android build.**
  Opening them works; the `ioctl` that actually drives the light is denied by SELinux for a
  regular app (`EACCES`). This is the normal case -- the probe screen tells you definitively
  whether this applies to your unit.
- **It can work if your panel's vendor will sign your build.** Some vendors will, on request, send
  you their platform signing key; signed with it, plus one manifest line, the app runs with the
  same privilege their own system apps have, and the LED/relay calls succeed. See
  [`docs/vendor-signing.md`](docs/vendor-signing.md) for exactly what to ask for and how to use it.
  This has been done successfully on two physical units from the same vendor family (one 10.1",
  one 15.6", different Android/firmware builds).
- **If your panel doesn't match this hardware at all**, the probe screen will show you that too --
  run it first, before assuming anything.

**If you try this on a panel not listed above, please open an issue with what the probe screen
showed** (model, Android version, whether `/dev/ledjni` exists) -- that's exactly what grows the
list of panels this is known to work on.

## Getting started

### Prerequisites

- The panel, with [ADB debugging](https://developer.android.com/tools/adb) reachable (USB, or
  wireless debugging enabled once in Developer Options -- note this often resets to off on
  reboot, so you may need to re-enable it after a restart).
- To build from source: JDK 17, Android SDK platform 34 with build tools 34.0.0, NDK
  `26.3.11579264`, Gradle 8.9 (AGP 8.7.3). See [Building](#building) below. If you'd rather not
  build it yourself, check this repo's Releases page for a prebuilt APK.
- An MQTT broker reachable from the panel, if you want the Home Assistant integration (not
  required for the hardware probe or the inter-app API alone).

### Quick start (human)

1. **Install the app.** Either grab a release APK from this repo's Releases page, or build one
   yourself (see [Building](#building)). Sideload it (`adb install app.apk`, or a file manager /
   browser download with "install unknown apps" allowed).
2. **Run the probe first.** Open the app -- it launches straight into the hardware probe screen.
   Tap "Cycle" and watch the panel's bezel. If the colors visibly change, the LED path works for
   your unit as-is (an ordinary, unsigned app). If not, see
   [Does this work on my panel?](#does-this-work-on-my-panel) and
   [`docs/vendor-signing.md`](docs/vendor-signing.md).
3. **Set up your MQTT broker's credentials for this app**, if you want the Home Assistant
   integration -- see [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#before-you-start-your-mqtt-broker-needs-a-user-for-this).
   This app does not include or assume any particular broker setup; that part is yours to arrange,
   the same as for any other MQTT client you add to your network.
4. **Configure the bridge.** Tap "Bridge settings" in the app for everything except the password,
   or do it all over adb (useful for the password specifically, so it's never typed where it
   could be seen on the panel's screen) -- the exact fields and commands are in
   [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#configuring-the-app).
5. **Check Home Assistant.** Once configured and connected, the light entity appears on its own
   (MQTT discovery) -- nothing further to do there.

### Quick start (agent)

If you'd rather hand this to an AI coding agent that has shell/adb access to the panel, give it
something like this (fill in the placeholders first):

```
Install and configure panel-led on the Android panel reachable at adb target <ADB_TARGET>
(e.g. a device serial, or host:port for wireless adb).

1. Confirm the device is reachable: `adb -s <ADB_TARGET> devices`.
2. Install the APK at <PATH_TO_APK> (uninstall first if an existing app.panelled is present with
   a different signature: `adb -s <ADB_TARGET> uninstall app.panelled`).
3. Launch it once: `adb -s <ADB_TARGET> shell am start -n app.panelled/.MainActivity`.
   Report back what the probe screen shows about /dev/ledjni -- this determines whether the
   LED/relay calls will actually work on this unit (see README.md "Does this work on my panel?").
4. Configure the MQTT bridge over adb, using these real values:
     - broker host: <MQTT_HOST>
     - broker port: <MQTT_PORT>  (1883 if unset)
     - MQTT username: <MQTT_USERNAME>
     - device slug: <SLUG>  (your own short, unique name for this panel)
   Do not pass the MQTT password as a command-line argument (it would land in shell history).
   First set the password by streaming it from a local file over adb's stdin (nothing is written
   on the device): `adb -s <ADB_TARGET> shell content write --uri
   content://app.panelled.config/password < <local_password_file>`. Then set the rest:
   `adb -s <ADB_TARGET> shell content update --uri content://app.panelled.config/config
   --bind host:s:<MQTT_HOST> --bind port:i:<MQTT_PORT> --bind user:s:<MQTT_USERNAME>
   --bind slug:s:<SLUG> --bind enabled:b:true`. You can read the result back (the password is
   never returned) with `adb -s <ADB_TARGET> shell content query --uri
   content://app.panelled.config/config`; it should show `password_set=1` and `configured=1`.
5. Verify: `adb -s <ADB_TARGET> shell logcat -d | grep MqttBridge` should show a line ending
   "connected, subscribed panelled/<SLUG>/led/set". If it instead shows a connect failure, check
   the broker host/port/credential and that the broker is reachable from the panel's network.
6. Confirm the broker's ACL grants this app's MQTT user read/write on `panelled/<SLUG>/#` and
   write on the Home Assistant discovery prefix (typically `homeassistant/light/#`) -- without
   both, the entity may appear in Home Assistant but stay stuck `unavailable`. That ACL lives on
   the broker, not on the panel; if you don't have access to configure it, ask whoever runs the
   broker to add it, following docs/mqtt-bridge.md's exact grant description.
7. Report back what you found at each step, including anything that failed and the exact command
   or output that showed it -- don't report success without having checked step 5's actual log
   output.
```

### Building

```
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Sideloading needs "install unknown apps" enabled for whatever opens the APK (a file manager, a
browser). A debug build works for the hardware probe and (if your panel doesn't need vendor
signing) the full bridge; see [`docs/vendor-signing.md`](docs/vendor-signing.md) for building a
signed release instead.

## Project layout

- [`docs/protocol.md`](docs/protocol.md) -- the decoded device protocol: `/dev/ledjni`,
  `/dev/gpio_control`, the exact `ioctl` calls.
- [`docs/api.md`](docs/api.md) -- the Binder/AIDL inter-app control surface.
- [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md) -- the MQTT/Home Assistant integration, its topics,
  and why its adb config interface is a content provider.
- [`docs/vendor-signing.md`](docs/vendor-signing.md) -- what to do if your panel needs a vendor
  signature to unlock LED/relay access.
- `app/` -- the Android app. `LedDevice`/`GpioDevice` are the whole hardware driver;
  `ledshim.c` is a 3-function native shim (`open`/`ioctl`/`close`) written from the decoded
  protocol. No vendor binary is included anywhere in this repo.
- [`CHANGELOG.md`](CHANGELOG.md) -- version history.

## Known limitations

- The stored MQTT password sits in the app's private preferences, not encrypted: anything with
  the adb shell or root on the panel can read it. See
  [`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#why-the-adb-config-interface-is-a-content-provider)
  for what the adb config interface does and does not defend against.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). If you run this on a panel not yet confirmed to work,
opening an issue with the probe screen's output is itself a useful contribution.

## License

MIT, see [LICENSE](LICENSE).
