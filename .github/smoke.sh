#!/bin/bash
# Temporary emulator smoke test: install, launch, open each surface, screenshot, collect crashes.
set -x
PKG=com.glassos.launcher
mkdir -p shots
adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.BLUETOOTH_CONNECT; do
  adb shell pm grant $PKG $p || true
done
adb logcat -c

shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; }

adb shell am start -n $PKG/.MainActivity
shot 01_home 14
# Control center: swipe down on the right side (below the system edge zone).
adb shell input swipe 900 500 900 1700 450
shot 02_control_center 3
# Mid-close: drag up slowly and capture while the modules are moving over the icons.
adb shell input swipe 540 1900 540 1500 900
shot 02b_control_center_after_partial_drag 2
adb shell input swipe 540 1900 540 500 250
sleep 3
# Notification center: swipe down on the left side.
adb shell input swipe 200 500 200 1700 450
shot 03_notification_center 3
adb shell input swipe 540 1900 540 600 250
sleep 3
# Apps screen: swipe up.
adb shell input swipe 540 1900 540 700 300
shot 04_app_drawer 3
adb shell input keyevent KEYCODE_BACK
sleep 3
shot 04b_after_back_home 1
# Settings via control center "+".
adb shell input swipe 900 500 900 1700 450
sleep 3
adb shell input tap 100 80
shot 05_settings 4
adb shell input swipe 540 1700 540 600 300
shot 05b_settings_scrolled 3
adb shell input keyevent KEYCODE_BACK
sleep 3

# Clear liquid glass icons + Ocean wallpaper, relaunch.
adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'sed -i \"s#</map>#<string name=\\\"icon_style\\\">Clear</string><string name=\\\"wallpaper\\\">Ocean</string></map>#\" shared_prefs/glassos_settings.xml'" || true
adb shell am start -n $PKG/.MainActivity
shot 06_home_clear_icons 14
adb shell input swipe 900 500 900 1700 450
shot 07_control_center_clear 3
adb shell input swipe 540 1900 540 500 250
sleep 3
adb shell input swipe 200 500 200 1700 450
shot 08_notification_center_clear 3

adb logcat -d > shots/logcat.txt
adb shell pidof $PKG > shots/pid.txt || echo "NOT RUNNING" > shots/pid.txt
exit 0
