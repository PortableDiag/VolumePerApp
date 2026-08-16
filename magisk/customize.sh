#!/system/bin/sh
#
# Runs once, at flash time, inside Magisk's installer.
#
# Everything under system/ is mounted over the real /system by Magisk's overlay,
# so nothing here copies files — the module directory IS the mount. What this
# script does is refuse to install when the result would be a broken device,
# and set the modes the platform requires on a priv-app.

SKIPUNZIP=0

ui_print " "
ui_print "  VolumePerApp — privileged install"
ui_print "  ---------------------------------"

# --- refuse rather than half-work ------------------------------------------

if [ ! -f "$MODPATH/system/priv-app/VolumePerApp/VolumePerApp.apk" ]; then
  ui_print "! The APK is missing from this zip."
  ui_print "! Build it with tools/build-magisk.sh, which packages the signed"
  ui_print "! release APK into the module. Aborting."
  abort   "! Nothing was installed."
fi

API=$(getprop ro.build.version.sdk)
if [ "$API" -lt 29 ]; then
  ui_print "! Android 10 (API 29) or newer is required."
  ui_print "! This device reports API $API. The uid-matching AudioMix this app"
  ui_print "! depends on does not exist before then. Aborting."
  abort   "! Nothing was installed."
fi

# A copy of the app installed normally sits in /data and wins over the system
# one, which is exactly the confusing half-state this checks for.
if pm path com.volumeperapp.app 2>/dev/null | grep -q '/data/app'; then
  ui_print "* A user-installed copy of VolumePerApp is present."
  ui_print "* Uninstall it after rebooting, or the privileged copy stays"
  ui_print "* shadowed and the app will still report Limited mode."
fi

# --- modes ------------------------------------------------------------------

set_perm_recursive "$MODPATH/system/priv-app"        0 0 0755 0644
set_perm_recursive "$MODPATH/system/etc/permissions" 0 0 0755 0644

ui_print "* APK          -> /system/priv-app/VolumePerApp/"
ui_print "* Permissions  -> /system/etc/permissions/privapp-permissions-volumeperapp.xml"
ui_print "* Grants       -> MODIFY_AUDIO_ROUTING, CAPTURE_AUDIO_OUTPUT, CAPTURE_MEDIA_OUTPUT"
ui_print " "
ui_print "  Reboot, then open VolumePerApp. The banner at the top of the"
ui_print "  mixer says Privileged mode when this worked. If it still says"
ui_print "  Limited, open Diagnostics — it names which step failed."
ui_print " "
