#!/bin/zsh
D=${1:-10.165.1.102:5555}
for t in 1 2 3; do adb -s $D shell dumpsys window | grep -m1 mCurrentFocus | grep -q glasslauncher && break; adb -s $D shell input keyevent 4; sleep 2; done
adb -s $D shell dumpsys gfxinfo com.glasslauncher.tv reset >/dev/null
adb -s $D shell 'input keyevent 19; sleep 0.6; for i in 1 2 3 4 5 6; do input keyevent 22; sleep 0.7; done; input keyevent 20; sleep 0.6; for i in 1 2 3 4 5; do input keyevent 22; sleep 0.35; done; for i in 1 2 3 4 5; do input keyevent 21; sleep 0.35; done'
adb -s $D shell dumpsys gfxinfo com.glasslauncher.tv framestats | python3 tools/framestats.py
adb -s $D shell dumpsys gfxinfo com.glasslauncher.tv | grep -E "Total frames|Janky frames:|50th percentile|90th percentile|99th percentile|50th gpu|90th gpu"
