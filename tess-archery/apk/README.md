# Quiver APK - built in Colab

## What this is
The WHOLE app as an APK, not just the alarm feature. The HTML/CSS/JS is
unchanged from the web build; the native layer only adds what a browser
cannot have.

## Files
- `app/src/main/java/agency/toku/quiver/MainActivity.kt`  WebView host + JS bridge
- `app/src/main/java/agency/toku/quiver/AlarmScheduler.kt` real AlarmManager logic
- `app/src/main/java/agency/toku/quiver/AlarmReceiver.kt`  notification at the set time
- `app/src/main/assets/`  filled by the Colab script from the public repo
- `colab_build.sh`        the one-paste Colab build

## To build
1. Open a Colab cell (runtime: no GPU needed, 2 vCPU is enough).
2. Mount Drive.
3. Run `!bash colab_build.sh` (or paste its content into a cell).
4. `quiver-debug.apk` lands in `/content` and in your Drive.

## The alarm behaviour that actually ships
- Supplies carry: name, alarm label, dose, recurrence (every N hours), start
  time, on/off.
- Exact alarms via `setRepeating(RTC_WAKEUP)` when Android lets us.
- If exact alarms are denied (Android 14+ default), the same schedule runs in
  soft mode (`setInexactRepeating`, 10-minute window) and the app says so
  honestly instead of pretending.
- The web side pushes the whole list on every add/toggle/delete, so the two
  never drift.
- Optional: drop a private `quiver_owner.html` in Drive and the build replaces
  the public index.html with the owner build (Supply Bay included). Nothing
  private is ever committed to the public repo.

## Install
`adb install quiver-debug.apk`, or move the file to the phone and tap it.
