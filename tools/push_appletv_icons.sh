#!/bin/zsh
# Waits for tools/appletv_icons.py to finish, then copies the Apple TV icon library to the TV
# (Android/data/com.glasslauncher.tv/files/appletv/<variant>/), where the icon picker lists it.
[ -n "$ANDROID_HOME" ] && export PATH=$PATH:$ANDROID_HOME/platform-tools
D=${TV:?Set TV=<tv-ip>:5555}
LIB=${1:-appletv-icons}
while pgrep -f "appletv_icons.py" >/dev/null; do sleep 20; done
adb connect $D >/dev/null
for v in default dark white clear; do
  adb -s $D push "$LIB/$v" /sdcard/Android/data/com.glasslauncher.tv/files/appletv/ >/dev/null
done
echo "pushed $(ls $LIB/default | wc -l | tr -d ' ') apps at $(date)" >> "$LIB/push.log"
