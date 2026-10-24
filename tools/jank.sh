#!/bin/zsh
# jank.sh <device> : resets gfxinfo, browses carousel and dock, prints frame stats
D=${1:?usage: $0 <tv-ip:5555>}
adb -s $D shell input keyevent 224; sleep 1
adb -s $D shell dumpsys gfxinfo com.glasslauncher.tv reset >/dev/null
adb -s $D shell 'input keyevent 19; sleep 0.6; for i in 1 2 3 4 5 6; do input keyevent 22; sleep 0.7; done; input keyevent 20; sleep 0.6; for i in 1 2 3 4 5; do input keyevent 22; sleep 0.35; done; for i in 1 2 3 4 5; do input keyevent 21; sleep 0.35; done; input keyevent 20; sleep 0.8; input keyevent 22; sleep 0.4; input keyevent 4; sleep 0.8'
adb -s $D shell dumpsys gfxinfo com.glasslauncher.tv | grep -E "Total frames|Janky|50th|90th|95th|99th|Slow UI|Slow bitmap|Slow issue|Frame deadline" | head -12
