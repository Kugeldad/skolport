#!/usr/bin/env bash
# Builds Skolport.apk without Gradle, using only the platform android.jar and
# the classic build tools (aapt, javac, dx, zipalign, apksigner).
#
# Ubuntu/Debian: sudo apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner openjdk-17-jdk-headless
# Signing key: keystore/skolport.jks, password in keystore/password.txt (created on first build).
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
DX="$(command -v dalvik-exchange || command -v dx)"
VERSION_CODE=5
VERSION_NAME=1.4
OUT=Skolport.apk

rm -rf build && mkdir -p build/gen build/classes build/dex

# 1. Resources + manifest -> R.java and resource-only APK
aapt package -f -m \
  -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F build/unsigned.apk

# 2. Java -> class files (Java 8 bytecode for dx)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d build/classes $(find src build/gen -name '*.java')

# 3. class files -> classes.dex, added to the APK
"$DX" --dex --min-sdk-version=24 --output=build/dex/classes.dex build/classes
(cd build/dex && aapt add ../unsigned.apk classes.dex >/dev/null)

# 4. Align and sign
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f keystore/skolport.jks ]; then
  mkdir -p keystore
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > keystore/password.txt
  keytool -genkeypair -noprompt -keystore keystore/skolport.jks -alias skolport \
    -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Skolport" \
    -storepass "$(cat keystore/password.txt)" -keypass "$(cat keystore/password.txt)"
fi
apksigner sign --ks keystore/skolport.jks --ks-key-alias skolport \
  --ks-pass file:keystore/password.txt \
  --out "$OUT" build/aligned.apk
apksigner verify --verbose "$OUT"
echo "Built $OUT"
