#!/usr/bin/env bash
# ClickHouse 를 직접 조회해 텔레메트리가 실제로 적재됐는지 증명한다.
# 사용법: signoz-verify.sh [service.name] [분]
# 호출: just signoz-verify
#
# UI 를 일부러 건너뛴다. 대시보드가 비어 보일 때 알아야 하는 것은 "수집이 실패한 것인가,
# 조회 조건이 틀린 것인가"이고, 저장소를 직접 읽으면 그 답이 나온다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
require_kind_context

SERVICE="${1:-scenetrip-scene-api}"
MINUTES="${2:-10}"
CH=chi-signoz-telemetrystore-clickhouse-cluster-0-0-0

kubectl get pod "$CH" -n "$SIGNOZ_NS" >/dev/null 2>&1 \
  || die "ClickHouse 파드를 찾을 수 없습니다 — SigNoz 가 설치돼 있나요? 실행: just cluster-up"

# ClickHouse 는 따옴표 없는 비ASCII 식별자를 문법 오류로 거절하므로 별칭은 ASCII 로 둔다.
# stderr 는 버리지 않고 걸러서 보여준다 — 조용히 삼키면 "0건"과 "질의 실패"가 구분되지 않는다.
q() {
  kubectl exec -n "$SIGNOZ_NS" "$CH" -- clickhouse-client --query "$1" 2>&1 \
    | grep -v 'Defaulted container'
}

echo "service.name = $SERVICE   구간 = 최근 ${MINUTES}분"
echo "-- 로그 --"
q "SELECT count() AS total, countIf(severity_text='WARN') AS warn,
          countIf(severity_text='ERROR') AS error,
          countIf(position(body,'eyJ')>0) AS unmasked_jwt
   FROM signoz_logs.distributed_logs_v2
   WHERE resources_string['service.name']='$SERVICE'
     AND timestamp > toUnixTimestamp64Nano(now64()-toIntervalMinute($MINUTES))
   FORMAT Vertical"

echo "-- 트레이스 --"
q "SELECT count() AS spans, uniq(trace_id) AS traces
   FROM signoz_traces.distributed_signoz_index_v3
   WHERE resource_string_service\$\$name='$SERVICE'
     AND timestamp > now()-toIntervalMinute($MINUTES)
   FORMAT Vertical"

echo "-- 메트릭 (샘플) --"
q "SELECT DISTINCT metric_name FROM signoz_metrics.distributed_samples_v4
   WHERE unix_milli > toUnixTimestamp(now()-toIntervalMinute($MINUTES))*1000
   ORDER BY metric_name LIMIT 5 FORMAT TSV"

echo
echo "※ unmasked_jwt 가 0 이 아니면 사고입니다 — 원문 토큰이 수집기로 나갔다는 뜻입니다."
echo "※ 세 신호가 모두 0 이면 SigNoz 관리자 계정을 만들었는지 확인하세요."
