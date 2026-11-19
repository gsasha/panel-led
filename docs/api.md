# The control API

This is `panel-led`'s inter-app surface: an ordinary Android bound service, for an installed app
other than `panel-led` that would rather drive the light directly than go through the MQTT/HA
bridge (`docs/mqtt-bridge.md`, the normal way to use this). The API itself runs no network code --
when this was two apps (one signed, one not), that mattered for keeping the signed half minimal;
now that signing is ours to do whenever needed (`docs/vendor-signing.md`), it is just the clean
split between "drive the hardware" and "talk MQTT", not a hard requirement.

## Binding to it

1. In the caller's manifest:
   ```xml
   <uses-permission android:name="app.panelled.permission.CONTROL_LIGHT" />
   ```
2. Bind with an **explicit** `Intent` (never an implicit one -- this service does not promise to be
   the only thing that will ever answer `app.panelled.action.BIND_CONTROL`, so always name the
   package):
   ```java
   Intent intent = new Intent("app.panelled.action.BIND_CONTROL");
   intent.setPackage("app.panelled");
   bindService(intent, connection, Context.BIND_AUTO_CREATE);
   ```
3. In `onServiceConnected`, wrap the binder: `IPanelControl.Stub.asInterface(binder)`.
4. Call `getCapabilities()` first and check the bit before calling anything else -- see below.

The `.aidl` file itself (`app/src/main/aidl/app/panelled/IPanelControl.aidl`) is the actual contract;
copy it into the caller's own `src/main/aidl/app/panelled/` so both sides compile the same interface.

## The interface, today

```java
interface IPanelControl {
    int getCapabilities();      // bitmask, see below
    int setLedColor(int color); // 0xRRGGBB
    int setLedOff();
}
```

- `getCapabilities()` returns `CAP_LED` (`0x1`) on every build that ships this file, and nothing
  else -- there is nothing else to control yet. Check it before calling `setLedColor`/`setLedOff`
  so a caller never has to guess by catching a failure; a future version that lost LED support for
  some reason (it won't, but the point of checking is that a caller doesn't have to assume) would
  simply not set that bit.
- `setLedColor`/`setLedOff` return `0` on success or a negative `errno` (`-13` is `EACCES`). **As of
  2026-11-19 this returns `0`** on a release build signed with the vendor's platform key plus
  `android:sharedUserId="android.uid.system"` -- see `docs/vendor-signing.md`. A debug build still
  returns `-13`: the AIDL contract is unchanged either way, only the signature decides which answer
  a given build gets.

## What is deliberately not here

Relay and digital-IO control (`/dev/gpio_control`, documented in `docs/protocol.md`) are not in this
interface. They were investigated -- same SELinux wall as the LED -- but Sasha did not ask for them to
be built, only to check the permission question, so adding `CAP_RELAY`/`CAP_IO` now would be
speculative surface with its own failure modes (in particular, the relay/IO *write* call was never
even tested on real hardware, because it can flip something of unknown wiring). If that's wanted
later: add `CAP_RELAY = 0x2` and new methods **appended** to the interface (never inserted before
`setLedOff`, never renumbering `CAP_LED`), and give it its own permission
(`app.panelled.permission.CONTROL_RELAY`) rather than folding it into `CONTROL_LIGHT` -- a caller
that only wants to turn a light on should never have to declare the permission to flip a relay.

## Versioning

While this project is 0.x: the interface can still change between releases, and a caller should
build against the exact `.aidl` shipped with the `panel-led` version it targets -- there is no
promise of binary compatibility yet. From 1.0 on, this file is append-only: existing method order,
signatures and `CAP_*` values are fixed for good; only new methods at the end, gated by a new bit.
