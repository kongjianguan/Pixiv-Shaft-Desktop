#!/usr/bin/env bash
#
# 验证登录凭据与下载队列在进程重启后仍然保留。
#
# 每一步都是独立进程，所以验证的是真正的持久化，而不是同一进程里读写一遍。
# 数据库与下载产物落在脚本自己创建的目录里（验证程序把数据库指到当前目录），
# 钥匙串用带 probe_ 前缀的键名，不会动到真实登录态。
#
# 不覆盖 HOME：默认钥匙串的位置由家目录决定，把 HOME 指到空目录会让 security
# 去那里新建默认钥匙串，从而一直卡住。
#
# 每个调用都带超时：钥匙串访问在无人应答的会话里可能一直阻塞，没有超时会让
# 整个任务挂住。
#
# 用法： verify/store-persistence.sh [store_probe 的路径]

set -euo pipefail

CORE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BINARY="${1:-$CORE_DIR/target/release/store_probe}"
WORK="$CORE_DIR/.store-probe"
RUN="$WORK/run"

if [[ ! -x "$BINARY" ]]; then
  echo "找不到可执行的验证程序：$BINARY" >&2
  exit 1
fi

with_timeout() {
  local seconds="$1"
  shift
  perl -e 'alarm shift; exec @ARGV' "$seconds" "$@"
}

step() {
  echo
  echo "----- $1 -----"
}

expect_file() {
  local path="$1"
  if [[ ! -s "$path" ]]; then
    echo "期望存在且非空的文件不存在：$path" >&2
    exit 1
  fi
  echo "文件已生成：${path}（$(wc -c <"$path" | tr -d ' ') 字节）"
}

rm -rf "$WORK"
mkdir -p "$RUN"

probe() {
  (cd "$RUN" && with_timeout 60 "$BINARY" "$@")
}

step "写入凭据（进程一）"
probe token-write

step "另起进程读取凭据"
probe token-verify

step "加入下载任务并暂停（进程三）"
probe enqueue task-a
probe pause task-a

step "另起进程确认暂停状态仍在"
probe expect task-a PAUSED

step "另起进程查看队列全貌"
probe list

step "恢复并真正下载（进程五）"
probe resume task-a
probe download

step "另起进程确认完成状态与字节数"
probe expect task-a COMPLETED
probe list

step "确认下载产物落盘"
expect_file "$RUN/probe-downloads/142389693_p0.img"

step "清理"
probe clear-queue
probe token-clear

step "另起进程确认队列已空"
probe list

step "写入设置（进程一）"
probe setting-write workFeedMaxColumns 6
probe setting-write isShowR18 true

step "另起进程确认设置仍在"
probe setting-expect workFeedMaxColumns 6
probe setting-expect isShowR18 true

step "未改过的设置读出默认值"
probe setting-expect workTitleMaxLines 1

step "重置回默认值"
probe setting-reset workFeedMaxColumns
probe setting-expect workFeedMaxColumns 4

step "记录浏览（进程一）"
probe browse-write illust 142389693

step "另起进程确认浏览记录仍在"
probe browse-expect illust 142389693 present

step "同一个关键词搜两次，只留一条"
probe search-write 初音ミク
probe search-write 初音ミク
probe search-expect 1

step "另起进程确认搜索记录仍在"
probe search-expect 1

step "清理历史"
probe clear-history
probe browse-expect illust 142389693 absent
probe search-expect 0

echo
echo "全部通过：凭据、队列、设置与历史跨进程保留，下载产物已落盘。"
