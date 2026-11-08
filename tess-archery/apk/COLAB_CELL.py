# QUIVER APK - paste this into one Colab cell (Runtime: no GPU needed)
# It builds the WHOLE app as an APK, including the alarms.
!bash -c '
set -e
apt-get -qq update > /dev/null
apt-get -qq install -y openjdk-17-jdk-headless unzip > /dev/null
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
mkdir -p $HOME/android-sdk && cd $HOME/android-sdk
[ -d cmdline-tools ] || { curl -s -o c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip; unzip -q c.zip; mkdir -p cmdline-tools/latest; mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/ 2>/dev/null || true; }
export ANDROID_HOME=$HOME/android-sdk
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager "platforms;android-34" "build-tools;34.0.0" > /dev/null
echo "== toolchain ok =="
cd /content
rm -rf QuiverBuild && git clone -q https://github.com/G3need/G3need.github.io.git QuiverBuild
cd QuiverBuild/tess-archery
rm -rf apk/app/src/main/assets && mkdir -p apk/app/src/main/assets
cp index.html apk/app/src/main/assets/ && cp -r assets/. apk/app/src/main/assets/
if [ -f /content/drive/MyDrive/quiver_owner/quiver_owner.html ]; then
  cp /content/drive/MyDrive/quiver_owner/* apk/app/src/main/assets/
  echo "OWNER LAYER APPLIED (supply bay + private notes included)"
else echo "public build (no owner file)"; fi
for d in hdpi xhdpi xxhdpi; do mkdir -p apk/app/src/main/res/mipmap-$d; cp assets/icon-512.png apk/app/src/main/res/mipmap-$d/ic_launcher.png; done
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "assets: $(find apk/app/src/main/assets -type f | wc -l) files, $(du -sh apk/app/src/main/assets | cut -f1)"
[ -d $HOME/gradle-8.7 ] || { curl -s -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip; unzip -q -d $HOME /tmp/g.zip; }
export PATH=$HOME/gradle-8.7/bin:$PATH
cd apk
gradle assembleDebug --no-daemon --console=plain 2>&1 | tail -25
APK=$(find . -name "*.apk" | head -1)
cp "$APK" /content/quiver-debug.apk
[ -d /content/drive/MyDrive ] && { cp "$APK" /content/drive/MyDrive/quiver-debug.apk; echo "saved to Drive"; }
ls -la /content/quiver-debug.apk
echo "== DONE: install with adb install quiver-debug.apk, or move it to the phone and tap it =="
'
