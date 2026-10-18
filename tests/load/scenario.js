// 실제 앱 흐름을 본뜬 부하 시나리오 (MZ2AZ-502, docs/project/plans/load-test.md §5).
//
// 흐름 넷을 비중대로 섞는다 — 둘러보기 60 · 담기 15 · 여행 15 · 챗봇 10(가정한 비율, PRD 실사용자가 쌓이면 맞춘다).
// 사람처럼 단계 사이에 1~3 초 쉬고(생각하는 시간), 작품·촬영지·지도 영역·검색어를 무작위로 골라 캐시 착시를 줄인다.
// 부하는 「초당 몇 명이 새로 들어오나」(도착률, ramping-arrival-rate)로 건다 — 서버가 느려져도 사용자는 계속 들어온다.
//
// 회원 흐름(여행·챗봇)은 미리 만든 시험 회원 토큰(`just load-tokens`)으로 진짜 POST /auth/google 을 탄다 — DEV 가 시험 모드여야
// 한다(`just loadtest-mode dev on`). 길찾기·챗봇의 바깥 호출은 가짜 서버로 간다.
//
// 실행 예:
//   just load-run scenario.js -e BASE_URL=https://api-dev.scenetrip.io/v1 -e TOKENS=/abs/path/tokens.json -e PROFILE=baseline
// PROFILE: smoke(1/s, 1분) · baseline(10/s, 10분) · stress(계단식 5→200/s) · spike(10→200/s 급증) · soak(20/s, 30분)
// RATE_SCALE 로 전체 도착률을 곱한다(기본 1).
import http from "k6/http";
import { check, group, sleep } from "k6";
import { SharedArray } from "k6/data";

const BASE = __ENV.BASE_URL || "https://api-dev.scenetrip.io/v1";
const PROFILE = __ENV.PROFILE || "smoke";
const SCALE = Number(__ENV.RATE_SCALE || "1");

// 시험 회원 — [{sub, idToken, nonce}]. 없으면 회원 흐름을 건너뛴다.
const TOKENS = new SharedArray("tokens", () =>
  __ENV.TOKENS ? JSON.parse(open(__ENV.TOKENS)) : [],
);

// 흐름 비중(합 1).
const WEIGHTS = { browse: 0.6, cart: 0.15, trip: 0.15, chat: 0.1 };

// 프로필 — 전체 도착률(초당 새 흐름 수)의 단계. 흐름마다 비중을 곱해 나눈다.
const PROFILES = {
  smoke: { start: 1, stages: [{ target: 1, duration: "1m" }] },
  baseline: {
    start: 2,
    stages: [
      { target: 10, duration: "1m" },
      { target: 10, duration: "10m" },
    ],
  },
  stress: {
    start: 5,
    stages: [
      { target: 25, duration: "2m" },
      { target: 25, duration: "3m" },
      { target: 50, duration: "2m" },
      { target: 50, duration: "3m" },
      { target: 100, duration: "2m" },
      { target: 100, duration: "3m" },
      { target: 200, duration: "2m" },
      { target: 200, duration: "3m" },
      { target: 0, duration: "1m" },
    ],
  },
  spike: {
    start: 10,
    stages: [
      { target: 10, duration: "2m" },
      { target: 200, duration: "10s" },
      { target: 200, duration: "1m" },
      { target: 10, duration: "10s" },
      { target: 10, duration: "2m" },
    ],
  },
  soak: {
    start: 5,
    stages: [
      { target: 20, duration: "2m" },
      { target: 20, duration: "30m" },
    ],
  },
};

function scenarioFor(flow) {
  const p = PROFILES[PROFILE];
  const w = WEIGHTS[flow] * SCALE;
  return {
    executor: "ramping-arrival-rate",
    exec: flow,
    // k6 는 도착률을 정수로만 받아 분당으로 센다(초당 0.15 = 분당 9).
    startRate: Math.max(1, Math.round(p.start * w * 60)),
    timeUnit: "1m",
    preAllocatedVUs: 50,
    maxVUs: 2000,
    stages: p.stages.map((s) => ({ target: Math.round(s.target * w * 60), duration: s.duration })),
    tags: { flow },
  };
}

const flows = TOKENS.length > 0 ? ["browse", "cart", "trip", "chat"] : ["browse", "cart"];

