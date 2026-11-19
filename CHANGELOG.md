# Changelog

This project is pre-1.0; versions below `1.0.0` carry no binary-compatibility promise for the
inter-app API (see [`docs/api.md`](docs/api.md#versioning)) and no stability promise in general.

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
