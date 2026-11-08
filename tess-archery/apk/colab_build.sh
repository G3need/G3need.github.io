#!/data/data/com.termux/files/usr/bin/bash
# Runs in a Colab cell. Builds the whole Quiver APK from the public repo.
set -e
echo "== 1. toolchain =="
apt-get -qq update > /dev/null
apt-get -qq install -y openjdk-17-jdk-headless unzip > /dev/null
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
mkdir -p "$HOME/android-sdk"
cd "$HOME/android-sdk"
if [ ! -d cmdline-tools ]; then
  curl -s -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q cmdtools.zip
  mkdir -p cmdline-tools/latest
  mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/ 2>/dev/null || true
fi
export ANDROID_HOME="$HOME/android-sdk"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager "platforms;android-34" "build-tools;34.0.0" > /dev/null
echo "local.properties.ok"

echo "== 2. project =="
cd /content
rm -rf Quiver
git clone -q https://github.com/G3need/G3need.github.io.git Quiver
cd Quiver
# the app itself: public build
rm -rf apk/app/src/main/assets
mkdir -p apk/app/src/main/assets
cp tess-archery/index.html apk/app/src/main/assets/
cp -r tess-archery/assets/. apk/app/src/main/assets/
# the owner layer: if Drive has a private index.html, it replaces the public one
OWNER="/content/drive/MyDrive/quiver_owner/quiver_owner.html"
if [ -f "$OWNER" ]; then cp /content/drive/MyDrive/quiver_owner/* apk/app/src/main/assets/; echo "OWNER LAYER APPLIED"; else echo "public build (no owner file)"; fi
# icons
cp tess-archery/assets/icon-512.png apk/app/src/main/res/mipmap-hdpi/ic_launcher.png 2>/dev/null || true
mkdir -p apk/app/src/main/res/mipmap-hdpi apk/app/src/main/res/mipmap-xhdpi apk/app/src/main/res/mipmap-xxhdpi
for d in hdpi xhdpi xxhdpi; do cp tess-archery/assets/icon-512.png apk/app/src/main/res/mipmap-$d/ic_launcher.png; done
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "assets: $(find apk/app/src/main/assets -type f | wc -l) files, $(du -sh apk/app/src/main/assets | cut -f1)"

echo "== 3. build =="
if [ ! -d "$HOME/gradle-8.7" ]; then
  curl -s -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
  unzip -q -d "$HOME" /tmp/gradle.zip
fi
export PATH="$HOME/gradle-8.7/bin:$PATH"
cd apk
gradle assembleDebug --no-daemon --console=plain 2>&1 | tail -25
APK=$(find . -name "*.apk" | head -1)
echo "== built: $APK =="
cp "$APK" /content/quiver-debug.apk
if [ -d /content/drive/MyDrive ]; then cp "$APK" /content/drive/MyDrive/quiver-debug.apk; echo "saved to Drive"; fi
ls -la /content/quiver-debug.apk
