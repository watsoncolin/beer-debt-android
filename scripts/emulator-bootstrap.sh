#!/bin/bash
# One-time: install command-line tools, emulator, platform-tools and an API 36
# system image into the SDK, then create the `beerdebt` Pixel AVD that
# scripts/screenshots.sh boots. Android Studio's device manager does the same
# thing with clicks.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT=$ANDROID_HOME
IMAGE="system-images;android-36;google_apis;arm64-v8a"
mkdir -p "$ANDROID_HOME" && cd "$ANDROID_HOME"

if [ ! -x cmdline-tools/latest/bin/sdkmanager ]; then
  ZIP=$(curl -s https://dl.google.com/android/repository/repository2-3.xml | grep -o 'commandlinetools-mac-[0-9]*_latest.zip' | sort -t- -k3 -n | tail -1)
  echo "installing $ZIP"
  curl -sL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$ZIP"
  rm -rf /tmp/cmdline-tools-extract && unzip -q -o /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
  mkdir -p cmdline-tools && rm -rf cmdline-tools/latest && mv /tmp/cmdline-tools-extract/cmdline-tools cmdline-tools/latest
fi
SDKM=cmdline-tools/latest/bin/sdkmanager
yes | $SDKM --licenses >/dev/null 2>&1 || true
$SDKM --install "platform-tools" "emulator" "$IMAGE" 2>&1 | grep -v -E "^\[|^\s*$" | tail -3
echo no | cmdline-tools/latest/bin/avdmanager create avd -n beerdebt -k "$IMAGE" -d pixel_6 --force 2>&1 | tail -1
echo "AVD beerdebt ready"
