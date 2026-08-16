#!/system/bin/sh
#
# Magisk runs this when the module is removed.
#
# The overlay disappears on its own, which takes the APK and the permission XML
# with it. What does not disappear is the app's own settings, and leaving them
# behind is deliberate: someone who removes the module to reflash a newer one
# should not lose every fader position they set.
#
# Nothing to do here. The file exists so that is on the record rather than
# looking like an oversight.
exit 0
