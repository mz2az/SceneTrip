// 실제 앱 흐름을 본뜬 부하 시나리오 — v4 (MZ2AZ-502, docs/project/plans/load-test.md §5, 설명서 docs/qa/loadtest/scenario.md).
//
// v2(v1 대비): 토큰 만료 시 /auth/refresh 로 갱신 · 앞 단계가 실패하면 그 흐름을 멈춤 · 둘러보기에 편의시설 상세·사진 ·
// 새 흐름 「후기 읽기」(비회원)·「쓰기」(회원 — 사진 올리기 → 리뷰 또는 여행후기).
// v3(v2 대비): 지도 영역 조회를 앱과 같은 모양으로 — 영역(bbox)과 함께 화면 중심(lat·lng)과 sort=distance 를 보낸다
// (iOS RouteGuide.swift·Android RouteEditorView.kt). v2 는 영역만 보내 서버 기본값인 이름순을 받았다. 나머지는 v2 와 같다.
// v4(v3 대비): 둘러보기에서 상세 화면(작품·편의시설·촬영지)을 열 때마다 앱처럼 사용 기록 POST /events {type: view} 를 보낸다
// (iOS UsageEvents.swift·Android UsageEvents.kt, 계약 「보내고 잊는다」 — 응답을 보지 않는다). 나머지는 v3 와 같다.
//
// 흐름 여섯을 비중대로 섞는다 — 둘러보기 50 · 후기 읽기 8 · 담기 14 · 여행 12 · 챗봇 8 · 쓰기 8(가정한 비율, PRD 실사용자가 쌓이면 맞춘다).
// 사람처럼 단계 사이에 1~3 초 쉬고(생각하는 시간), 작품·촬영지·지도 영역·검색어를 무작위로 골라 캐시 착시를 줄인다.
// 부하는 「초당 몇 명이 새로 들어오나」(도착률, ramping-arrival-rate)로 건다 — 서버가 느려져도 사용자는 계속 들어온다.
//
// 회원 흐름(여행·챗봇)은 미리 만든 시험 회원 토큰(`just load-tokens`)으로 진짜 POST /auth/google 을 탄다 — DEV 가 시험 모드여야
// 한다(`just loadtest-mode dev on`). 길찾기·챗봇의 바깥 호출은 가짜 서버로 간다.
//
// 실행 예:
//   just load-run scenario.js -e BASE_URL=https://api-dev.scenetrip.io/v1 -e TOKENS=/abs/path/tokens.json -e PROFILE=baseline
// PROFILE: smoke(1/s, 1분) · baseline(10/s, 10분) · stress(계단식 5→200/s) · spike(10→200/s 급증) · soak(20/s, 30분)
// RATE_SCALE 로 전체 도착률을 곱한다(기본 1). FLOWS 로 흐름을 고른다(기본 전부).
import http from "k6/http";
import { check, group, sleep } from "k6";
import { SharedArray } from "k6/data";
import encoding from "k6/encoding";

const VERSION = "v4";

const BASE = __ENV.BASE_URL || "https://api-dev.scenetrip.io/v1";
const PROFILE = __ENV.PROFILE || "smoke";
const SCALE = Number(__ENV.RATE_SCALE || "1");

// 시험 회원 — [{sub, idToken, nonce}]. 없으면 회원 흐름을 건너뛴다.
const TOKENS = new SharedArray("tokens", () =>
  __ENV.TOKENS ? JSON.parse(open(__ENV.TOKENS)) : [],
);

// 흐름 비중(합 1).
const WEIGHTS = { browse: 0.5, posts: 0.08, cart: 0.14, trip: 0.12, chat: 0.08, write: 0.08 };

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

// FLOWS=browse,cart,trip 처럼 고르면 그 흐름만 돈다(비중은 그대로 — 뺀 만큼 전체 도착률이 준다). 회원 흐름은 토큰이 있어야 한다.
const wanted = (__ENV.FLOWS || "browse,posts,cart,trip,chat,write").split(",").map((f) => f.trim());
const flows = wanted.filter((f) => WEIGHTS[f] !== undefined && (TOKENS.length > 0 || !["trip", "chat", "write"].includes(f)));

