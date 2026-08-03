#!/usr/bin/env bash
# Query ClickHouse directly to prove telemetry actually landed.
# Usage: signoz-verify.sh [service.name] [minutes]
# Invoked by: just signoz-verify
#
# This bypasses the UI on purpose. When a dashboard looks empty, the question is
# whether collection failed or the query was wrong — reading the store answers it.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
require_kind_context

SERVICE="${1:-scenetrip-scene-api}"
MINUTES="${2:-10}"
CH=chi-signoz-telemetrystore-clickhouse-cluster-0-0-0

kubectl get pod "$CH" -n "$SIGNOZ_NS" >/dev/null 2>&1 \
  || die "ClickHouse pod not found — is SigNoz installed? run: just cluster-up"

# ClickHouse rejects unquoted non-ASCII identifiers, so aliases stay ASCII.
# stderr is filtered rather than discarded: swallowing it makes "0 rows" and
# "query failed" look identical.
q() {
  kubectl exec -n "$SIGNOZ_NS" "$CH" -- clickhouse-client --query "$1" 2>&1 \
    | grep -v 'Defaulted container'
}

echo "service.name = $SERVICE   window = last ${MINUTES}m"
echo "-- logs --"
q "SELECT count() AS total, countIf(severity_text='WARN') AS warn,
          countIf(severity_text='ERROR') AS error,
          countIf(position(body,'eyJ')>0) AS unmasked_jwt
   FROM signoz_logs.distributed_logs_v2
   WHERE resources_string['service.name']='$SERVICE'
     AND timestamp > toUnixTimestamp64Nano(now64()-toIntervalMinute($MINUTES))
   FORMAT Vertical"

echo "-- traces --"
q "SELECT count() AS spans, uniq(trace_id) AS traces
   FROM signoz_traces.distributed_signoz_index_v3
   WHERE resource_string_service\$\$name='$SERVICE'
     AND timestamp > now()-toIntervalMinute($MINUTES)
   FORMAT Vertical"

echo "-- metrics (sample) --"
q "SELECT DISTINCT metric_name FROM signoz_metrics.distributed_samples_v4
   WHERE unix_milli > toUnixTimestamp(now()-toIntervalMinute($MINUTES))*1000
   ORDER BY metric_name LIMIT 5 FORMAT TSV"

echo
echo "note: unmasked_jwt other than 0 is an incident — a raw token reached the collector."
echo "note: all three signals at 0 usually means the SigNoz admin account was never created."
