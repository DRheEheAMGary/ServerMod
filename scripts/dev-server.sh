#!/usr/bin/env bash
# 开发期无人值守跑一次服务端并执行若干命令。
#
# 用法：scripts/dev-server.sh "hub info" "hub selftest"
# 依赖：run/server.properties 里已开启 rcon（enable-rcon=true, rcon.password=...）
#
# 注意：服务端在装了 LuckPerms 之后首次启动可能要几分钟（它要下载/重映射依赖），
# 所以这里等的是"本次启动新出现的 Done 行"，而不是日志里任意一行 Done。
set -uo pipefail

cd "$(dirname "$0")/.."

PROP="run/server.properties"
RCON_PASS="$(grep -E '^rcon\.password=' "$PROP" | cut -d= -f2-)"
RCON_PORT="$(grep -E '^rcon\.port=' "$PROP" | cut -d= -f2-)"
RCON_PASS="${RCON_PASS:-hubsuite-dev}"
RCON_PORT="${RCON_PORT:-25575}"
READY_TIMEOUT="${READY_TIMEOUT:-420}"

LOG="run/logs/latest.log"

# 清理上一次强杀留下的锁，否则服务端会拒绝启动
pkill -9 -f "net.fabricmc.devlaunchinjector.Main" 2>/dev/null || true
sleep 2
rm -f run/world/session.lock run/hubsuite_*/session.lock 2>/dev/null || true

# 把上次的日志归档掉，这样 latest.log 里出现的任何 "Done (" 都一定是本次启动产生的
if [ -f "$LOG" ]; then
  mv -f "$LOG" "$LOG.prev" 2>/dev/null || true
fi

./gradlew runServer --console=plain > /tmp/hubsuite-devserver.out 2>&1 &
GRADLE_PID=$!

echo "[dev-server] 启动中（最多等 ${READY_TIMEOUT}s，LuckPerms 首次启动较慢）……"
READY=0
for _ in $(seq 1 $((READY_TIMEOUT / 2))); do
  if [ -f "$LOG" ] && grep -q 'Done (' "$LOG"; then
    READY=1
    break
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    echo "[dev-server] 服务端进程提前退出。"
    break
  fi
  sleep 2
done

if [ "$READY" -ne 1 ]; then
  echo "[dev-server] 服务端未就绪，日志尾部："
  tail -40 "$LOG" 2>/dev/null || tail -40 /tmp/hubsuite-devserver.out
  kill -9 "$GRADLE_PID" 2>/dev/null || true
  exit 1
fi

echo "[dev-server] 服务端已就绪，执行命令……"

python3 - "$RCON_PORT" "$RCON_PASS" "$@" <<'PY'
import socket, struct, sys, time

port = int(sys.argv[1]); password = sys.argv[2]; commands = sys.argv[3:]

def pack(req_id, kind, body):
    payload = struct.pack('<ii', req_id, kind) + body.encode('utf8') + b'\x00\x00'
    return struct.pack('<i', len(payload)) + payload

def read_packet(sock):
    raw = sock.recv(4)
    if len(raw) < 4:
        return None, None
    (length,) = struct.unpack('<i', raw)
    data = b''
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    req_id, kind = struct.unpack('<ii', data[:8])
    return req_id, data[8:-2].decode('utf8', 'replace')

# RCON 监听可能比 "Done" 稍晚，重试连接
s = None
for attempt in range(30):
    try:
        s = socket.create_connection(('127.0.0.1', port), timeout=10)
        break
    except OSError:
        time.sleep(1)
if s is None:
    print('[rcon] 无法连接 RCON 端口', port); sys.exit(2)

s.sendall(pack(1, 3, password))
req_id, _ = read_packet(s)
if req_id == -1:
    print('[rcon] 认证失败'); sys.exit(2)

for i, cmd in enumerate(commands, start=2):
    s.sendall(pack(i, 2, cmd))
    _, body = read_packet(s)
    print(f'\n===== /{cmd} =====')
    print(body)
    time.sleep(0.5)

s.sendall(pack(999, 2, 'stop'))
try:
    read_packet(s)
except Exception:
    pass
s.close()
PY

echo "[dev-server] 等待服务端退出……"
for _ in $(seq 1 60); do
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    break
  fi
  sleep 2
done
kill -9 "$GRADLE_PID" 2>/dev/null || true
pkill -9 -f "net.fabricmc.devlaunchinjector.Main" 2>/dev/null || true
echo "[dev-server] 完成。日志：$LOG"
