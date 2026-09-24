#!/bin/bash
# 冷启动测量：从 open 到 AppKit 主窗口出现在窗口列表中（不需要辅助功能权限）
APP="PixivShaft"
for round in 1 2 3; do
  osascript -e "tell application \"$APP\" to quit" >/dev/null 2>&1
  sleep 3
  pkill -f "/Applications/$APP.app" >/dev/null 2>&1
  sleep 2
  S=$(python3 -c 'import time;print(time.time())')
  open -a "$APP"
  FOUND=0
  for i in $(seq 1 400); do
    pid=$(pgrep -f "/Applications/$APP.app/Contents/MacOS/$APP" | head -1)
    if [ -n "$pid" ]; then
      out=$(lsappinfo info -only windows "$pid" 2>/dev/null)
      if [ -n "$out" ] && [ "$out" != "" ]; then
        E=$(python3 -c 'import time;print(time.time())')
        python3 -c "print(f'round $round: first window listed after {$E-$S:.2f}s')"
        FOUND=1
        break
      fi
    fi
    sleep 0.05
  done
  [ "$FOUND" = "0" ] && echo "round $round: no window within 20s"
done
