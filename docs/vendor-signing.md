# Vendor signing (resolved, 2026-11-19)

The panel vendor (ELC) sent a zip containing their platform signing key (`platform.pk8`, PKCS#8
DER, unencrypted) and the matching certificate (`platform.x509.pem`), plus their own `signapk.jar`.
**The certificate's SHA-256 fingerprint (`9ef8ee67...ed2904`) matches the signer of their own
installed system apps exactly** (checked against `com.elc.smt_test` pulled off the playroom panel)
-- confirmed to be their real platform key, not a mismatched or placeholder one.

## What this app now has that it didn't before

1. `AndroidManifest.xml` declares `android:sharedUserId="android.uid.system"` -- the same thing the
   vendor's own apps declare. This line is inert on a signature mismatch (install just fails); it
   only has effect once the APK is actually signed with the matching cert.
2. The release build is signed with that cert, not a debug key.

Both are required together -- this is the exact combination the vendor's own apps use (confirmed by
inspecting `com.elc.smt_test`'s manifest earlier in this task), not a guess.

## Build & sign (manual, today -- not yet wired into Gradle)

```
./gradlew :app:assembleRelease                       # unsigned release APK
$ANDROID_HOME/build-tools/34.0.0/zipalign -v -p 4 \
    app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
java -jar $ANDROID_HOME/build-tools/34.0.0/lib/apksigner.jar sign \
    --key <path-to-platform.pk8> --cert <path-to-platform.x509.pem> \
    --out signed.apk aligned.apk
```

`apksigner` accepts the raw PKCS#8 key + X.509 cert directly (`--key`/`--cert`); no keystore
conversion needed. The key and certificate are **not in this repo** -- they are the vendor's
platform signing key, kept outside any git history on the host that has them
(`~/secrets/panel-led/` on this one). **Never commit them, here or anywhere else.** A future
release script should read them from an external path the same way `portico-app` does
(`PORTICO_SIGNING_PROPS`), not bundle them.

## Result (2026-11-19, tested on both panels)

Installed (uninstall first -- the signature changed, so a plain upgrade install is refused) on:
- The playroom panel (`domatic-tablet-gf-play`, 15.6" `ZX-SMT1569`).
- The kitchen tablet (`domatic-tablet-gf-kitchen`, 10.1" `ZX-SMT1019`).

On both: the running process shows `scontext=u:r:system_app:s0`, uid `system` (confirmed via
`ps -AZ`) -- the same domain as the vendor's own apps. **Every LED and relay/IO call that was
previously denied now succeeds**: `setRgb`/`off` on `/dev/ledjni` and the GET call on
`/dev/gpio_control` all return `0`, with no `avc: denied` in logcat. This is the first time either
has worked from this app; `docs/protocol.md`'s SELinux finding and `docs/api.md`'s "expected -13
until signed" note are both now historical, not current.

Not tested: the relay/IO **write** call (still deliberately out of scope -- unknown wiring, see
`docs/protocol.md`).
