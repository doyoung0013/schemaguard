#!/usr/bin/env bash
# 실험 전용 MySQL 인스턴스 (시스템 MySQL 과 별도 데이터 폴더·포트).
#   scripts/lock-experiment/mysql-local.sh start <데이터 폴더> [포트, 기본 3307]
#   scripts/lock-experiment/mysql-local.sh stop  <데이터 폴더>
# root 비밀번호 없이(--initialize-insecure) 127.0.0.1 에서만 받는다. 측정이 끝나면 stop 하고 폴더를 지운다.
set -euo pipefail
cmd="$1"; dir="$(mkdir -p "$2" && cd "$2" && pwd)"; port="${3:-3307}"
case "$cmd" in
  start)
    if [ ! -d "$dir/data/mysql" ]; then
      mysqld --no-defaults --initialize-insecure --user="$(whoami)" --datadir="$dir/data" > "$dir/init.log" 2>&1
    fi
    mysqld --no-defaults --user="$(whoami)" --datadir="$dir/data" --port="$port" --bind-address=127.0.0.1 \
      --socket="$dir/mysqld.sock" --pid-file="$dir/mysqld.pid" --mysqlx=OFF \
      --performance-schema=ON --innodb-buffer-pool-size=512M --log-error="$dir/error.log" &
    for _ in $(seq 1 60); do
      mysqladmin --no-defaults -h127.0.0.1 -P"$port" -uroot ping >/dev/null 2>&1 && { echo "MySQL ready on 127.0.0.1:$port"; exit 0; }
      sleep 1
    done
    echo "MySQL did not start; see $dir/error.log" >&2; exit 1 ;;
  stop)
    mysqladmin --no-defaults -S "$dir/mysqld.sock" -uroot shutdown ;;
  *) echo "usage: $0 start|stop <dir> [port]" >&2; exit 2 ;;
esac
