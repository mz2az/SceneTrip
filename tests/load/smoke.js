// 연기 확인 — k6 와 대상이 붙어 있는지만 본다(1 명, 10 초). 부하가 아니다. MZ2AZ-502, docs/project/plans/load-test.md §5.
// 실행: just load-run smoke.js -e BASE_URL=https://api-dev.scenetrip.io/v1
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE_URL || "https://api-dev.scenetrip.io/v1";

export const options = {
  vus: 1,
  duration: "10s",
  thresholds: { http_req_failed: ["rate<0.01"] },
};

export default function () {
  // 앱처럼 설치 ID 를 싣는다 — 없으면 IP 한 칸으로 세여 요청 한도에 걸린다(계획 §4).
  const headers = { "X-Install-Id": "00000000-0000-4000-8000-00000000000" + (__VU % 10) };
  const r = http.get(`${BASE}/contents?limit=5`, { headers });
  check(r, { "작품 목록 200": (res) => res.status === 200 });
  sleep(1);
}
