# Contributing

This started as a small, personal tool, so the bar for a useful contribution is low -- a report
is as valuable as a patch.

## Reporting panel compatibility

If you run this on a panel not already confirmed to work, that's a genuinely useful contribution
by itself. Open an issue with:

- The exact model/listing you bought (a link helps).
- What the hardware probe screen shows -- in particular, whether `/dev/ledjni` exists and whether
  tapping "Cycle" visibly changes the bezel's color.
- The Android version and `ro.build.display.id` (`adb shell getprop ro.build.display.id`).
- Whether your panel vendor was willing to sign a build for you (see
  [`docs/vendor-signing.md`](docs/vendor-signing.md)), if the hardware probe alone didn't work.

## Reporting a bug

Include the exact `adb logcat` lines around the failure (tag `MqttBridge` or `ConfigReceiver` for
the bridge, or whatever the probe screen printed for the hardware side), and your Android
version/build.

## Code changes

- Small, focused pull requests are easiest to review.
- If you're touching `docs/protocol.md` or `docs/vendor-signing.md` with a new device/vendor
  finding, say how you confirmed it (disassembly, a probe run, a vendor's own document) -- this
  project tries hard to distinguish "observed on real hardware" from "the vendor's PDF says so,"
  and new entries should keep doing that.
- `app/src/main/aidl/app/panelled/IPanelControl.aidl` is append-only once released (see
  [`docs/api.md`](docs/api.md#versioning)): never reorder or remove an existing method, never
  change a `CAP_*` value.

## Security

This app can run with elevated privileges once vendor-signed (see
[`docs/vendor-signing.md`](docs/vendor-signing.md)). If you find a security issue specific to
that -- not the already-documented limitations in
[`docs/mqtt-bridge.md`](docs/mqtt-bridge.md#two-known-limitations) -- please open an issue
describing the class of problem rather than a working exploit, so it can be looked at before
any detail is public.
