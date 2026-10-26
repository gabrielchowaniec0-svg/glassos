#!/bin/bash
set -x
PKG=com.glassos.launcher
mkdir -p shots
adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.BLUETOOTH_CONNECT; do adb shell pm grant $PKG $p || true; done
adb shell cmd package set-home-activity $PKG/.MainActivity || true
adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell cmd notification allow_listener $PKG/$PKG.GlassNotificationListener || true
adb shell cmd notification post -S bigtext -t "Ring" ring1 "There is a person at your Front garages" || true
adb shell cmd notification post -S bigtext -t "Jerzy" garage1 "Big Garage Door just closed" || true
adb logcat -c
shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; }
adb shell input keyevent KEYCODE_HOME
shot 01_home 14
# Pull from the very top edge (left side), screenshot mid-pull, then release.
adb shell input motionevent DOWN 300 6
for y in 60 140 240 360 500 650 800 950; do adb shell input motionevent MOVE 300 $y; done
shot 02_sheet_mid_pull 2
for y in 1100 1300 1500; do adb shell input motionevent MOVE 300 $y; done
adb shell input motionevent UP 300 1500
shot 03_sheet_open 8
# Close by swiping up on the bottom bar area.
adb shell input swipe 540 2250 540 900 300
shot 04_after_close 6
# Top edge, right side -> control center.
adb shell input motionevent DOWN 900 6
for y in 80 200 400 700 1000 1300 1500; do adb shell input motionevent MOVE 900 $y; done
adb shell input motionevent UP 900 1500
shot 05_control_center_from_edge 8
adb logcat -d > shots/logcat.txt
adb shell pidof $PKG > shots/pid.txt || echo "NOT RUNNING" > shots/pid.txt
exit 0
