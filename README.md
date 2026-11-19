# panel-led

Control the RGB side light bars of cheap Android PoE wall panels (the "10.1 inch smart home panel"
family, Rockchip based) **without the seller's closed-source app or library**.

These panels ship with programmable LED bars on the bezel, but the only documented way to drive them is
a closed Java library from the seller. `panel-led` is an independent, open implementation of the same
device interface:

- [`docs/protocol.md`](docs/protocol.md): the device protocol as observed (`/dev/ledjni`, three `ioctl`
  requests, brightness 0..15 per channel).
- `app/`: an Android app. `LedDevice` is the whole driver; `ledshim.c` is a 3-call native shim
  (`open`, `ioctl`, `close`) written from that description. No vendor binary is included.
- [`docs/api.md`](docs/api.md): this app's whole external surface -- a Binder/AIDL service
  (`PanelControlService` / `IPanelControl`), nothing else. `panel-led` itself runs no network server
  of any kind (no MQTT, no HTTP); an external app that wants to publish the light to MQTT or Home
  Assistant calls in through this API and does the networking on its own side.

> **Unofficial.** Not affiliated with or endorsed by any panel vendor. Provided as is, without warranty
> (see [LICENSE](LICENSE)). It talks to a hardware device node on your own device; use it at your own
> risk. Whether it works depends on your panel's firmware: some newer units (Android 14 / RK3576) are
> reported not to expose the same interface, and finding out is the job of the probe below.

## Versions

- **0.2.1-alpha, vendor-signed.** Signed with a platform key the vendor provided, plus
  `android:sharedUserId="android.uid.system"` in the manifest -- the exact combination their own
  apps use. Confirmed working on two panel models: every LED/relay-read call that was previously
  denied now succeeds. See [`docs/vendor-signing.md`](docs/vendor-signing.md). **The signing key is
  not in this repo**, and must never be committed here or anywhere else.
- **0.2.0-alpha, control API.** Adds `PanelControlService` (`IPanelControl` over AIDL, see
  [`docs/api.md`](docs/api.md)) -- another app on the device can bind to it and set the LED color or
  turn it off, without this app running any network code. Still exports a read-only relay/IO check
  (`GpioDevice`, GET only) for diagnosis, not in the control API; see `docs/protocol.md`.
- **0.1.0-alpha, hardware probe.** Shows what the device exposes for the light (device nodes, sysfs,
  properties) and has buttons that fire the decoded ioctl sequence, with the result code of every call
  on screen. It writes nothing except those ioctls. Not a final app.

## Build

JDK 17, Android SDK platform 34 with build tools 34.0.0, NDK 26.3.11579264, Gradle 8.9 (AGP 8.7.3):

```
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Sideloading needs "install unknown apps" enabled for whatever opens the APK (a file manager, a browser).

## Contributing results

If you run the probe on a panel, the model, the Android version and the probe screen's output are
exactly what is useful: open an issue with them.
