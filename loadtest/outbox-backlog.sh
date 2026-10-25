#!/bin/sh
# Measures how fast a backlog of outbox events drains: adds EVENTS pending events (default 50000) to the load-test
# database while the application runs, then prints the pending count and the events processed by the example consumer
# every INTERVAL seconds (default 5) until the backlog is gone. Run from the project directory:
#
#   loadtest/outbox-backlog.sh 50000
#
# To see the backlog grow first, stop RabbitMQ (docker compose -p chattywap-loadtest --env-file loadtest/.env stop
# rabbitmq), send messages (for example with rest-mix.js), then start it again and run this script with 0 events.
set -eu

events="${1:-50000}"
interval="${INTERVAL:-5}"
cd "$(dirname "$0")/.."

psql() {
  docker compose -p chattywap-loadtest --env-file loadtest/.env exec -T postgres \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At "$@"' psql "$@"
}

processed_before="$(psql -c "select count(*) from processed_events")"
if [ "$events" -gt 0 ]; then
  psql -v events="$events" < loadtest/outbox-backlog.sql > /dev/null
fi
start="$(date +%s)"
echo "seconds pending processed_since_start"
while true; do
  pending="$(psql -c "select count(*) from outbox_events where published_at is null")"
  processed="$(psql -c "select count(*) from processed_events")"
  now="$(date +%s)"
  echo "$((now - start)) $pending $((processed - processed_before))"
  if [ "$pending" -eq 0 ] && [ $((processed - processed_before)) -ge "$events" ]; then
    break
  fi
  sleep "$interval"
done
echo "Drained $events events in $((now - start)) s"