export const options = {
  scenarios: Object.fromEntries(flows.map((f) => [f, scenarioFor(f)])),
  // 합격선(계획 §6) — 바깥이 가짜인 길찾기·챗봇은 가짜 지연(초 단위)이 들어가 따로 본다.
  thresholds: {
    "http_req_failed{flow:browse}": ["rate<0.01"],
    "http_req_duration{flow:browse}": ["p(95)<500"],
    "http_req_duration{flow:cart}": ["p(95)<500"],
    "http_req_failed{flow:trip}": ["rate<0.01"],
    "http_req_failed{flow:chat}": ["rate<0.02"],
    "http_req_failed{flow:posts}": ["rate<0.01"],
    "http_req_duration{flow:posts}": ["p(95)<500"],
    "http_req_failed{flow:write}": ["rate<0.01"],
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

function send(method, path, { body, token, install, name } = {}) {
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

// 회원 요청은 세션(s)을 받아, 액세스 토큰이 만료(401 ACCESS_TOKEN_EXPIRED)면 앱처럼 /auth/refresh 로 갱신하고 한 번 다시 보낸다(v2).
function req(method, path, opts = {}) {
  const s = opts.session;
  const r = send(method, path, { ...opts, token: s ? s.token : opts.token, install: s ? s.install : opts.install });
  if (s && r.status === 401 && String(r.body || "").includes("ACCESS_TOKEN_EXPIRED")) {
    const rr = send("POST", "/auth/refresh", { install: s.install, body: { refreshToken: s.refresh } });
    if (rr.status !== 200) return r;
    s.token = rr.json("accessToken");
    s.refresh = rr.json("refreshToken");
    return send(method, path, { ...opts, token: s.token, install: s.install });
  }
  return r;
}

const ok = (r) => r.status >= 200 && r.status < 300;
const PNG = encoding.b64decode(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQaPgAAAIkAYFvkeRkAAAAAElFTkSuQmCC",
  "std",
);

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
  console.log(`시나리오 ${VERSION} 준비: 작품 ${contents.length} · 촬영지 ${places.length}`);
  return { contents, places, words };
}

// ── 둘러보기(비회원) — 작품 → 상세 → 촬영지 → 검색 → 지도 → (v2) 편의시설 상세·사진 → 촬영지 상세. 실패하면 멈춘다.
export function browse(data) {
  const install = uuid();
  group("browse", () => {
    if (!ok(req("GET", `/contents?limit=20&offset=${Math.floor(Math.random() * 100)}`, { install }))) return;
    think();
    const cid = pick(data.contents);
    if (!check(req("GET", `/contents/${cid}`, { install }), { "작품 상세 200": ok })) return;
    viewed(install, "content", cid);
    if (!ok(req("GET", `/contents/${cid}/places`, { install }))) return;
    think();
    if (!ok(req("GET", `/search/suggestions?q=${encodeURIComponent(pick(data.words))}&limit=8`, { install }))) return;
    think();
    const lng = 126.85 + Math.random() * 0.3;
    const lat = 37.45 + Math.random() * 0.2;
    // 앱처럼 화면 가운데를 기준점으로 — 가운데에서 가까운 순 30 개(v3)
    const map = req("GET", `/pois?bbox=${lng},${lat},${lng + 0.02},${lat + 0.015}&lat=${lat + 0.0075}&lng=${lng + 0.01}&sort=distance&limit=30`, { install });
    if (!check(map, { "지도 200": ok })) return;
    const pois = map.json("items") || [];
    if (pois.length > 0 && Math.random() < 0.7) {
      // 지도에서 핀 하나를 눌러 상세 → 사진(v2)
      think();
      const poi = pick(pois);
      if (!check(req("GET", `/pois/${poi.id}`, { install }), { "편의시설 상세 200": ok })) return;
      viewed(install, "poi", poi.id);
      req("GET", `/pois/${poi.id}/photos?limit=20`, { install });
    }
    think();
    const place = pick(data.places).id;
    if (ok(req("GET", `/places/${place}`, { install }))) viewed(install, "place", place);
  });
}

// 상세 화면이 열렸다는 사용 기록(v4) — 앱처럼 보내고 잊는다(응답·실패를 흐름에 반영하지 않는다).
function viewed(install, entityType, entityId) {
  send("POST", "/events", { install, body: { type: "view", entityType, entityId } });
}

// ── 후기 읽기(비회원, v2) — 여행후기 목록 → 상세
export function posts() {
  const install = uuid();
  group("posts", () => {
    const list = req("GET", `/posts?limit=20`, { install });
    if (!check(list, { "후기 목록 200": ok })) return;
    const items = list.json("items") || [];
    if (items.length === 0) return;
    think();
    req("GET", `/posts/${pick(items).id}`, { install });
  });
}

// ── 담기(비회원) — 장바구니 담기 → 보기 → 빼기, 작품 찜
export function cart(data) {
  const install = uuid();
  group("cart", () => {
    const p = pick(data.places);
    const added = req("POST", "/cart/items", { install, body: { placeId: p.id } });
    if (!check(added, { "담기 201/409": (r) => r.status === 201 || r.status === 409 })) return;
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
  sessions[idx] = { token: r.json("accessToken"), refresh: r.json("refreshToken"), install };
  return sessions[idx];
}

// ── 여행(회원) — 코스 만들기 → 장소 담기 → 시작 → 길찾기(가짜 카카오) → 지우기
export function trip(data) {
  const s = member();
  if (!s) return;
  group("trip", () => {
    const p = pick(data.places);
    const c = req("POST", "/courses", { session: s, body: { dayCount: 1, origin: "self", title: "부하 코스" } });
    if (!check(c, { "코스 201": (r) => r.status === 201 })) return;
    const id = c.json("id");
    think();
    const filled = req("PUT", `/courses/${id}`, {
      session: s,
      body: { title: "부하 코스", days: [{ items: [{ placeId: p.id, dwellMinutes: 30 }] }] },
    });
    if (!ok(filled)) return;
    const item = filled.json("days.0.items.0.id");
    if (!ok(req("PUT", `/courses/${id}/progress`, { session: s, body: { status: "active", currentDayNo: 1 } }))) return;
    think();
    if (item) {
      // 멀리서 출발 — 대중교통 경로(가짜 카카오)를 탄다.
      check(
        req("POST", "/navigation/next-leg", {
          session: s,
          body: { courseId: id, itemId: item, latitude: p.lat + 0.05, longitude: p.lng + 0.03 },
        }),
        { "길찾기 200": (r) => r.status === 200 },
      );
    }
    think();
    req("DELETE", `/courses/${id}`, { session: s });
  });
}

// ── 챗봇(회원) — 대화 한 턴(가짜 Bedrock, LLM 2 번)
export function chat() {
  const s = member();
  if (!s) return;
  group("chat", () => {
    const r = req("POST", "/guide/chat", {
      session: s,
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

// 사진 한 장 올리기 — POST /uploads → 받은 서명 주소로 S3 에 PUT. 성공하면 키(v2).
function upload(s, purpose) {
  const t = req("POST", "/uploads", { session: s, body: { purpose, contentType: "image/png", bytes: PNG.byteLength } });
  if (!ok(t)) return null;
  const put = http.put(t.json("uploadUrl"), PNG, { headers: t.json("requiredHeaders"), tags: { name: "PUT s3 presigned" } });
  return check(put, { "S3 올리기 성공": (r) => r.status >= 200 && r.status < 300 }) ? t.json("key") : null;
}

// ── 쓰기(회원, v2) — 반은 촬영지 리뷰(사진 1 장), 반은 여행후기(사진 1 장). 실험을 거듭해도 DB 가 커지지 않게 여행후기는 지운다.
export function write(data) {
  const s = member();
  if (!s) return;
  group("write", () => {
    if (Math.random() < 0.5) {
      const p = pick(data.places);
      const key = upload(s, "review");
      if (!key) return;
      think();
      const r = req("PUT", `/places/${p.id}/reviews/me`, {
        session: s,
        body: { rating: 1 + Math.floor(Math.random() * 5), body: "부하 시험 리뷰", photoKeys: [key] },
      });
      if (!check(r, { "리뷰 200": ok })) return;
      think();
      req("GET", `/places/${p.id}/reviews?limit=20`, { session: s });
    } else {
      const key = upload(s, "post");
      if (!key) return;
      think();
      const c = req("POST", "/posts", { session: s, body: { title: "부하 시험 후기", body: "부하 시험 본문", photoKeys: [key] } });
      if (!check(c, { "후기 201": (r) => r.status === 201 })) return;
      const id = c.json("id");
      think();
      req("GET", `/posts?limit=20`, { session: s });
      req("GET", `/posts/${id}`, { session: s });
      think();
      req("DELETE", `/posts/${id}`, { session: s });
    }
  });
}

