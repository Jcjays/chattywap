#!/bin/sh
# Prints a line of resource and pool metrics every INTERVAL seconds (default 5) until stopped: the application's CPU,
# heap, database pool, request threads and outbox backlog (from its Prometheus endpoint on the management port), and
# the CPU and memory of the PostgreSQL and RabbitMQ containers. Run it next to a load test:
#
#   loadtest/sample-metrics.sh > loadtest/results/metrics-$(date +%H%M%S).txt &
#
# MANAGEMENT_URL defaults to http://localhost:8081, PROJECT (the Compose project) to chattywap-loadtest.
set -u

management="${MANAGEMENT_URL:-http://localhost:8081}"
project="${PROJECT:-chattywap-loadtest}"
interval="${INTERVAL:-5}"

metric() {
  # Sum of every series of the metric, so that labels don't matter; "-" if it is missing.
  echo "$scrape" | awk -v name="$1" '$1 == name || index($1, name "{") == 1 { sum += $NF; found = 1 }
    END { if (found) printf "%.3f", sum; else printf "-" }'
}

echo "time app_cpu heap_mb db_active db_idle db_pending db_timeouts http_threads_busy ws_sessions outbox_pending pg_cpu pg_mem rabbit_cpu rabbit_mem"
while true; do
  scrape="$(curl -s --max-time 3 "$management/actuator/prometheus" || true)"
  heap="$(echo "$scrape" | awk '/^jvm_memory_used_bytes\{.*area="heap"/ { sum += $NF } END { printf "%.0f", sum / 1048576 }')"
  containers="$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
    "$project-postgres-1" "$project-rabbitmq-1" 2>/dev/null | awk '{ printf "%s %s ", $2, $3 }')"
  echo "$(date +%H:%M:%S) $(metric process_cpu_usage) $heap $(metric hikaricp_connections_active)" \
    "$(metric hikaricp_connections_idle) $(metric hikaricp_connections_pending)" \
    "$(metric hikaricp_connections_timeout_total) $(metric tomcat_threads_busy_threads)" \
    "$(metric chattywap_websocket_sessions) $(metric chattywap_outbox_pending) $containers"
  sleep "$interval"
done
