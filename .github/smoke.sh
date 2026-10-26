#!/bin/bash
# Temporary emulator smoke test: install, launch, open each surface, screenshot, collect crashes.
set -x
PKG=com.glassos.launcher
mkdir -p shots
adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.BLUETOOTH_CONNECT; do
  adb shell pm grant $PKG $p || true
done
adb shell settings put global window_animation_scale 1
adb logcat -c

shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; }

adb shell am start -n $PKG/.MainActivity
shot 01_home 14
adb shell input swipe 900 120 900 1500 450
shot 02_control_center 4
adb shell input swipe 540 2000 540 900 250
sleep 3
adb shell input swipe 200 120 200 1500 450
shot 03_notification_center 4
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell input swipe 540 1900 540 700 300
shot 04_app_drawer 4
adb shell input keyevent KEYCODE_BACK
sleep 3
# Open GlassOS settings via the control center "+" button.
adb shell input swipe 900 120 900 1500 450
sleep 3
adb shell input tap 100 110
shot 05_settings 4
adb shell input keyevent KEYCODE_BACK
sleep 2

# Switch to clear liquid glass icons and relaunch.
adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat shared_prefs/glassos_settings.xml'" > shots/prefs_before.xml || true
adb shell "run-as $PKG sh -c 'sed -i \"s#</map>#<string name=\\\"icon_style\\\">Clear</string><string name=\\\"wallpaper\\\">Ocean</string></map>#\" shared_prefs/glassos_settings.xml'" || true
adb shell am start -n $PKG/.MainActivity
shot 06_home_clear_icons 14
adb shell input swipe 900 120 900 1500 450
shot 07_control_center_clear 4

adb logcat -d > shots/logcat.txt
adb shell pidof $PKG > shots/pid.txt || echo "NOT RUNNING" > shots/pid.txt
exit 0
