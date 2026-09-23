#!/bin/bash
# 关闭 TeProjectApplication 进程

# 通过 jps 找到 TeProjectApplication 的 PID
PID=$(jps | grep 'TeProjectApplication' | awk '{print $1}')

if [ -z "$PID" ]; then
    echo "未找到 TeProjectApplication 进程"
    exit 0
fi

echo "正在结束进程 PID: $PID"
kill "$PID"

# 等待 2 秒，确认是否关闭
sleep 2
if jps | grep -q "$PID"; then
    echo "进程未正常关闭，强制结束..."
    kill -9 "$PID"
fi

echo "TeProjectApplication 已关闭"