export const options = {
  scenarios: Object.fromEntries(flows.map((f) => [f, scenarioFor(f)])),
  // 합격선(계획 §6) — 바깥이 가짜인 길찾기·챗봇은 가짜 지연(초 단위)이 들어가 따로 본다.
  thresholds: {
    "http_req_failed{flow:browse}": ["rate<0.01"],
    "http_req_duration{flow:browse}": ["p(95)<500"],
    "http_req_duration{flow:cart}": ["p(95)<500"],
    "http_req_failed{flow:trip}": ["rate<0.01"],
    "http_req_failed{flow:chat}": ["rate<0.02"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

// 409(이미 담김)는 정상 응답으로 센다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

function uuid() {
  // RFC4122 v4 비슷한 것 — 설치 ID 용.
  return "xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx".replace(/x/g, () =>
    Math.floor(Math.random() * 16).toString(16),
  );
}
const pick = (a) => a[Math.floor(Math.random() * a.length)];
const think = () => sleep(1 + Math.random() * 2);
const json = (body) => ({ body: JSON.stringify(body), contentType: "application/json" });

function req(method, path, { body, token, install, name } = {}) {
  const headers = { "Accept-Language": pick(["ko", "ko", "en"]) };
  if (install) headers["X-Install-Id"] = install;
  if (token) headers.Authorization = `Bearer ${token}`;
  const params = { headers, tags: { name: name || `${method} ${path.split("?")[0].replace(/\/\d+/g, "/{id}")}` } };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    return http.request(method, `${BASE}${path}`, JSON.stringify(body), params);
  }
  return http.request(method, `${BASE}${path}`, null, params);
}

// 시작 전에 한 번 — 실제 번호를 API 로 모은다(번호를 박아 두지 않는다).
export function setup() {
  const install = uuid();
  // 목록은 한 번에 100 개까지(계약 limit 최대) — 끝까지 넘겨 모은다.
  const all = (path) => {
    const out = [];
    for (let offset = 0; offset < 2000; offset += 100) {
      const items = req("GET", `${path}${path.includes("?") ? "&" : "?"}limit=100&offset=${offset}`, {
        install,
        name: `setup ${path}`,
      }).json("items");
      if (!items || items.length === 0) break;
      out.push(...items);
      if (items.length < 100) break;
    }
    return out;
  };
  const contents = all("/contents").map((c) => c.id);
  const places = all("/places").map((p) => ({ id: p.id, lat: p.latitude, lng: p.longitude }));
  const words = ["도깨비", "경복궁", "부산", "제주", "카페", "해변", "궁", "시장", "공원", "Seoul"];
  console.log(`준비: 작품 ${contents.length} · 촬영지 ${places.length}`);
  return { contents, places, words };
}

// ── 둘러보기(비회원) — 작품 → 상세 → 촬영지 → 검색 → 지도 → 촬영지 상세
export function browse(data) {
  const install = uuid();
  group("browse", () => {
    req("GET", `/contents?limit=20&offset=${Math.floor(Math.random() * 100)}`, { install });
    think();
    const cid = pick(data.contents);
    check(req("GET", `/contents/${cid}`, { install }), { "작품 상세 200": (r) => r.status === 200 });
    req("GET", `/contents/${cid}/places`, { install });
    think();
    req("GET", `/search/suggestions?q=${encodeURIComponent(pick(data.words))}&limit=8`, { install });
    think();
    const lng = 126.85 + Math.random() * 0.3;
    const lat = 37.45 + Math.random() * 0.2;
    check(req("GET", `/pois?bbox=${lng},${lat},${lng + 0.02},${lat + 0.015}&limit=30`, { install }), {
      "지도 200": (r) => r.status === 200,
    });
    think();
    req("GET", `/places/${pick(data.places).id}`, { install });
  });
}

// ── 담기(비회원) — 장바구니 담기 → 보기 → 빼기, 작품 찜
export function cart(data) {
  const install = uuid();
  group("cart", () => {
    const p = pick(data.places);
    check(req("POST", "/cart/items", { install, body: { placeId: p.id } }), {
      "담기 201/409": (r) => r.status === 201 || r.status === 409,
    });
    think();
    req("GET", "/cart", { install });
    think();
    req("POST", "/favorites/contents", { install, body: { contentId: pick(data.contents) } });
    req("DELETE", `/cart/items/${p.id}`, { install });
  });
}

// 회원 세션 — 가상 사용자마다 시험 회원 하나를 붙잡아 한 번 로그인하고 다시 쓴다(사람처럼).
const sessions = {};
function member() {
  const idx = (__VU * 7919 + Math.floor(Math.random() * 3)) % TOKENS.length;
  if (sessions[idx]) return sessions[idx];
  const t = TOKENS[idx];
  const install = uuid();
  const r = req("POST", "/auth/google", { install, body: { idToken: t.idToken, nonce: t.nonce } });
  if (!check(r, { "로그인 200": (res) => res.status === 200 })) return null;
  sessions[idx] = { token: r.json("accessToken"), install };
  return sessions[idx];
}

// ── 여행(회원) — 코스 만들기 → 장소 담기 → 시작 → 길찾기(가짜 카카오) → 지우기
export function trip(data) {
  const s = member();
  if (!s) return;
  group("trip", () => {
    const p = pick(data.places);
    const c = req("POST", "/courses", { ...s, body: { dayCount: 1, origin: "self", title: "부하 코스" } });
    if (!check(c, { "코스 201": (r) => r.status === 201 })) return;
    const id = c.json("id");
    think();
    const filled = req("PUT", `/courses/${id}`, {
      ...s,
      body: { title: "부하 코스", days: [{ items: [{ placeId: p.id, dwellMinutes: 30 }] }] },
    });
    const item = filled.status === 200 ? filled.json("days.0.items.0.id") : null;
    req("PUT", `/courses/${id}/progress`, { ...s, body: { status: "active", currentDayNo: 1 } });
    think();
    if (item) {
      // 멀리서 출발 — 대중교통 경로(가짜 카카오)를 탄다.
      check(
        req("POST", "/navigation/next-leg", {
          ...s,
          body: { courseId: id, itemId: item, latitude: p.lat + 0.05, longitude: p.lng + 0.03 },
        }),
        { "길찾기 200": (r) => r.status === 200 },
      );
    }
    think();
    req("DELETE", `/courses/${id}`, { ...s });
  });
}

// ── 챗봇(회원) — 대화 한 턴(가짜 Bedrock, LLM 2 번)
export function chat() {
  const s = member();
  if (!s) return;
  group("chat", () => {
    const r = req("POST", "/guide/chat", {
      ...s,
      body: {
        sessionId: uuid(),
        latitude: 37.5796,
        longitude: 126.977,
        messages: [{ role: "user", content: pick(["근처 맛집 알려줘", "도깨비 촬영지 어디야?", "오늘 코스 추천해줘"]) }],
        context: { stops: [] },
      },
    });
    check(r, { "챗봇 200": (res) => res.status === 200 });
  });
}
