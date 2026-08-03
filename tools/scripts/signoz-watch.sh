#!/usr/bin/env bash
# Print SigNoz log ingestion counts on an interval. Ctrl+C to stop.
# Usage: signoz-watch.sh [service.name] [interval-seconds]
# Invoked by: just signoz-watch
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
require_kind_context

SERVICE="${1:-scenetrip-scene-api}"
INTERVAL="${2:-5}"
CH=chi-signoz-telemetrystore-clickhouse-cluster-0-0-0

echo "service.name = $SERVICE   refresh = ${INTERVAL}s   (Ctrl+C to stop)"
while true; do
  kubectl exec -n "$SIGNOZ_NS" "$CH" -- clickhouse-client --query \
    "SELECT formatDateTime(now(),'%H:%M:%S') AS time,
            count() AS total,
            countIf(severity_text='ERROR') AS errors,
            countIf(severity_text='WARN')  AS warns,
            countIf(position(body,'eyJ')>0) AS unmasked
     FROM signoz_logs.distributed_logs_v2
     WHERE resources_string['service.name']='$SERVICE'
       AND timestamp > toUnixTimestamp64Nano(now64()-toIntervalMinute(1))
     FORMAT TSVWithNames" 2>/dev/null | column -t
  echo
  sleep "$INTERVAL"
done
