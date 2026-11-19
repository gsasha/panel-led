#!/bin/sh
# Turns a vendor platform key pair (platform.pk8 + platform.x509.pem, the format AOSP builds use)
# into the PKCS12 keystore Gradle signs with. Run once; the key itself never leaves your machine.
#
#   scripts/make-keystore.sh platform.pk8 platform.x509.pem out/platform.p12
#
# Writes out/platform.p12 (mode 600) and out/platform.p12.password (mode 600, random). Then:
#   export PANEL_LED_KEYSTORE=$PWD/out/platform.p12
#   export PANEL_LED_KEYSTORE_PASSWORD=$(cat out/platform.p12.password)
#   ./gradlew :app:assembleRelease
set -eu
[ $# -eq 3 ] || { echo "usage: $0 platform.pk8 platform.x509.pem out.p12" >&2; exit 2; }
pk8=$1
cert=$2
out=$3
umask 077
openssl rand -hex 16 > "$out.password"
openssl pkcs8 -inform DER -nocrypt -in "$pk8" -out "$out.key.pem"
openssl pkcs12 -export -inkey "$out.key.pem" -in "$cert" -name platform \
    -out "$out" -passout "file:$out.password"
rm -f "$out.key.pem"
echo "wrote $out and $out.password"
