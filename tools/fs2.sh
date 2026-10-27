#!/bin/zsh
# fs2.sh <device> <keys-script> : gfxinfo for one interaction pattern
D=$1
for t in 1 2 3; do adb -s $D shell dumpsys power | grep -q "mWakefulness=Awake" && adb -s $D shell dumpsys window | grep -m1 mCurrentFocus | grep -q glasslauncher && break; adb -s $D shell input keyevent 4; sleep 2; done
adb -s $D shell "$3" >/dev/null 2>&1; sleep 1
adb -s $D shell dumpsys gfxinfo com.glassioyahsun.tv reset >/dev/null
adb -s $D shell "$2"
adb -s $D shell dumpsys gfxinfo com.glassioyahsun.tv framestats | python3 tools/framestats.py | cat
adb -s $D shell dumpsys gfxinfo com.glassioyahsun.tv | grep -E "Janky frames:|50th percentile|90th percentile|99th percentile"
