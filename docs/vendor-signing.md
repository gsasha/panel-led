# Vendor signing

Some panel vendors will, on request, send you their platform signing key (a PKCS#8 private key
plus an X.509 certificate) so you can sign your own app the same way their system apps are signed.
If yours does, this is what to do with it.

**Verify it's genuinely theirs before using it.** Compare the certificate's SHA-256 fingerprint
against the signer of one of the vendor's own installed system apps (any `apksigner verify
--print-certs` on an APK pulled off the device). If they don't match, don't use it -- you'd be
signing with the wrong key, or a placeholder.

## What a vendor signature actually buys you

1. `AndroidManifest.xml` declares `android:sharedUserId="android.uid.system"` -- the same thing a
   vendor's own system apps typically declare. This line has no effect on its own; it is inert
   (install refused) unless the APK is signed with a certificate matching the one that already
   signs whatever already holds that shared UID on the device.
2. The release build must be signed with the vendor's certificate, not a debug key.

Both are needed together. Confirm the exact combination by inspecting one of the vendor's own
system apps' manifest on a device you control, rather than assuming it.

## Build & sign

Gradle signs the release build itself when it is given a keystore, and leaves it unsigned
otherwise (so a build without the vendor key behaves exactly as before). A vendor platform key
usually comes as a `platform.pk8` + `platform.x509.pem` pair; `scripts/make-keystore.sh` turns
that into the PKCS12 keystore Gradle wants (once; the password is random, written to a file next
to it, and never appears on a command line):

```
scripts/make-keystore.sh <path-to-platform.pk8> <path-to-platform.x509.pem> <dir>/platform.p12
export PANEL_LED_KEYSTORE=<dir>/platform.p12
export PANEL_LED_KEYSTORE_PASSWORD=$(cat <dir>/platform.p12.password)
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk -- aligned and signed
```

`PANEL_LED_KEY_ALIAS` defaults to `platform` (what the script names the key). Check the result
against the vendor's certificate fingerprint with
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`. The APK is
signed with APK Signature Scheme v2; that was enough for the system-uid join on the Android 14
panels this was tested on.

**Never commit the key, the certificate, the keystore or its password to this repo, or to any
other git history.** Keep them outside any repository (e.g. `~/secrets/panel-led/`, permissions
`600`), and point the build at them via the environment variables above rather than a path
hard-coded into a tracked file.

## Result, once correctly signed and installed

The app's process runs with `scontext=u:r:system_app:s0` (confirm with `ps -AZ` on the device) --
the same SELinux domain as the vendor's own system apps. Every LED and relay/IO `ioctl` that was
previously denied for an ordinary app succeeds: `LedDevice`'s calls and the read-only GPIO check
both return `0`, with no `avc: denied` entries in `logcat`. `docs/protocol.md`'s SELinux finding
and `docs/api.md`'s `-13` note both describe an **unsigned** build; this is what changes once it's
signed.

Since the signing identity changes, a plain upgrade install over an existing unsigned install is
refused -- uninstall first (`adb uninstall app.panelled`), then install the signed APK.

The relay/IO **write** call is deliberately untested in this project -- it can flip hardware of
unknown wiring, so it's left as a documented risk rather than exercised blind; see
`docs/protocol.md`.
