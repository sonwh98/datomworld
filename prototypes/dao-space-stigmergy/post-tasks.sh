#!/usr/bin/env bash
# Post demo tasks to the dao.space board as agent "poster".
#   usage: ./post-tasks.sh    (coordinator must be running; see README.md)
set -euo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

post() {
  clj -M -m dao.space.stigmergy deposit poster \
    "[{:task/id \"$(uuidgen | tr 'A-Z' 'a-z')\" :task/title \"$1\" :task/posted true}]"
}

post "write a haiku about tuple spaces"
post "explain stigmergy in one sentence a child could understand"
post "suggest three names for a coordination library based on ant behavior"
post "write a two-line limerick about append-only logs"

echo "posted 4 tasks; board:"
clj -M -m dao.space.stigmergy query \
  '[:find ?id ?title :where [?e :task/id ?id] [?e :task/title ?title]]'
