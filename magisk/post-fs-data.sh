#!/system/bin/sh
#
# Suppresses the platform's hearing-safety nag, when the app has asked for it.
#
# Two annoyances — the "Volume alert" dialog when you raise a headset past the
# safe level, and the "Volume lowered" attenuation after a long listen — are one
# mechanism, gated on SoundDoseHelper.mSafeMediaVolumeState. Setting
# audio.safemedia.bypass makes updateSafeMediaVolume_l land on
# SAFE_MEDIA_VOLUME_DISABLED, which silences both.
#
# WHY THIS RUNS HERE AND NOT IN THE APP
#
# SoundDoseHelper reads the property exactly once, from
# AudioService.onSystemReady(). Its only other caller, onConfigurationChanged(),
# passes force=false and so re-runs on an MCC change alone. Setting the property
# at runtime is therefore a no-op, and writing Settings.Global's
# audio_safe_volume_state is worse — AudioService recomputes and overwrites it on
# the next boot. post-fs-data is before zygote, which is the only moment that
# works.
#
# WHY THE FLAG LIVES IN DEVICE-PROTECTED STORAGE
#
# This stage runs before the user is unlocked. Measured on the target device at
# post-fs-data: /data/user_de/0/<pkg> is listable, /data/data/<pkg> does not
# exist yet. An ordinary SharedPreferences file would be unreadable here.
#
# The file is written by SafeVolume.java. The two must agree on name and content.

for flag in /data/user_de/*/com.volumeperapp.app/files/safemedia_bypass; do
  [ -f "$flag" ] || continue
  # Content, not mere presence: an empty file from a failed write is not consent.
  [ "$(cat "$flag" 2>/dev/null)" = "1" ] || continue
  resetprop audio.safemedia.bypass true
  break
done
