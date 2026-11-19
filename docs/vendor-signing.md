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

## Build & sign (manual; not wired into Gradle)

```
./gradlew :app:assembleRelease                       # unsigned release APK
$ANDROID_HOME/build-tools/34.0.0/zipalign -v -p 4 \
    app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
java -jar $ANDROID_HOME/build-tools/34.0.0/lib/apksigner.jar sign \
    --key <path-to-platform.pk8> --cert <path-to-platform.x509.pem> \
    --out signed.apk aligned.apk
```

`apksigner` accepts a raw PKCS#8 key and X.509 certificate directly (`--key`/`--cert`); no keystore
conversion needed.

**Never commit the key or certificate to this repo, or to any other git history.** Keep them
outside any repository (e.g. `~/secrets/panel-led/`, permissions `600`), and point a build or
release script at that path via an environment variable rather than a path hard-coded into a
tracked file.

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
