#!/usr/bin/env bash
# Captures README screenshots from the running emulator. Called by screenshots.yml.
set -e
PKG=com.tokalot.app
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.OfflineFlowService
adb shell settings put secure accessibility_enabled 1

# Clean status bar: fixed time, full battery, no notification icons.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

mkdir -p docs/screenshots
shot() {
  local name=$1 theme=$2
  shift 2
  adb shell am start -S -W -n $PKG/.MainActivity --ez demo true --es theme "$theme" "$@"
  sleep 4
  adb exec-out screencap -p > "docs/screenshots/$name.png"
  echo "captured $name"
}
shot home-light light --es tab HOME
shot home-dark dark --es tab HOME
shot style light --es tab STYLE --es category AI_CODE
shot snippets dark --es tab SNIPPETS
shot dictionary light --es tab DICTIONARY
shot settings light --ez settings true
