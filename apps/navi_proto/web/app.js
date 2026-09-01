/* SceneTrip navi — 프로토타입 프론트엔드.
 *
 * 하는 일은 넷이다.
 *   1. 촬영지를 골라 장바구니에 담는다 (앱의 장바구니를 흉내 낸다)
 *   2. 이동 수단(도보/대중교통)을 고르고 담은 순서대로 서버에 물어본다
 *   3. 엔진마다 다른 색으로 지도에 겹쳐 그린다
 *   4. 구간별 상세를 표로 펼친다 — 어디서 어디까지, 무슨 버스, 횡단보도 몇 번
 *
 * 4번이 이 도구의 핵심이다. 총 거리만 보면 어느 엔진이 맞는지 가릴 수 없다.
 *
 * 서버가 보내는 GeoJSON 을 그대로 Leaflet 에 넣는다. 좌표를 만지는 코드가
 * 여기 없다는 점이 중요하다 — 계획서 §6-1 의 "파싱을 없앤다" 가 이것이다.
 */

const state = {
  groups: [], cart: [], engines: [], layers: {}, mode: 'walk', last: null,
  base: 'osm', nmap: null, nlines: [], nmarks: [], naverKey: '',
  tmap: null, tlines: [], tmarks: [], tmapKey: '',
  poiOn: false, poiCat: '전체', poiRows: [], poiTimer: null,
};

const COLOR = {};            // 엔진 색은 서버가 준다
const MODE_COLOR = {         // 대중교통 구간 색
  WALK: '#6b6b76', BUS: '#2f6fd0', SUBWAY: '#43a86b',
  EXPRESSBUS: '#e07b39', TRAIN: '#9b6dd6', FERRY: '#0aa2c0',
};

// ── 지도 ────────────────────────────────────────────────────────────────────
const map = L.map('map', { zoomControl: true }).setView([37.5665, 126.978], 12);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '© OpenStreetMap 기여자',
}).addTo(map);
const pinLayer = L.layerGroup().addTo(map);

/** 지도를 눌러 임의의 핀을 담는다.
 *
 * **세 배경 지도 모두에 붙여야 한다.** 예전에는 Leaflet(OSM)에만 붙어 있어서,
 * 버전 3 에서 OSM 을 없애자 핀을 찍을 방법이 사라졌다. 호텔·공항·터미널 POI 가
 * 들어오기 전까지는 이것이 유일한 시작·끝점 지정 수단이다.
 */
state.pinMode = false;

function setPinMode(on) {
  state.pinMode = on;
  const b = document.getElementById('pin-mode');
  if (b) { b.classList.toggle('on', on); b.textContent = on ? '📍 핀 찍는 중 — 끄기' : '📍 지도에 핀 찍기'; }
  document.body.classList.toggle('pinning', on);
}

function dropPin(lat, lng) {
  // **켜져 있을 때만 찍는다.** 예전에는 아무 때나 찍혀서, 지도를 잡아끌어 옮기려
  // 해도 핀이 생겼다.
  if (!state.pinMode) return;
  if (!isFinite(lat) || !isFinite(lng)) return;
  addToCart({
    id: 'xy:' + lat.toFixed(5) + ',' + lng.toFixed(5),
    name: '지도에서 찍은 지점',
    address: `${lat.toFixed(5)}, ${lng.toFixed(5)}`,
    lat, lng,
  });
  setPinMode(false);       // 하나 찍으면 끈다. 연달아 찍을 일은 드물다
}

map.on('click', (e) => dropPin(e.latlng.lat, e.latlng.lng));

// ── 시작 ────────────────────────────────────────────────────────────────────
async function boot() {
  const [places, eng] = await Promise.all([
    fetch('/api/places').then((r) => r.json()),
    fetch('/api/engines').then((r) => r.json()),
  ]);
  state.groups = places;
  state.engines = eng.engines;
  state.engines.forEach((e) => { COLOR[e.id] = e.color; });
  state.naverKey = eng.naver_client_id || '';
  if (state.naverKey) loadNaver();
  state.tmapKey = eng.tmap_app_key || '';
  if (state.tmapKey) loadTmap();
  state.kakaoKey = eng.kakao_js_key || '';
  if (state.kakaoKey) initKakao(state.kakaoKey);

  const sel = document.getElementById('content-select');
  places.forEach((g, i) => {
    const o = document.createElement('option');
    o.value = String(i);
    o.textContent = `${g.content} — ${g.places.length}곳`;
    sel.appendChild(o);
  });
  sel.addEventListener('change', renderPlaces);
  document.getElementById('place-filter').addEventListener('input', renderPlaces);

  renderEngines();
  initPois();
  if (places.length) { sel.value = '0'; renderPlaces(); }
}

// ── 음식점·카페 ─────────────────────────────────────────────────────────────
// 1만 5천 건을 다 그리면 지도가 죽는다. **지금 보이는 범위 + 업종** 으로 좁혀서
// 서버가 최대 400 건만 준다. 지도를 옮기면 다시 불러온다.

const POI_COLOR = { 음식점: '#e07b39', 카페: '#43a86b' };
const poiLayer = L.layerGroup().addTo(map);

function poiColor(r) {
  return POI_COLOR[r.biz_middle] || '#9b6dd6';
}

/** 지금 **보고 있는** 지도의 범위와 중심. 배경마다 API 가 다르다.
 *
 * 예전에는 Leaflet 것만 읽었다. 버전 3 은 네이버가 기본이라 숨은 Leaflet 의 옛 범위로
 * 조회하게 되어, 칩을 눌러도 **0 건** 이 왔다. 지도를 갈아 끼울 때 늘 놓치는 자리다.
 */
function viewBounds() {
  if (state.base === 'naver' && state.nmap) {
    const b = state.nmap.getBounds(), c = state.nmap.getCenter();
    return { s: b.getSW().lat(), w: b.getSW().lng(),
             n: b.getNE().lat(), e: b.getNE().lng(),
             lat: c.lat(), lng: c.lng() };
  }
  if (state.base === 'kakao' && state.kmap) {
    const b = state.kmap.getBounds(), c = state.kmap.getCenter();
    return { s: b.getSouthWest().getLat(), w: b.getSouthWest().getLng(),
             n: b.getNorthEast().getLat(), e: b.getNorthEast().getLng(),
             lat: c.getLat(), lng: c.getLng() };
  }
  if (state.base === 'tmap' && state.tmap) {
    try {
      const b = state.tmap.getBounds(), c = state.tmap.getCenter();
      const sw = b.getSouthWest ? b.getSouthWest() : b._sw;
      const ne = b.getNorthEast ? b.getNorthEast() : b._ne;
      return { s: sw._lat ?? sw.lat, w: sw._lng ?? sw.lng,
               n: ne._lat ?? ne.lat, e: ne._lng ?? ne.lng,
               lat: c._lat ?? c.lat, lng: c._lng ?? c.lng };
    } catch (e) { /* 아래 Leaflet 로 떨어진다 */ }
  }
  const b = map.getBounds(), c = map.getCenter();
  return { s: b.getSouth(), w: b.getWest(), n: b.getNorth(), e: b.getEast(),
           lat: c.lat, lng: c.lng };
}

async function loadPois() {
  if (!state.poiOn) { clearPoiOverlays(); renderPoiList([]); return; }
  const v = viewBounds();
  const b = { getNorth: () => v.n, getSouth: () => v.s,
              getEast: () => v.e, getWest: () => v.w };
  const c = { lat: v.lat, lng: v.lng };
  // 상세 패널이 열리고 닫히는 순간 지도 높이가 0 이 되어 범위가 한 점으로 붕괴한다.
  // 그때 조회하면 0 건이 와서 음식점이 통째로 사라진다(실측). 잠깐 뒤에 다시 잰다.
  if (b.getNorth() - b.getSouth() < 1e-9 || b.getEast() - b.getWest() < 1e-9) {
    clearTimeout(state.poiTimer);
    state.poiTimer = setTimeout(loadPois, 400);
    return;
  }
  const bbox = [b.getSouth(), b.getWest(), b.getNorth(), b.getEast()].join(',');
  const q = document.getElementById('poi-filter').value.trim();
  // 중심을 함께 보낸다 — 상한에 걸리면 서버가 여기서 가까운 것부터 고른다.
  const url = '/api/pois?' + new URLSearchParams({
    bbox, cat: state.poiCat, group: state.poiGroup, q, limit: 400,
    cy: c.lat, cx: c.lng,
  });
  try {
    const d = await fetch(url).then((r) => r.json());
    state.poiRows = d.pois;
    drawPois(d.pois);
    renderPoiList(d.pois);
    document.getElementById('poi-count').textContent = String(d.count);
    document.getElementById('poi-note').textContent = d.capped
      ? `범위 안에 ${d.total.toLocaleString('ko-KR')}건 — 화면 가운데에서 가까운 `
        + `${d.count}건만 그린다. 더 확대하거나 업종을 좁힌다.`
      : '지금 보이는 지도 범위 안만 불러온다. 지도를 옮기면 다시 불러온다.';
  } catch (e) {
    document.getElementById('poi-note').textContent = '불러오지 못했다: ' + e;
  }
}

// POI 점도 **세 지도 모두에** 그려야 한다. 예전에는 Leaflet 에만 그려서, 버전 3 처럼
// 네이버를 기본으로 두면 칩을 눌러도 지도에 아무것도 안 나왔다.
state.nPois = [];
state.tPois = [];

function clearPoiOverlays() {
  poiLayer.clearLayers();
  (state.nPois || []).forEach((m) => m.setMap(null)); state.nPois = [];
  (state.tPois || []).forEach((m) => m.setMap(null)); state.tPois = [];
  (state.kPois || []).forEach((m) => m.setMap(null)); state.kPois = [];
}

function drawPois(rows) {
  clearPoiOverlays();
  rows.forEach((r) => {
    L.circleMarker([r.lat, r.lng], {
      radius: 5, color: '#fff', weight: 1.5,
      fillColor: poiColor(r), fillOpacity: 0.9,
    })
      .bindTooltip(`${r.name} · ${r.biz_lower || r.biz_middle}`)
      .on('click', () => addPoiToCart(r))
      .addTo(poiLayer);
  });
  if (state.nmap) {
    rows.forEach((r) => {
      const m = new naver.maps.Marker({
        position: new naver.maps.LatLng(r.lat, r.lng), map: state.nmap,
        title: `${r.name} · ${r.biz_lower || r.biz_middle}`,
        icon: { content: `<div class="poi-dot" style="background:${poiColor(r)}"></div>`,
                anchor: new naver.maps.Point(5, 5) },
      });
      naver.maps.Event.addListener(m, 'click', () => addPoiToCart(r));
      state.nPois.push(m);
    });
  }
  if (state.tmap) {
    // TMAP 마커는 아이콘을 바꾸기 번거로워 기본 마커를 쓴다. 수가 많으면 느리므로
    // 화면에 나온 것 중 앞의 200개만 그린다.
    try {
      rows.slice(0, 200).forEach((r) => {
        state.tPois.push(new Tmapv2.Marker({
          position: new Tmapv2.LatLng(r.lat, r.lng), map: state.tmap,
          title: r.name,
        }));
      });
    } catch (e) { console.warn('TMAP POI 표시 실패', e); }
  }
  if (state.kmap) {
    // **빠져 있던 자리다.** 카카오를 배경으로 골라도 여기가 없어 POI 점이
    // 하나도 안 찍혔다 — 8/12 에 정리한 「배경 바꿀 때 손봐야 할 다섯 곳」 목록에
    // POI 마커도 있었는데 카카오맵을 넣을 당시엔 빠뜨렸다.
    try {
      rows.slice(0, 200).forEach((r) => {
        state.kPois = state.kPois || [];
        state.kPois.push(new kakao.maps.Marker({
          position: new kakao.maps.LatLng(r.lat, r.lng), map: state.kmap,
          title: r.name,
        }));
      });
    } catch (e) { console.warn('카카오 POI 표시 실패', e); }
  }
}

/* 「네이버에서 더 보기」. 촬영지는 이미 매칭돼 있는 반면(볼트 자산), TMAP POI
 * 47만 건은 **누를 때 한 건씩** 네이버 비공식 엔드포인트로 찾는다. 미리 다
 * 매칭해 두면 47만 건에 9.6일이 걸리고 그 전에 막힌다(실측) — 그래서 담을
 * 때만 묻는다.
 */
function addNaverMoreLink(container, r) {
  const btn = document.createElement('button');
  btn.className = 'ghost small naver-more';
  btn.textContent = '네이버에서 더 보기';
  btn.addEventListener('click', async (e) => {
    e.stopPropagation();
    btn.disabled = true; btn.textContent = '찾는 중…';
    try {
      const d = await fetch('/api/naver-place', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: r.name, addr: r.addr || r.address,
                               lat: r.lat, lng: r.lng }),
      }).then((res) => res.json());
      if (!d.found) { btn.textContent = '네이버에 없다'; return; }
      window.open(d.url, '_blank');
      btn.textContent = '네이버에서 열림 ↗';
      btn.disabled = false;
    } catch (err) {
      btn.disabled = false; btn.textContent = '다시 시도';
    }
  });
  container.appendChild(btn);
}

function renderPoiList(rows) {
  const ul = document.getElementById('poi-list');
  ul.innerHTML = '';
  rows.slice(0, 120).forEach((r) => {
    const li = document.createElement('li');
    li.innerHTML = '<span class="dotc"></span>'
      + '<span class="nm"><b></b><span class="addr"></span></span>';
    li.querySelector('.dotc').style.background = poiColor(r);
    li.querySelector('b').textContent = r.name;
    li.querySelector('.addr').textContent =
      `${r.biz_lower || r.biz_middle} · ${r.addr}`;
    li.addEventListener('click', () => addPoiToCart(r));
    addNaverMoreLink(li, r);
    ul.appendChild(li);
  });
}

/* ── 전국 장소 검색 (v5) ─────────────────────────────────────────────────
 *
 * 화면에 보이는 범위와 무관하게 47만 건 전체에서 이름으로 찾는다. `bbox` 를
 * 안 넘기면 서버가 전국을 훑는다 — 이미 되던 기능인데 화면에는 「지금 보이는
 * 범위 안만」 짜여 있는 목록(#poi-list)밖에 없었다.
 *
 * 결과를 누르면 지도를 그 자리로 옮기고 **딱 하나만 강조 표시**한다.
 * 「TMAP 좌표가 실제로 어디를 가리키는지」를 눈으로 직접 대조하기 위해서다 —
 * 배경 지도(네이버) 위에 놓인 점이 그 지도가 그리는 건물·역 아이콘과 겹치는지를
 * 직접 봐야 「데이터가 틀렸다」 와 「배경 지도와 조금 어긋난다」 를 가를 수 있다.
 */
let poiSearchTimer = null;
let poiSearchMark = null;   // 강조 마커 — 배경마다 다른 객체라 하나만 기억해 지운다

async function poiSearch(q) {
  const ul = document.getElementById('poi-search-list');
  if (!q.trim()) { ul.innerHTML = ''; return; }
  const d = await fetch('/api/pois?' + new URLSearchParams({ q, limit: 20 }))
    .then((r) => r.json());
  ul.innerHTML = '';
  if (!d.pois || !d.pois.length) {
    ul.innerHTML = '<li class="hint">없다</li>';
    return;
  }
  d.pois.forEach((r) => {
    const li = document.createElement('li');
    li.innerHTML = '<span class="dotc"></span>'
      + '<span class="nm"><b></b><span class="addr"></span></span>';
    li.querySelector('.dotc').style.background = poiColor(r);
    li.querySelector('b').textContent = r.name;
    li.querySelector('.addr').textContent =
      `${r.biz_lower || r.biz_middle} · ${r.addr} · ${r.lat.toFixed(5)}, ${r.lng.toFixed(5)}`;
    li.addEventListener('click', () => poiSearchGoto(r));
    addNaverMoreLink(li, r);
    ul.appendChild(li);
  });
}

async function poiSearchGoto(r) {
  await precisePoi(r);       // 47만 건이 도로 진입점으로 저장돼 있다 — 볼 때마다 바로잡는다
  // 배경마다 지도 객체가 다르다 — 여기서 안 갈라 주면 세 곳 중 하나만 움직인다
  // (8/12 에 겪은 다섯 가지 「배경 바꿀 때 빠뜨리는 자리」 와 같은 함정).
  const ll = [r.lat, r.lng];
  if (state.base === 'naver' && state.nmap) {
    state.nmap.setCenter(new naver.maps.LatLng(...ll)); state.nmap.setZoom(17);
  } else if (state.base === 'kakao' && state.kmap) {
    state.kmap.setCenter(new kakao.maps.LatLng(...ll)); state.kmap.setLevel(3);
  } else if (state.base === 'tmap' && state.tmap) {
    state.tmap.setCenter(new Tmapv2.LatLng(...ll)); state.tmap.setZoom(17);
  } else {
    map.setView(ll, 17);
  }

  if (poiSearchMark) { try { poiSearchMark.remove?.(); poiSearchMark.setMap?.(null); } catch (e) {} }
  if (state.base === 'naver' && state.nmap) {
    poiSearchMark = new naver.maps.Marker({
      position: new naver.maps.LatLng(...ll), map: state.nmap,
      icon: { content: '<div class="poi-hit"></div>', anchor: new naver.maps.Point(9, 9) },
    });
  } else if (state.base === 'kakao' && state.kmap) {
    poiSearchMark = new kakao.maps.Marker({ position: new kakao.maps.LatLng(...ll), map: state.kmap });
  } else if (state.base === 'tmap' && state.tmap) {
    poiSearchMark = new Tmapv2.Marker({ position: new Tmapv2.LatLng(...ll), map: state.tmap });
  } else {
    poiSearchMark = L.circleMarker(ll, { radius: 8, color: '#e0367a', weight: 3, fillOpacity: 0 }).addTo(map);
  }
}

function setupPoiSearch() {
  const inp = document.getElementById('poi-search-in');
  if (!inp) return;
  inp.addEventListener('input', () => {
    clearTimeout(poiSearchTimer);
    poiSearchTimer = setTimeout(() => poiSearch(inp.value), 250);
  });
}

/* 2026-08-24 발견 — 47만 건 전체가 도로 진입점(frontLat)으로 저장돼 있다.
 * 건물 좌표(noorLat)로 다시 훑으려면 격자 재수집이 필요하고 그건 약 2일
 * 걸린다. 그동안 **실제로 담는 곳만** 그 자리에서 바로잡는다. */
async function precisePoi(r) {
  try {
    const d = await fetch('/api/poi-precise', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: r.id, name: r.name, lat: r.lat, lng: r.lng }),
    }).then((res) => res.json());
    if (d.corrected) { r.lat = d.lat; r.lng = d.lng; }
    return d;
  } catch (e) { return { corrected: false }; }
}

async function addPoiToCart(r) {
  await precisePoi(r);       // 담기 전에 건물 좌표로 보정한다
  addToCart({
    id: 'poi:' + r.id, name: r.name,
    address: `${r.biz_lower || r.biz_middle} · ${r.addr}`,
    lat: r.lat, lng: r.lng, kind: 'poi', biz: r.biz_middle,
  });
}

// 업종이 수십 가지라 그대로 늘어놓으면 고를 수가 없다. **큰 갈래를 먼저 고르고**
// 그 안에서 좁힌다. 갈래는 음식·숙박·명소·교통 넷이다.
state.poiGroup = '전체';

async function initPois() {
  const d = await fetch('/api/poi-categories').then((r) => r.json());
  const gwrap = document.getElementById('poi-groups');
  const wrap = document.getElementById('poi-cats');

  function paintSubs() {
    wrap.innerHTML = '';
    const g = d.categories.find((x) => x.group === state.poiGroup);
    const subs = g ? g.cats : [];
    if (!subs.length) return;
    const all = document.createElement('span');
    all.className = 'chip' + (state.poiCat === '전체' ? ' on' : '');
    all.textContent = `전체 ${g.count.toLocaleString('ko-KR')}`;
    all.addEventListener('click', () => { state.poiCat = '전체'; paintSubs(); loadPois(); });
    wrap.appendChild(all);
    subs.forEach((c) => {
      const el = document.createElement('span');
      el.className = 'chip' + (c.name === state.poiCat ? ' on' : '');
      el.textContent = `${c.name} ${c.count.toLocaleString('ko-KR')}`;
      el.addEventListener('click', () => { state.poiCat = c.name; paintSubs(); loadPois(); });
      wrap.appendChild(el);
    });
  }

  gwrap.innerHTML = '';
  const total = d.categories.reduce((s2, g) => s2 + g.count, 0);
  const mkGroup = (name, icon, count) => {
    const el = document.createElement('span');
    el.className = 'chip group' + (name === state.poiGroup ? ' on' : '');
    el.textContent = `${icon} ${name} ${count.toLocaleString('ko-KR')}`.trim();
    el.addEventListener('click', () => {
      state.poiGroup = name; state.poiCat = '전체';
      [...gwrap.children].forEach((x) => x.classList.remove('on'));
      el.classList.add('on');
      paintSubs(); loadPois();
    });
    gwrap.appendChild(el);
  };
  mkGroup('전체', '', total);
  d.categories.forEach((g) => mkGroup(g.group, g.icon, g.count));
  paintSubs();

  document.querySelectorAll('#poi-toggle button').forEach((b) => {
    b.addEventListener('click', () => {
      document.querySelectorAll('#poi-toggle button')
        .forEach((x) => x.classList.remove('on'));
      b.classList.add('on');
      state.poiOn = b.dataset.poi === 'on';
      loadPois();
    });
  });

  document.getElementById('poi-filter').addEventListener('input', () => {
    clearTimeout(state.poiTimer);
    state.poiTimer = setTimeout(loadPois, 300);
  });

  // 지도를 옮기면 다시 불러온다. 연달아 움직일 때 매번 부르지 않게 늦춘다.
  map.on('moveend', () => {
    clearTimeout(state.poiTimer);
    state.poiTimer = setTimeout(loadPois, 350);
  });
}

// ── 장소 목록 ───────────────────────────────────────────────────────────────
function currentPlaces() {
  const i = document.getElementById('content-select').value;
  if (i === '') return [];
  const q = document.getElementById('place-filter').value.trim();
  const list = state.groups[Number(i)].places;
  return q ? list.filter((p) => p.name.includes(q)) : list;
}

function renderPlaces() {
  const ul = document.getElementById('place-list');
  ul.innerHTML = '';
  const inCart = new Set(state.cart.map((p) => p.id));
  currentPlaces().forEach((p) => {
    const li = document.createElement('li');
    if (inCart.has(p.id)) li.className = 'on';
    li.innerHTML = '<span class="nm"><b></b><span class="addr"></span></span>';
    li.querySelector('b').textContent = p.name;
    li.querySelector('.addr').textContent = p.address || '';
    li.addEventListener('click', () => addToCart({ ...p, kind: 'spot' }));
    ul.appendChild(li);
  });
}

// ── 장바구니 ────────────────────────────────────────────────────────────────
function addToCart(p) {
  if (state.cart.some((x) => x.id === p.id)) return;
  state.cart.push(p);
  renderCart(); renderPlaces();
}

function renderCart() {
  const ol = document.getElementById('cart');
  ol.innerHTML = '';
  state.cart.forEach((p, i) => {
    const li = document.createElement('li');
    const icon = p.kind === 'poi'
      ? (p.biz === '카페' ? '☕' : '🍽️') : '🎬';
    li.draggable = true;
    li.dataset.i = String(i);
    li.innerHTML = '<span class="grip" title="잡아 끌어 순서를 바꾼다">⠿</span>'
      + `<span class="idx">${i + 1}</span>`
      + `<span class="kind" title="${p.kind === 'poi' ? '음식점·카페' : '촬영지'}">${icon}</span>`
      + '<span class="nm"><b></b><span class="addr"></span></span>'
      + '<button class="rm" title="빼기">×</button>';
    wireDrag(li);
    li.querySelector('b').textContent = p.name;
    li.querySelector('.addr').textContent = p.address || '';
    li.querySelector('.rm').addEventListener('click', () => {
      state.cart.splice(i, 1); renderCart(); renderPlaces();
    });
    ol.appendChild(li);
  });

  document.getElementById('cart-count').textContent = String(state.cart.length);
  document.getElementById('go').disabled = state.cart.length < 2;
  if (typeof paintTmapCost === 'function' && V.has('engine_pick')) paintTmapCost();
  if (typeof checkSavedKey === 'function' && V.has('engine_compare')) {
    checkSavedKey(); paintEngineTabs();
  }
  // 여행 전이면 직선을 다시 그린다. 담고 빼고 순서를 바꿀 때마다 보여야 한다.
  if (typeof drawPlanLine === 'function' && state.trip === 'before' && V.has('plan_mode')) {
    drawPlanLine();
  }
  document.getElementById('compare-box').hidden = V.has('score') || state.cart.length < 2;

  pinLayer.clearLayers();
  state.cart.forEach((p, i) => {
    L.marker([p.lat, p.lng], {
      icon: L.divIcon({ className: '', html: `<div class="pin-label">${i + 1}</div>`,
                        iconSize: [24, 24], iconAnchor: [12, 12] }),
    }).bindTooltip(p.name).addTo(pinLayer);
  });
  // 보고 있는 지도의 범위를 맞춘다. 예전에는 Leaflet 만 맞춰서, 네이버를 보는
  // 동안 핀을 담아도 화면이 따라가지 않았다.
  if (state.cart.length) {
    if (state.base === 'osm') {
      map.fitBounds(L.latLngBounds(state.cart.map((p) => [p.lat, p.lng])),
                    { padding: [60, 60], maxZoom: 16 });
    } else if (typeof fitPlan === 'function') {
      fitPlan();
    }
  }
  if (state.base === 'naver') drawOnNaver();
  if (state.base === 'tmap') drawOnTmap();
}

// ── 장바구니 순서 바꾸기 ────────────────────────────────────────────────────
// 자동 최적화가 있어도 손으로 옮길 수 있어야 한다. "밥은 중간에 먹고 싶다" 같은
// 뜻은 최단 거리로 표현되지 않기 때문이다.

let dragFrom = null;

function wireDrag(li) {
  li.addEventListener('dragstart', (e) => {
    dragFrom = Number(li.dataset.i);
    li.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    // 파이어폭스는 데이터가 없으면 드래그를 시작하지 않는다
    e.dataTransfer.setData('text/plain', String(dragFrom));
  });
  li.addEventListener('dragend', () => {
    li.classList.remove('dragging');
    document.querySelectorAll('#cart li').forEach((x) => x.classList.remove('over'));
  });
  li.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    li.classList.add('over');
  });
  li.addEventListener('dragleave', () => li.classList.remove('over'));
  li.addEventListener('drop', (e) => {
    e.preventDefault();
    const to = Number(li.dataset.i);
    if (dragFrom === null || dragFrom === to) return;
    const [moved] = state.cart.splice(dragFrom, 1);
    state.cart.splice(to, 0, moved);
    dragFrom = null;
    renderCart(); renderPlaces();
  });
}

// ── 순서 최적화 ─────────────────────────────────────────────────────────────
document.getElementById('optimize').addEventListener('click', async () => {
  if (state.cart.length < 3) {
    document.getElementById('cart-hint').textContent =
      '지점이 셋 이상이어야 순서를 바꿀 수 있다.';
    return;
  }
  const btn = document.getElementById('optimize');
  const old = btn.textContent;
  btn.disabled = true; btn.textContent = '계산 중…';
  try {
    const d = await fetch('/api/optimize', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        points: state.cart.map((p) => [p.lat, p.lng]),
        fixed_start: document.getElementById('fix-start').checked,
      }),
    }).then((r) => r.json());
    state.cart = d.order.map((i) => state.cart[i]);
    renderCart(); renderPlaces();
    const cut = d.before ? Math.round(100 * (1 - d.after / d.before)) : 0;
    document.getElementById('cart-hint').textContent =
      cut > 0
        ? `순서를 바꿔 ${cut}% 줄였다 (${d.source}). 행을 잡아 끌어 다시 바꿀 수 있다.`
        : `이미 가장 짧은 순서다 (${d.source}).`;
  } catch (e) {
    document.getElementById('cart-hint').textContent = '최적화 실패: ' + e;
  } finally {
    btn.disabled = false; btn.textContent = old;
  }
});

document.getElementById('clear').addEventListener('click', () => {
  state.cart = []; clearRoutes(); renderCart(); renderPlaces();
  ['result-box', 'geojson-box', 'detail'].forEach(
    (id) => { document.getElementById(id).hidden = true; });
});
document.getElementById('reverse').addEventListener('click', () => {
  state.cart.reverse(); renderCart();
});

// ── 이동 수단 ───────────────────────────────────────────────────────────────
document.querySelectorAll('#mode-seg button').forEach((b) => {
  b.addEventListener('click', () => {
    document.querySelectorAll('#mode-seg button').forEach((x) => x.classList.remove('on'));
    b.classList.add('on');
    state.mode = b.dataset.mode;
    renderEngines();
  });
});

// ── 임계값 ──────────────────────────────────────────────────────────────────
const thR = document.getElementById('threshold');
const thN = document.getElementById('threshold-num');
thR.addEventListener('input', () => { thN.value = thR.value; });
thN.addEventListener('input', () => {
  const v = Number(thN.value);
  if (v >= Number(thR.min) && v <= Number(thR.max)) thR.value = v;
});

// ── 엔진 목록 ───────────────────────────────────────────────────────────────
function renderEngines() {
  const ul = document.getElementById('engines');
  const usable = state.mode === 'auto'
    ? state.engines
    : state.engines.filter((e) => e.modes.includes(state.mode));
  ul.innerHTML = '';
  // 자동 모드에서는 확정 조합(도보 TMAP · 대중교통 ODsay)을 미리 켜 둔다.
  const auto = state.mode === 'auto'
    ? new Set(['tmap', 'odsay'])
    : new Set([ (usable.find((x) => x.ready) || {}).id ]);
  usable.forEach((e, i) => {
    const li = document.createElement('li');
    if (!e.ready) li.className = 'off';
    li.innerHTML = `<span class="swatch" style="background:${e.color}"></span>`
      + `<input type="checkbox" id="e-${e.id}" value="${e.id}" `
      + `${e.ready ? '' : 'disabled'} ${e.ready && auto.has(e.id) ? 'checked' : ''}>`
      + `<label for="e-${e.id}"><b></b><span class="sub"></span></label>`;
    li.querySelector('b').textContent = e.label;
    li.querySelector('.sub').textContent = `${e.data} · ${e.note}`;
    ul.appendChild(li);
  });

  // 페리 옵션은 도보에서만 뜻이 있다. 대중교통은 배가 정식 수단이다.
  document.getElementById('ferry-opt').style.display =
    state.mode === 'walk' ? '' : 'none';
  // ODsay 옵션은 대중교통에서 ODsay 를 쓸 수 있을 때만 보인다.
  const odsayReady = state.engines.some((e) => e.id === 'odsay' && e.ready);
  document.getElementById('auto-opt').hidden = state.mode !== 'auto';
  const showOdsay = (state.mode === 'transit' || state.mode === 'auto') && odsayReady;
  document.getElementById('odsay-opt').hidden = !showOdsay;
  document.getElementById('odsay-walk-opt').hidden = !showOdsay;
  // 지금 켤 수 없는 도보 엔진은 고르지 못하게 한다
  const walkable = new Set(state.engines.filter((e) => e.ready
    && e.modes.includes('walk')).map((e) => e.id));
  [...document.querySelectorAll('#odsay-walk option')].forEach((o) => {
    o.disabled = o.value !== '' && !walkable.has(o.value);
  });
  document.getElementById('mode-note').textContent = {
    walk: '엔진 넷이 같은 구간을 각자 계산한다. 겹쳐 그려 눈으로 비교한다.',
    transit: '대중교통은 시간대에 따라 답이 달라져 캐시하지 않는다. 매번 새로 묻는다.',
    auto: '구간마다 도보와 대중교통을 갈라 계산한다. 도보 엔진과 대중교통 엔진을 하나씩 골라 둔다.',
  }[state.mode];
}

function selectedEngines() {
  return [...document.querySelectorAll('#engines input:checked')].map((i) => i.value);
}

// ── 경로 요청 ───────────────────────────────────────────────────────────────
function clearRoutes() {
  Object.values(state.layers).forEach((l) => map.removeLayer(l));
  state.layers = {};
}

document.getElementById('go').addEventListener('click', async () => {
  const engines = selectedEngines();
  if (!engines.length) { alert('엔진을 하나 이상 고른다'); return; }

  const btn = document.getElementById('go');
  btn.disabled = true; btn.textContent = '계산 중…';
  clearRoutes();
  try {
    const res = await fetch('/api/route', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        engines, mode: state.mode,
        allow_ferry: document.getElementById('allow-ferry').checked,
        odsay_shape: document.getElementById('odsay-shape').checked,
        odsay_walk: document.getElementById('odsay-walk').value,
        threshold_m: Number(document.getElementById('threshold-num').value) || 800,
        points: state.cart.map((p) => [p.lat, p.lng]),
        names: state.cart.map((p) => p.name),
      }),
    }).then((r) => r.json());
    state.last = res;
    draw(res);
    renderDetail(res);
    if (state.base === 'naver') drawOnNaver();
    if (state.base === 'tmap') drawOnTmap();
  } catch (err) {
    alert('요청이 실패했다: ' + err);
  } finally {
    btn.disabled = false; btn.textContent = '경로 찾기';
  }
});

const km = (m) => (m / 1000).toFixed(2) + ' km';
const min = (s) => Math.round(s / 60) + ' 분';
const won = (n) => n == null ? '' : n.toLocaleString('ko-KR') + ' 원';

// ── 요약 표 + 지도 ──────────────────────────────────────────────────────────
function draw(res) {
  const tbl = document.getElementById('results');
  const isTransit = res.mode === 'transit';
  tbl.innerHTML = '<tr><th>엔진</th><th>거리</th><th>시간</th>'
    + (isTransit ? '<th>요금</th>' : '<th>점</th>') + '<th>응답</th></tr>';

  let firstOk = null;
  res.results.forEach((r) => {
    const tr = document.createElement('tr');
    if (!r.ok) {
      tr.innerHTML = `<td><span class="dot" style="background:${COLOR[r.engine]}"></span>`
        + `${r.engine}</td><td class="err" colspan="4"></td>`;
      tr.querySelector('.err').textContent = r.error;
      tbl.appendChild(tr);
      return;
    }
    if (!firstOk) firstOk = r;

    // 서버가 준 GeoJSON 을 그대로 넣는다. 좌표를 다시 만들지 않는다.
    if (r.geojson.features.length) {
      const layer = L.geoJSON(r.geojson, {
        style: { color: COLOR[r.engine], weight: 5, opacity: 0.75 },
        onEachFeature: (f, l) => {
          const p = f.properties;
          l.bindTooltip(`${r.engine} · ${p.from} → ${p.to} · ${p.distance_m} m`);
        },
      }).addTo(map);
      state.layers[r.engine] = layer;
    }

    tr.innerHTML = `<td><span class="dot" style="background:${COLOR[r.engine]}"></span>`
      + `${r.engine}</td><td>${km(r.distance_m)}</td><td>${min(r.duration_s)}</td>`
      + `<td>${isTransit ? won(r.fare_krw) : r.point_count}</td>`
      + `<td>${r.elapsed_ms} ms</td>`;
    tbl.appendChild(tr);
  });

  if (!V.has('score')) document.getElementById('result-box').hidden = false;

  // 페리처럼 걸어서 갈 수 없는 구간이 섞였으면 조용히 넘기지 않는다.
  const warns = res.results.flatMap((r) => (r.ok ? r.warnings || [] : []));
  const box = document.getElementById('warn-box');
  box.hidden = !warns.length;
  if (warns.length) {
    box.innerHTML = '<b>⚠ 걸어서 갈 수 없는 구간이 섞였다</b>';
    warns.forEach((w) => {
      const d = document.createElement('div'); d.textContent = '· ' + w; box.appendChild(d);
    });
  }

  const c = res.cache || {}; const total = (c.hit || 0) + (c.miss || 0);
  document.getElementById('cache-note').textContent = total
    ? `캐시 — 재사용 ${c.hit} / 새로 계산 ${c.miss} (적중률 ${Math.round(100 * c.hit / total)}%)`
    : '';

  if (firstOk && firstOk.geojson.features.length) {
    const txt = JSON.stringify(firstOk.geojson, null, 1);
    document.getElementById('geojson').textContent = txt;
    document.getElementById('geojson-size').textContent =
      `${(txt.length / 1024).toFixed(1)} KB · 구간 ${firstOk.leg_count}개`;
    if (!V.has('score')) document.getElementById('geojson-box').hidden = false;
    map.fitBounds(state.layers[firstOk.engine].getBounds(), { padding: [40, 40] });
  }
}

// ── 구간별 상세 ─────────────────────────────────────────────────────────────
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function chips(parent, obj, cls) {
  const keys = Object.keys(obj || {});
  if (!keys.length) return;
  const wrap = el('div', 'chips');
  keys.forEach((k) => wrap.appendChild(el('span', 'chip ' + (cls || ''), `${k} ${obj[k]}`)));
  parent.appendChild(wrap);
}

function walkTable(steps) {
  if (!steps.length) return el('div', 'none', '이 엔진은 구간별 안내를 주지 않는다.');
  const t = el('table', 'steps');
  t.innerHTML = '<tr><th>거리</th><th>시간</th><th>시설</th><th>도로</th><th>안내</th></tr>';
  steps.forEach((s) => {
    const tr = el('tr');
    if ((s.text || '').includes('횡단보도')) tr.className = 'cross';
    tr.innerHTML = `<td class="n">${s.distance_m} m</td>`
      + `<td class="n">${s.duration_s ? s.duration_s + ' s' : ''}</td>`
      + '<td class="fac"></td><td></td><td></td>';
    tr.children[2].textContent = s.facility || '';
    tr.children[3].textContent = s.road || '';
    tr.children[4].textContent = s.text || '';
    t.appendChild(tr);
  });
  return t;
}

function transitBlock(tr) {
  const wrap = el('div');
  const head = el('div', 'chips');
  if (tr.fare_krw != null) head.appendChild(el('span', 'chip', '요금 ' + won(tr.fare_krw)));
  if (tr.transfer_count != null) head.appendChild(el('span', 'chip', `환승 ${tr.transfer_count}회`));
  if (tr.walk_distance_m != null) head.appendChild(el('span', 'chip', `도보 ${tr.walk_distance_m} m`));
  wrap.appendChild(head);

  tr.legs.forEach((g) => {
    const d = el('div', 'tleg');
    d.style.borderLeftColor = MODE_COLOR[g.mode] || '#8a8a96';
    const top = el('div', 'top');
    const b = el('span', 'badge2', g.mode_ko);
    b.style.background = MODE_COLOR[g.mode] || '#8a8a96';
    top.appendChild(b);
    top.appendChild(el('span', 'od', `${g.from} → ${g.to}`));
    top.appendChild(el('span', 'n', `${g.distance_m} m · ${min(g.duration_s)}`));
    d.appendChild(top);

    if (g.route) {
      const r = el('div', 'stops', '노선 ' + g.route);
      d.appendChild(r);
    }
    if (g.stops && g.stops.length > 1) {
      d.appendChild(el('div', 'stops',
        `정류장 ${g.stop_count}개 — ` + g.stops.join(' · ')));
    }
    if (g.walk_steps && g.walk_steps.length) {
      const ul = el('ul');
      g.walk_steps.forEach((w) => ul.appendChild(el('li', null, `${w.distance_m} m · ${w.text}`)));
      d.appendChild(ul);
    }
    wrap.appendChild(d);
  });
  return wrap;
}

function renderDetail(res) {
  const body = document.getElementById('detail-body');
  body.innerHTML = '';

  res.results.forEach((r) => {
    const block = el('div', 'eng-block');
    const head = el('div', 'eng-head');
    const sw = el('span', 'sw'); sw.style.background = COLOR[r.engine] || '#999';
    head.appendChild(sw);
    head.appendChild(el('b', null, r.engine));
    if (r.ok) {
      const meta = el('div', 'meta');
      meta.textContent = `${km(r.distance_m)} · ${min(r.duration_s)}`
        + (r.fare_krw != null ? ` · ${won(r.fare_krw)}` : '');
      head.appendChild(meta);
    }
    block.appendChild(head);

    const bd = el('div', 'eng-body');
    if (!r.ok) {
      bd.appendChild(el('div', 'none', '실패 — ' + r.error));
    } else {
      chips(bd, r.facilities, 'f');
      if (!Object.keys(r.facilities || {}).length && r.mode === 'walk') {
        bd.appendChild(el('div', 'none',
          '이 엔진은 횡단보도·계단 같은 시설 정보를 주지 않는다.'));
      }
      r.legs.forEach((l) => {
        const t = el('div', 'leg-title');
        t.textContent = `${l.index + 1}. ${l.from} → ${l.to} `;
        t.appendChild(el('span', 'sub', `${l.distance_m} m · ${min(l.duration_s)}`));
        bd.appendChild(t);
        if (l.note) { bd.appendChild(el('div', 'none', l.note)); return; }
        bd.appendChild(l.transit ? transitBlock(l.transit) : walkTable(l.steps || []));
      });
    }
    block.appendChild(bd);
    body.appendChild(block);
  });

  document.getElementById('detail').hidden = false;
  setTimeout(resizeMaps, 60);
}

document.getElementById('detail-close').addEventListener('click', () => {
  document.getElementById('detail').hidden = true;
  setTimeout(resizeMaps, 60);
});

function resizeMaps() {
  map.invalidateSize();
  if (state.poiOn) {
    clearTimeout(state.poiTimer);
    state.poiTimer = setTimeout(loadPois, 250);
  }
  const box = document.getElementById('maps').getBoundingClientRect();
  if (state.nmap) state.nmap.setSize(new naver.maps.Size(box.width, box.height));
  if (state.tmap) state.tmap.resize(box.width, box.height);
}
window.addEventListener('resize', () => setTimeout(resizeMaps, 100));

// ── 눈으로 비교 ─────────────────────────────────────────────────────────────
document.getElementById('naver').addEventListener('click', () => {
  const a = state.cart[0]; const b = state.cart[state.cart.length - 1];
  const enc = (p) => `${p.lng},${p.lat},${encodeURIComponent(p.name)},,`;
  const kind = state.mode === 'transit' ? 'transit' : 'walk';
  window.open(`https://map.naver.com/p/directions/${enc(a)}/${enc(b)}/-/${kind}`,
              '_blank', 'noopener');
});

document.getElementById('copy-coords').addEventListener('click', () => {
  const t = state.cart.map((p, i) => `${i + 1}. ${p.name}\t${p.lat}, ${p.lng}`).join('\n');
  navigator.clipboard.writeText(t).then(() => flash('copy-coords', '복사했다'));
});
document.getElementById('copy-geojson').addEventListener('click', () => {
  navigator.clipboard.writeText(document.getElementById('geojson').textContent)
    .then(() => flash('copy-geojson', '복사했다'));
});

function flash(id, msg) {
  const b = document.getElementById(id); const old = b.textContent;
  b.textContent = msg; setTimeout(() => { b.textContent = old; }, 1200);
}

// ── 네이버 배경 지도 ────────────────────────────────────────────────────────
// Leaflet 은 네이버 타일을 그리지 못한다(공개 타일 URL 이 없다). 그래서 네이버는
// 자기 JS 지도를 따로 띄우고, 같은 좌표열을 양쪽에 그린 뒤 **번갈아 보며** 비교한다.
// 나란히 놓는 것보다 같은 자리에서 깜빡이며 바꾸는 편이 어긋남을 더 잘 잡아낸다.

function loadNaver() {
  const s = document.createElement('script');
  s.src = 'https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=' + state.naverKey;
  s.onload = () => {
    const c = map.getCenter();
    state.nmap = new naver.maps.Map('navermap', {
      center: new naver.maps.LatLng(c.lat, c.lng),
      zoom: map.getZoom(), scaleControl: true,
    });
    naver.maps.Event.addListener(state.nmap, 'click', (e) => {
      dropPin(e.coord.lat(), e.coord.lng());
    });
    // 지도를 옮기면 그 범위로 다시 불러온다. Leaflet 에만 걸려 있던 것이다.
    naver.maps.Event.addListener(state.nmap, 'idle', () => {
      clearTimeout(state.poiTimer);
      state.poiTimer = setTimeout(loadPois, 350);
    });
    document.getElementById('btn-naver').disabled = false;
  };
  s.onerror = () => { document.getElementById('btn-naver').title = '스크립트를 못 받았다'; };
  window.navermap_authFailure = () => {
    const b = document.getElementById('btn-naver');
    b.disabled = true;
    b.title = '이 키로는 이 주소에서 네이버 지도를 못 연다 (NCP 콘솔의 Web 서비스 URL 확인)';
  };
  document.head.appendChild(s);
}

function clearNaverOverlays() {
  state.nlines.forEach((l) => l.setMap(null));
  state.nmarks.forEach((m) => m.setMap(null));
  state.nlines = []; state.nmarks = [];
}

function drawOnNaver() {
  if (!state.nmap) return;
  clearNaverOverlays();
  state.cart.forEach((p, i) => {
    state.nmarks.push(new naver.maps.Marker({
      position: new naver.maps.LatLng(p.lat, p.lng), map: state.nmap,
      icon: { content: `<div class="pin-label">${i + 1}</div>`,
              anchor: new naver.maps.Point(12, 12) },
    }));
  });
  if (!state.last) return;
  state.last.results.forEach((r) => {
    if (!r.ok) return;
    r.geojson.features.forEach((f) => {
      // GeoJSON 은 [경도, 위도] 순이다. 네이버는 LatLng(위도, 경도) 를 받는다.
      const path = f.geometry.coordinates.map((c) => new naver.maps.LatLng(c[1], c[0]));
      state.nlines.push(new naver.maps.Polyline({
        map: state.nmap, path, strokeColor: COLOR[r.engine],
        strokeWeight: 5, strokeOpacity: 0.75,
      }));
    });
  });
}

// ── TMAP 배경 지도 ──────────────────────────────────────────────────────────
// 네이버와 같은 이유로 별도 지도 인스턴스를 띄운다. TMAP 의 지도보기 API 그룹은
// Free 에서 하루 10 만 건이라 배경으로 쓰기에 넉넉하다.

function loadTmap() {
  // SDK 는 index.html 이 정적으로 이미 불러왔다. 다만 로더가 document.write 로 진짜
  // 파일을 한 번 더 받아오므로 Tmapv2.Map 이 생길 때까지 잠깐 기다려야 한다.
  const btn = document.getElementById('btn-tmap');
  let tries = 0;
  const timer = setInterval(() => {
    tries += 1;
    if (typeof Tmapv2 !== 'undefined' && Tmapv2.Map) {
      clearInterval(timer);
      const c = map.getCenter();
      state.tmap = new Tmapv2.Map('tmapmap', {
        center: new Tmapv2.LatLng(c.lat, c.lng),
        zoom: map.getZoom(), width: '100%', height: '100%', httpsMode: true,
      });
      // Tmapv2 는 이벤트 객체 모양이 문서마다 달라 세 자리를 다 본다.
      try {
        state.tmap.addListener('click', (e) => {
          const ll = e.latLng || e.lonlat || e;
          const lat = ll._lat ?? ll.lat ?? (typeof ll.getLat === 'function' ? ll.getLat() : null);
          const lng = ll._lng ?? ll.lng ?? (typeof ll.getLng === 'function' ? ll.getLng() : null);
          if (lat != null && lng != null) dropPin(+lat, +lng);
        });
      } catch (err) { console.warn('TMAP 클릭 핸들러 실패', err); }
      btn.disabled = false;
    } else if (tries > 60) {          // 약 15초
      clearInterval(timer);
      btn.title = 'TMAP SDK 가 로드되지 않았다 (콘솔 확인)';
    }
  }, 250);
}

function clearTmapOverlays() {
  state.tlines.forEach((l) => l.setMap(null));
  state.tmarks.forEach((m) => m.setMap(null));
  state.tlines = []; state.tmarks = [];
}

function drawOnTmap() {
  if (!state.tmap) return;
  clearTmapOverlays();
  state.cart.forEach((p) => {
    state.tmarks.push(new Tmapv2.Marker({
      position: new Tmapv2.LatLng(p.lat, p.lng), map: state.tmap,
    }));
  });
  if (!state.last) return;
  state.last.results.forEach((r) => {
    if (!r.ok) return;
    r.geojson.features.forEach((f) => {
      // GeoJSON 은 [경도, 위도] 순. Tmapv2.LatLng 는 (위도, 경도) 를 받는다.
      const path = f.geometry.coordinates.map((c) => new Tmapv2.LatLng(c[1], c[0]));
      state.tlines.push(new Tmapv2.Polyline({
        path, strokeColor: COLOR[r.engine] || '#e07b39',
        strokeWeight: 5, map: state.tmap,
      }));
    });
  });
}

// ── 카카오 지도 ─────────────────────────────────────────────────────────────
// 네 번째 배경. 카카오는 **키가 둘로 나뉜다** — 서버가 부르는 REST API 키와 화면에
// 지도를 그리는 JavaScript 키. 여기서 쓰는 것은 JavaScript 키이고, 개발자센터의
// 플랫폼에 http://localhost:8899 을 등록해야 동작한다.
state.kmap = null;
state.kLines = []; state.kMarks = []; state.kPois = []; state.kCands = []; state.kPlan = [];

function initKakao(key) {
  if (!key || state.kmap || document.getElementById('kakao-sdk')) return;
  const sc = document.createElement('script');
  sc.id = 'kakao-sdk';
  // autoload=false 로 받아 두고 kakao.maps.load() 로 직접 켠다. 그래야 언제 준비됐는지
  // 알 수 있다 — TMAP 로더에서 겪은 문제(document.write 로 다시 받는다)를 피한다.
  sc.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`;
  sc.onerror = () => console.warn('카카오 지도 SDK 를 받지 못했다 — JS 키·도메인 등록 확인');
  sc.onload = () => {
    if (!window.kakao || !kakao.maps) return;
    kakao.maps.load(() => {
      const c = map.getCenter();
      state.kmap = new kakao.maps.Map(document.getElementById('kakaomap'), {
        center: new kakao.maps.LatLng(c.lat, c.lng), level: 6,
      });
      kakao.maps.event.addListener(state.kmap, 'click', (e) => {
        dropPin(e.latLng.getLat(), e.latLng.getLng());
      });
      kakao.maps.event.addListener(state.kmap, 'idle', () => {
        clearTimeout(state.poiTimer);
        state.poiTimer = setTimeout(loadPois, 350);
      });
      const b = document.getElementById('btn-kakao');
      if (b) b.disabled = false;
    });
  };
  document.head.appendChild(sc);
}

function clearKakaoOverlays() {
  ['kLines', 'kMarks', 'kPois', 'kCands', 'kPlan'].forEach((k) => {
    (state[k] || []).forEach((o) => o.setMap(null));
    state[k] = [];
  });
}

function drawOnKakao() {
  if (!state.kmap) return;
  (state.kMarks || []).forEach((m) => m.setMap(null)); state.kMarks = [];
  state.cart.forEach((p) => {
    state.kMarks.push(new kakao.maps.Marker({
      map: state.kmap, position: new kakao.maps.LatLng(p.lat, p.lng), title: p.name,
    }));
  });
}

// ── 배경 지도끼리 자리를 맞추는 계산 ────────────────────────────────────────
// **fitBounds 로 옮기면 안 된다.** 범위를 안쪽에 담느라 한 단계씩 축소되고, 그 커진
// 값을 다음 지도가 또 담아 **전환할 때마다 두 배씩 넓어진다**(실측 — 0.087 → 0.183
// → 0.232 → 0.348도). 그래서 중심과 **가로 폭** 을 그대로 옮기고 줌은 식으로 낸다.
//
// 표준 웹 메르카토르:  폭(도) = 360 × 화면폭(px) ÷ (256 × 2^zoom)
//   →  zoom = log2( 360 × 화면폭 ÷ (256 × 폭) )
//
// 카카오는 level 이라는 다른 눈금을 쓴다. 실측해 보니 **level + zoom = 19.92** 로
// 일정했다(level 1~10 에서 확인). 즉 level = 20 − zoom 이다.
const KAKAO_LEVEL_BASE = 20;

function mapWidthPx() {
  return document.getElementById('maps').getBoundingClientRect().width || 1;
}

function zoomForSpan(spanLng) {
  if (!spanLng || spanLng <= 0) return 13;
  return Math.log2(360 * mapWidthPx() / (256 * spanLng));
}

function spanOf(v) { return Math.max(1e-6, v.e - v.w); }

function setBase(base) {
  if (base === 'naver' && !state.nmap) return;
  if (base === 'tmap' && !state.tmap) return;
  if (base === 'kakao' && !state.kmap) return;

  // 바꾸기 **전에** 지금 보이는 범위를 떠 둔다. 바꾼 뒤에는 이 지도가 숨겨져
  // 크기가 0 이 되어 못 잰다.
  state.prevBounds = (typeof viewBounds === 'function' && state.base) ? viewBounds() : null;

  // 지금 보고 있는 지도의 중심·줌을 받아 둔다 — 배경을 바꿔도 자리를 잃지 않는다.
  let center = map.getCenter();
  let zoom = map.getZoom();
  if (state.base === 'naver' && state.nmap) {
    const c = state.nmap.getCenter();
    center = { lat: c.lat(), lng: c.lng() }; zoom = state.nmap.getZoom();
  } else if (state.base === 'tmap' && state.tmap) {
    const c = state.tmap.getCenter();
    center = { lat: c._lat, lng: c._lng }; zoom = state.tmap.getZoom();
  } else if (state.base === 'kakao' && state.kmap) {
    const c = state.kmap.getCenter();
    center = { lat: c.getLat(), lng: c.getLng() };
    zoom = KAKAO_LEVEL_BASE - state.kmap.getLevel();   // level 이 작을수록 확대다
  }

  state.base = base;
  document.querySelectorAll('#basemap-seg button').forEach((b) => {
    b.classList.toggle('on', b.dataset.base === base);
  });
  document.getElementById('map').hidden = base !== 'osm';
  document.getElementById('navermap').hidden = base !== 'naver';
  document.getElementById('tmapmap').hidden = base !== 'tmap';
  document.getElementById('kakaomap').hidden = base !== 'kakao';

  // **줌 숫자를 옮기면 어긋난다.** 카카오는 level(작을수록 확대), 나머지는 zoom
  // (클수록 확대)이라 눈금이 다르고, 같은 값이라도 지도마다 보이는 넓이가 다르다.
  // 그래서 숫자가 아니라 **보이는 범위(bounds)** 를 그대로 옮긴다 — 그것이 사실이다.
  //
  // 순서가 중요하다. 숨어 있는 지도는 크기가 0이라 범위를 계산하지 못한다(실측 —
  // 폭이 0 으로 나왔다). **먼저 보이게 하고, 크기를 다시 잡은 뒤, 범위를 적용한다.**
  const prev = state.prevBounds;
  const box = document.getElementById('maps').getBoundingClientRect();
  if (base === 'osm') {
    map.invalidateSize();
    const z = prev ? Math.round(zoomForSpan(spanOf(prev))) : zoom;
    map.setView([prev ? prev.lat : center.lat, prev ? prev.lng : center.lng], z,
                { animate: false });
    setTimeout(() => map.invalidateSize(), 30);
  } else if (base === 'naver') {
    state.nmap.setSize(new naver.maps.Size(box.width, box.height));
    naver.maps.Event.trigger(state.nmap, 'resize');
    state.nmap.setCenter(new naver.maps.LatLng(prev ? prev.lat : center.lat,
                                               prev ? prev.lng : center.lng));
    state.nmap.setZoom(prev ? Math.round(zoomForSpan(spanOf(prev))) : zoom);
    drawOnNaver();
    drawCandsOnNaver();          // 후보선은 배경마다 따로 만들어야 한다
    selectCand(state.pick || 0);
    if (state.trip === 'before' && typeof drawPlanOnNaver === 'function') drawPlanOnNaver();
  } else if (base === 'kakao') {
    state.kmap.relayout();
    state.kmap.setCenter(new kakao.maps.LatLng(prev ? prev.lat : center.lat,
                                               prev ? prev.lng : center.lng));
    if (prev) {
      const lv = Math.round(KAKAO_LEVEL_BASE - zoomForSpan(spanOf(prev)));
      state.kmap.setLevel(Math.max(1, Math.min(14, lv)));
    }
    drawOnKakao();
  } else {
    state.tmap.resize(box.width, box.height);
    state.tmap.setCenter(new Tmapv2.LatLng(prev ? prev.lat : center.lat,
                                           prev ? prev.lng : center.lng));
    state.tmap.setZoom(prev ? Math.round(zoomForSpan(spanOf(prev))) : zoom);
    drawOnTmap();
    drawCandsOnTmap();
    selectCand(state.pick || 0);
    if (state.trip === 'before' && typeof drawPlanOnTmap === 'function') drawPlanOnTmap();
  }
}

document.querySelectorAll('#basemap-seg button').forEach((b) => {
  b.addEventListener('click', () => setBase(b.dataset.base));
});

// ── 버전 목차 ───────────────────────────────────────────────────────────────
// 제목 옆 알약을 누르면 versions.js 의 내용이 그대로 목차로 펼쳐진다. 목차와
// 기능 플래그가 같은 파일에서 나오므로 둘이 어긋날 수 없다.

function paintVersion() {
  const d = V.def;
  const btn = document.getElementById('ver-btn');
  btn.textContent = d.id;
  btn.classList.toggle('wip', d.status === 'wip');
  btn.title = `${d.label} — ${d.title}`;
  const sub = document.getElementById('ver-sub');
  sub.textContent = `루트 탭 프로토타입 · ${d.label} · ${d.title}`;
  // 아직 안 만든 기능을 켠 채로 두면 오해하니, 켜진 기능만 문서에 적힌 대로 돈다.
  document.body.dataset.version = d.id;
}

function renderVersions() {
  const wrap = document.getElementById('ver-list');
  wrap.innerHTML = '';
  VERSIONS.forEach((v) => {
    const on = v.id === V.cur;
    const el = document.createElement('div');
    el.className = 'ver-item' + (on ? ' on' : '');
    const done = v.items.filter((i) => i.done).length;

    const tags = [];
    if (on) tags.push('<span class="ver-tag now">보는 중</span>');
    if (v.status === 'wip') tags.push('<span class="ver-tag wip">만드는 중</span>');
    tags.push(`<span class="ver-tag">${done}/${v.items.length}</span>`);

    el.innerHTML = `
      <div class="ver-top">
        <b>${v.label}</b>
        <span class="t">${v.title}</span>
        ${tags.join('')}
        <span class="ver-tag">${v.date}</span>
      </div>
      <p class="ver-sum">${v.summary}</p>
      <ul>${v.items.map((i) => `
        <li class="${i.done ? '' : 'todo'}">
          <span class="mk">${i.done ? '✅' : '⬜'}</span>
          <span class="tx"><b>${i.t}</b><span>${i.d}</span></span>
        </li>`).join('')}</ul>`;

    el.addEventListener('click', () => {
      if (v.id === V.cur) return;
      V.set(v.id);
      // 버전이 바뀌면 화면 전체가 그 버전의 규칙으로 다시 서야 한다. 부분만
      // 갈아끼우면 이전 버전의 상태가 남아 무엇 때문에 달라졌는지 알 수 없다.
      location.reload();
    });
    wrap.appendChild(el);
  });
}

function openVersions() {
  renderVersions();
  document.getElementById('ver-modal').hidden = false;
}
function closeVersions() {
  document.getElementById('ver-modal').hidden = true;
}

document.getElementById('ver-btn').addEventListener('click', openVersions);
document.getElementById('ver-close').addEventListener('click', closeVersions);
document.getElementById('ver-modal').addEventListener('click', (e) => {
  if (e.target.id === 'ver-modal') closeVersions();
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') closeVersions();
});

paintVersion();

// ── 버전 2 · 후보와 점수 ────────────────────────────────────────────────────
// 버전 1 은 "직선 거리 몇 m 이하면 도보" 라는 임계값 하나로 갈랐다. 여기서는
// 후보를 다 만들어 다섯 변수로 점수를 매기고, **추천 하나를 맨 위에 올린 뒤
// 나머지를 그 아래 펼친다.** 사용자가 왜 그게 1등인지 볼 수 있어야 한다.

// 서버의 WEIGHT_DEFAULT 와 짝이 맞아야 한다. 하나라도 빠지면 화면에
// `undefined` 슬라이더가 생긴다 — 계단을 더할 때 실제로 그랬다.
const WLABEL = { time: '시간', walk: '도보', transfer: '환승', fare: '요금',
                 climb: '오르막', stairs: '계단' };
state.weights = null;
state.cands = [];
state.candLayers = []; state.nCands = []; state.tCands = [];
state.pick = 0;

async function loadWeights() {
  const d = await fetch('/api/weights').then((r) => r.json());
  state.weights = { ...d.default };
  const chips = document.getElementById('pref-presets');
  chips.innerHTML = '';
  Object.keys(d.presets).forEach((name) => {
    const b = document.createElement('button');
    b.className = 'chip'; b.textContent = name;
    b.addEventListener('click', () => {
      state.weights = { ...d.default, ...d.presets[name] };
      document.getElementById('pref-text').value = name === '기본' ? '' : name;
      document.getElementById('pref-src').textContent = `프리셋 「${name}」`;
      paintWeights();
    });
    chips.appendChild(b);
  });
  paintWeights();
}

function paintWeights() {
  const box = document.getElementById('weights');
  box.innerHTML = '';
  if (typeof refreshWalkNote === 'function') refreshWalkNote();
  Object.entries(state.weights).forEach(([k, v]) => {
    const row = document.createElement('div');
    row.className = 'wrow';
    row.innerHTML = `<span>${WLABEL[k] || k}</span>
      <input type="range" min="0" max="5" step="0.1" value="${v}" data-w="${k}">
      <b>${(+v).toFixed(1)}</b>`;
    row.querySelector('input').addEventListener('input', (e) => {
      state.weights[k] = +e.target.value;
      row.querySelector('b').textContent = (+e.target.value).toFixed(1);
      if (k === 'stairs' && typeof refreshWalkNote === 'function') refreshWalkNote();
    });
    box.appendChild(row);
  });
}

// 사람 말 → 가중치. 규칙 표가 먼저 답하고, 없으면 로컬 LLM 을 부른다.
// 모델이 없어도 기능이 죽지 않는다 — 그래서 규칙을 앞에 뒀다.
let prefTimer = null;
function onPrefText() {
  clearTimeout(prefTimer);
  prefTimer = setTimeout(async () => {
    const text = document.getElementById('pref-text').value.trim();
    if (!text) return;
    const d = await fetch('/api/interpret', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    }).then((r) => r.json());
    state.weights = d.weights;
    paintWeights();
    document.getElementById('pref-src').textContent =
      `${d.source}${d.matched && d.matched.length ? ' — ' + d.matched.join(', ') : ''}`
      + (d.note ? ` · ${d.note}` : '');
  }, 500);
}

const fmtMin = (s) => `${Math.round((s || 0) / 60)}분`;
const fmtM = (m) => (m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m || 0)} m`);

function candNums(c) {
  const fix = c.duration_fix_s && c.duration_fix_s - c.duration_s > 60;
  return `
    <span><b>${fmtMin(c.duration_s)}</b>${fix ? ` <i>→ ${fmtMin(c.duration_fix_s)}</i>` : ''}</span>
    <span${c.walk_kind === '어림' ? ' class="dim"' : ''}>도보 <b>${fmtM(c.walk_m)}</b>
      ${c.walk_kind && c.walk_kind !== '카카오' ? `<i>${c.walk_kind}</i>` : ''}</span>
    ${c.kind === 'transit' ? `<span>환승 <b>${c.transfers}</b></span>` : ''}
    ${c.fare_krw ? `<span><b>${c.fare_krw.toLocaleString()}</b>원</span>` : ''}
    ${c.climb_m == null
      ? `<span class="climb dim">⛰ <i>${c.climb_kind || '모름'}</i></span>`
      : `<span class="climb${c.climb_m >= 30 ? ' hot' : ''}">⛰ <b>${Math.round(c.climb_m)} m</b>
      <i>${c.climb_kind || ''}</i></span>`}
    ${c.stairs != null ? `<span>계단 <b>${c.stairs}</b>
        ${c.stairs_kind === '안내문' ? '<i>안내문</i>' : ''}</span>`
                       : '<span class="dim">계단 <b>?</b> <i>못 쟀다</i></span>'}`;
}

/** 왜 이것이 1등이 아닌가. 점수만 보여 주면 사용자가 납득하지 못한다. */
function vsBest(c) {
  const v = c.vs_best;
  if (!v) return '';
  const w = v.worse.length ? `<span class="tag bad">${v.worse.join(' · ')}</span>` : '';
  const b = v.better.length ? `<span class="tag good">대신 ${v.better.join(' · ')}</span>` : '';
  if (!w && !b) return '<div class="cand-why"><span class="tag">거의 같다 — 점수로 갈렸다</span></div>';
  return `<div class="cand-why">${w}${b}</div>`;
}

function candCard(c, isTop, drawIdx) {
  const box = document.createElement('div');
  box.className = 'cand' + (isTop ? ' top' : '') + (c.stage === 2 ? ' fin' : '');
  const score = c.stage === 2 ? c.score2 : c.score;
  if (drawIdx != null) {
    box.dataset.i = drawIdx;
    box.style.setProperty('--cc', CAND_COLOR[drawIdx % CAND_COLOR.length]);
  }

  const head = document.createElement('div');
  head.className = 'cand-top';
  head.innerHTML = `
    ${drawIdx != null ? '<span class="cdot"></span>' : ''}
    ${isTop ? '<span class="tag rec">추천</span>' : `<span class="rk">${c.rank + 1}</span>`}
    <b>${c.kind === 'walk' ? '🚶 ' : '🚌 '}${c.label || ''}</b>
    ${c.stage === 2 && !isTop ? '<span class="tag fin">결선</span>' : ''}
    <span class="sc">${(score ?? 0).toFixed(2)}</span>
    ${isTop ? '' : '<span class="caret">▾</span>'}`;
  box.appendChild(head);
  if (drawIdx != null) head.addEventListener('click', () => selectCand(drawIdx));

  const nums = document.createElement('div');
  nums.className = 'cand-nums';
  nums.innerHTML = candNums(c);
  box.appendChild(nums);

  box.insertAdjacentHTML('beforeend', vsBest(c));

  // 자세한 것은 접어 둔다. 26개를 다 펼치면 아무것도 안 보인다.
  const more = document.createElement('div');
  more.className = 'cand-more';
  more.hidden = !isTop;
  if (c.seq && c.seq.length) {
    const q = document.createElement('div');
    q.className = 'cand-seq'; q.textContent = c.seq.join(' → ');
    more.appendChild(q);
  }
  if (c.duration_fix_s - c.duration_s > 60) {
    const n = document.createElement('p');
    n.className = 'cand-note';
    n.innerHTML = `오르막 ${Math.round(c.climb_m)} m 를 반영하면 <b>${fmtMin(c.duration_fix_s)}</b> 이다.
      티맵이 말하는 ${fmtMin(c.duration_s)} 은 평지 속도만 쓴 값이다.`;
    more.appendChild(n);
  }
  if (c.stage !== 2) {
    const n = document.createElement('p');
    n.className = 'cand-note dim';
    n.textContent = '1차에서 걸러졌다 — 실제로 채워 보지 않아 계단은 모르고 오르막은 어림값이다.';
    more.appendChild(n);
  }
  if (c.elev && c.elev.profile && c.elev.profile.length > 2) {
    more.appendChild(elevChart(c.elev, c.kind === 'transit' ? c.climb_m : null));
  } else if (c.walk_coords && c.walk_coords.length > 1) {
    // 카카오 후보는 고도를 **미리 재 두지 않는다.** 오르막이 이미 시간에 들어
    // 있어 순위에 필요가 없기 때문이다. 그래프를 보고 싶을 때만 그 하나를 잰다.
    more.appendChild(elevLater(c, more));
  }
  if (c.climb_est_m != null) {
    const n = document.createElement('p');
    n.className = 'cand-note dim';
    n.innerHTML = `1차에서는 <b>${c.climb_est_m} m</b> 로 어림했는데 실제로 채워 보니
      <b>${c.climb_m} m</b> 였다. 어림은 직선 위를 재므로 <b>낮게 나온다.</b>`;
    more.appendChild(n);
  }
  box.appendChild(more);

  if (!isTop) {
    head.style.cursor = 'pointer';
    head.addEventListener('click', () => {
      more.hidden = !more.hidden;
      head.querySelector('.caret').textContent = more.hidden ? '▾' : '▴';
    });
  }
  return box;
}

/** 「언덕 보기」 단추. 눌렀을 때만 고도 서버에 묻는다.
 *
 * 예전에는 후보 15개를 미리 다 재 두고 그중 하나만 봤다. 14번은 버려졌고,
 * 고도 서버가 느린 날(2026-08-15, 한 번에 30초)에는 그 14번이 화면을 멈춰 세웠다.
 */
function elevLater(c, more) {
  const wrap = document.createElement('div');
  const btn = document.createElement('button');
  btn.className = 'ghost small';
  btn.textContent = '⛰ 언덕 보기';
  btn.addEventListener('click', async () => {
    btn.disabled = true; btn.textContent = '고도를 재는 중…';
    try {
      const d = await fetch('/api/elev', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ coords: c.walk_coords }),
      }).then((r) => r.json());
      if (!d.ok) { btn.textContent = '⛰ ' + (d.why || '고도를 못 받았다'); return; }
      c.elev = d.elev; c.climb_m = d.climb_m;
      wrap.replaceWith(elevChart(d.elev, d.climb_m));
    } catch (e) {
      btn.disabled = false; btn.textContent = '⛰ 다시 시도';
    }
  });
  wrap.appendChild(btn);
  return wrap;
}

/** 고도 단면을 작은 SVG 로 그린다. 숫자만 보면 얼마나 가파른지 감이 안 온다. */
function elevChart(el, walkUp) {
  const W = 320, H = 54, pad = 3;
  const pts = el.profile || [];
  if (pts.length < 2) return document.createElement('div');
  const maxD = pts[pts.length - 1][0] || 1;
  // 전개 연산자(...)는 배열이 아주 길면 터진다. reduce 로 훑는다.
  const lo = pts.reduce((m, p) => Math.min(m, p[1]), Infinity);
  const hi = pts.reduce((m, p) => Math.max(m, p[1]), -Infinity);
  const span = Math.max(1, hi - lo);
  const d = pts.map((p, i) => {
    const x = pad + (p[0] / maxD) * (W - pad * 2);
    const y = H - pad - ((p[1] - lo) / span) * (H - pad * 2);
    return (i ? 'L' : 'M') + x.toFixed(1) + ' ' + y.toFixed(1);
  }).join(' ');
  const wrap = document.createElement('div');
  wrap.className = 'elev';
  wrap.innerHTML = `
    <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
      <path d="${d} L ${W - pad} ${H} L ${pad} ${H} Z" class="fill"/>
      <path d="${d}" class="line"/>
    </svg>
    <div class="elev-cap">
      <span>↑ ${el.up_m} m</span><span>↓ ${el.down_m} m</span>
      <span>${el.lo_m}~${el.hi_m} m</span>
      <span class="hint">${walkUp != null
        ? `버스 구간까지 포함한 전체 단면이다 — 실제로 걷는 오르막은 ${Math.round(walkUp)} m`
        : '경로 전체 단면'} · SRTM 30 m</span>
    </div>`;
  return wrap;
}

// 결선에 오른 것들은 **동시에** 그린다. 하나만 그리면 왜 그것이 1등인지
// 지도에서는 알 수 없다 — 나란히 놓여야 "아, 이쪽이 돌아가는구나" 가 보인다.
const CAND_COLOR = ['#e0574d', '#2f6fd0', '#43a86b', '#9b6dd6', '#e07b39'];

function clearCandLayers() {
  (state.candLayers || []).forEach((l) => map.removeLayer(l));
  (state.nCands || []).forEach((l) => l.setMap(null));
  (state.tCands || []).forEach((l) => l.setMap(null));
  state.candLayers = []; state.nCands = []; state.tCands = [];
  const lg = document.getElementById('cand-legend');
  if (lg) lg.hidden = true;
}

// 굵기·투명도는 **셋 다 읽히는 선에서** 강약만 준다. 예전에는 고른 것 말고는
// 투명도 0.35 에 점선으로 만들었더니 "하나만 그려진다" 는 말을 들었다.
const PICK_ON = { weight: 7, opacity: 0.95 };
const PICK_OFF = { weight: 4.5, opacity: 0.8 };

function drawTop(cands) {
  clearCandLayers();
  const drawable = cands.filter((c) => c.coords && c.coords.length);
  state.drawable = drawable;
  if (!drawable.length) return;

  // 뒤엣것부터 그려 1등이 맨 위로 올라오게 한다
  [...drawable].reverse().forEach((c) => {
    const i = drawable.indexOf(c);
    const line = L.polyline(c.coords.map((p) => [p[1], p[0]]), {
      color: CAND_COLOR[i % CAND_COLOR.length], ...PICK_OFF,
    }).addTo(map);
    line.bindTooltip(`${i + 1}위 · ${c.label || ''} · ${fmtMin(c.duration_s)}`);
    line.on('click', () => selectCand(i));
    state.candLayers[i] = line;
  });
  drawLegend(drawable);

  // 배경 지도를 OSM 으로 끌어오지 않는다. **보고 있는 지도에 그대로 그린다.**
  try { drawCandsOnNaver(); } catch (e) { console.warn('네이버 후보선 실패', e); }
  try { drawCandsOnTmap(); } catch (e) { console.warn('TMAP 후보선 실패', e); }
  fitCands();
  selectCand(0);
}

/** 세 지도 중 지금 보이는 것의 범위를 후보에 맞춘다. */
function fitCands() {
  const pts = (state.drawable || []).flatMap((c) => c.coords);
  if (!pts.length) return;
  const lat = pts.map((p) => p[1]), lng = pts.map((p) => p[0]);
  const s0 = Math.min(...lat), n0 = Math.max(...lat);
  const w0 = Math.min(...lng), e0 = Math.max(...lng);
  if (state.base === 'naver' && state.nmap) {
    state.nmap.fitBounds(new naver.maps.LatLngBounds(
      new naver.maps.LatLng(s0, w0), new naver.maps.LatLng(n0, e0)));
  } else if (state.base === 'tmap' && state.tmap) {
    // Tmapv2 에 fitBounds 가 있는지 확인하지 못했다. 없으면 중심만 옮긴다.
    state.tmap.setCenter(new Tmapv2.LatLng((s0 + n0) / 2, (w0 + e0) / 2));
    try {
      if (typeof state.tmap.fitBounds === 'function' && Tmapv2.LatLngBounds) {
        state.tmap.fitBounds(new Tmapv2.LatLngBounds(
          new Tmapv2.LatLng(s0, w0), new Tmapv2.LatLng(n0, e0)));
      }
    } catch (e) { /* 중심만 맞춘 채로 둔다 */ }
  } else {
    map.fitBounds([[s0, w0], [n0, e0]], { padding: [40, 40] });
  }
}

// 네이버·TMAP 은 Leaflet 레이어를 그리지 못한다. 같은 좌표로 각자의 폴리라인을
// 따로 만들어야 한다. 색과 순서는 세 지도에서 똑같이 맞춘다.
function drawCandsOnNaver() {
  if (!state.nmap) return;
  (state.nCands || []).forEach((l) => l.setMap(null));
  state.nCands = [];
  [...(state.drawable || [])].reverse().forEach((c) => {
    const i = state.drawable.indexOf(c);
    state.nCands[i] = new naver.maps.Polyline({
      map: state.nmap,
      path: c.coords.map((p) => new naver.maps.LatLng(p[1], p[0])),
      strokeColor: CAND_COLOR[i % CAND_COLOR.length],
      strokeWeight: PICK_OFF.weight, strokeOpacity: PICK_OFF.opacity,
    });
    naver.maps.Event.addListener(state.nCands[i], 'click', () => selectCand(i));
  });
}

function drawCandsOnTmap() {
  if (!state.tmap) return;
  (state.tCands || []).forEach((l) => l.setMap(null));
  state.tCands = [];
  [...(state.drawable || [])].reverse().forEach((c) => {
    const i = state.drawable.indexOf(c);
    state.tCands[i] = new Tmapv2.Polyline({
      map: state.tmap,
      path: c.coords.map((p) => new Tmapv2.LatLng(p[1], p[0])),
      strokeColor: CAND_COLOR[i % CAND_COLOR.length],
      strokeWeight: PICK_OFF.weight,
    });
  });
}

function drawLegend(drawable) {
  const box = document.getElementById('cand-legend');
  if (!box) return;
  box.innerHTML = drawable.map((c, i) => `
    <button data-i="${i}" style="--cc:${CAND_COLOR[i % CAND_COLOR.length]}">
      <span class="cdot"></span>${i + 1}위 ${fmtMin(c.duration_s)}
    </button>`).join('');
  box.querySelectorAll('button').forEach((b) => {
    b.addEventListener('click', (e) => { e.stopPropagation(); selectCand(+b.dataset.i); });
  });
  box.hidden = drawable.length < 2;
}

/** 후보 패널 안에서만 스크롤한다. 페이지 전체가 튀면 지도가 화면 밖으로 나간다. */
function scrollIntoPanel(el) {
  const box = document.getElementById('cands');
  if (!box) return;
  const top = el.offsetTop - box.offsetTop;
  const bottom = top + el.offsetHeight;
  if (top < box.scrollTop || bottom > box.scrollTop + box.clientHeight) {
    box.scrollTo({ top: Math.max(0, top - 12), behavior: 'smooth' });
  }
}

/** 고른 경로를 앱에 넘길 형식(GeoJSON)으로 낸다. 버전 1 의 7절이 하던 일이다. */
function candGeoJSON(c) {
  if (!c || !c.coords || !c.coords.length) return null;
  return {
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: c.coords },
      properties: {
        label: c.label || '', kind: c.kind,
        duration_s: Math.round(c.duration_s || 0),
        duration_fix_s: Math.round(c.duration_fix_s || 0),
        walk_m: Math.round(c.walk_m || 0), walk_kind: c.walk_kind || '',
        transfers: c.transfers || 0, fare_krw: c.fare_krw || 0,
        climb_m: c.climb_m || 0, climb_kind: c.climb_kind || '',
        stairs: c.stairs == null ? null : c.stairs, stairs_kind: c.stairs_kind || '',
        score: c.score2 ?? c.score,
      },
    }],
  };
}

function paintGeoJSON(c) {
  const box = document.getElementById('cand-geojson');
  if (!box) return;
  const g = candGeoJSON(c);
  if (!g) { box.hidden = true; return; }
  const txt = JSON.stringify(g, null, 1);
  box.hidden = false;
  box.querySelector('pre').textContent = txt;
  box.querySelector('.gsize').textContent =
    `${(txt.length / 1024).toFixed(1)} KB · 좌표 ${c.coords.length}개`;
}

/** 하나를 앞으로 끌어올린다. 나머지는 흐리게 두되 지우지 않는다. */
function selectCand(i) {
  state.pick = i;
  (state.candLayers || []).forEach((l, k) => {
    if (!l) return;
    const on = k === i;
    l.setStyle(on ? PICK_ON : PICK_OFF);
    if (on) l.bringToFront();
  });
  // 네이버·TMAP SDK 의 메서드는 문서로 확인하지 못한 것이 섞여 있다. 없어도
  // 선택 표시만 못 할 뿐 화면이 죽으면 안 되므로 감싼다.
  (state.nCands || []).forEach((l, k) => {
    if (!l) return;
    const on = k === i;
    try {
      l.setOptions({ strokeWeight: on ? PICK_ON.weight : PICK_OFF.weight,
                     strokeOpacity: on ? PICK_ON.opacity : PICK_OFF.opacity,
                     zIndex: on ? 200 : 100 });
    } catch (e) { /* 무시한다 — 선은 이미 그려져 있다 */ }
  });
  (state.tCands || []).forEach((l, k) => {
    if (!l) return;
    const w = k === i ? PICK_ON.weight : PICK_OFF.weight;
    try {
      if (typeof l.setStrokeWeight === 'function') l.setStrokeWeight(w);
      else if (typeof l.setOptions === 'function') l.setOptions({ strokeWeight: w });
    } catch (e) { /* 무시한다 */ }
  });
  // 음영은 **고른 것** 을 따라간다. 1위에 영구히 붙여 두면 2위를 골랐을 때
  // 화면이 여전히 1위를 가리켜 어느 것을 보고 있는지 알 수 없다.
  let picked = null;
  document.querySelectorAll('#cands .cand').forEach((el) => {
    const on = +el.dataset.i === i;
    el.classList.toggle('picked', on);
    if (on) picked = el;
  });
  if (picked) {
    // 고른 카드는 펼쳐 준다 — 지도에서 눌렀는데 접혀 있으면 볼 것이 없다.
    const more = picked.querySelector('.cand-more');
    if (more && more.hidden) {
      more.hidden = false;
      const caret = picked.querySelector('.caret');
      if (caret) caret.textContent = '▴';
    }
    scrollIntoPanel(picked);
  }
  document.querySelectorAll('#cand-legend button').forEach((b) => {
    b.classList.toggle('on', +b.dataset.i === i);
  });
  paintGeoJSON((state.drawable || [])[i]);
}

function renderCands(res) {
  const wrap = document.getElementById('cands');
  document.getElementById('cands-n').textContent = res.candidates.length;
  document.getElementById('cands-calls').textContent = res.calls ? `호출 ${res.calls}` : '';
  const top = document.getElementById('cands-top');
  const rest = document.getElementById('cands-rest');
  top.innerHTML = ''; rest.innerHTML = '';
  const cs = res.candidates || [];
  if (!cs.length) {
    top.innerHTML = `<p class="hint">${(res.notes || []).join(' · ') || '후보가 없다'}</p>`;
    wrap.hidden = false; clearCandLayers(); return;
  }

  // 지도에 그리는 것은 **결선에 오른 것만** 이다. 1차 후보는 좌표가 있어도
  // 그리지 않는다 — 카드에 색 점이 없는 선이 지도에 떠 있으면 짝이 안 맞는다.
  const drawable = cs.filter((c) => c.stage === 2 && c.coords && c.coords.length);
  const idxOf = (c) => { const i = drawable.indexOf(c); return i < 0 ? null : i; };

  top.appendChild(candCard(cs[0], true, idxOf(cs[0])));

  const fin = cs.filter((c, i) => i > 0 && c.stage === 2);
  const out = cs.filter((c) => c.stage !== 2);

  if (fin.length) {
    rest.appendChild(hdr(`결선 ${fin.length + 1}개 중 나머지`,
      '실제로 채워 봐서 계단과 실제 오르막까지 잰 것들이다. 지도에 함께 그려져 있다 — 누르면 앞으로 나온다.'));
    fin.forEach((c) => rest.appendChild(candCard(c, false, idxOf(c))));
  }
  if (out.length) {
    rest.appendChild(hdr(`1차에서 걸러진 ${out.length}개`,
      '싼 자료로만 줄 세웠다. 점수 기준이 결선과 달라 위 숫자와 나란히 비교하면 안 된다.'));
    out.forEach((c) => rest.appendChild(candCard(c, false, null)));
  }
  if ((res.notes || []).length) {
    const n = document.createElement('p');
    n.className = 'hint'; n.textContent = res.notes.join(' · ');
    rest.appendChild(n);
  }
  wrap.hidden = false;
  resizeMaps();
  drawTop(drawable);
}

function hdr(title, sub) {
  const d = document.createElement('div');
  d.className = 'cand-hdr';
  d.innerHTML = `<b>${title}</b><span>${sub}</span>`;
  return d;
}

async function findCandidates() {
  if (state.cart.length < 2) { alert('장바구니에 지점을 두 개 이상 담는다'); return; }
  const btn = document.getElementById('find-cands');
  btn.disabled = true; btn.textContent = '찾는 중…';
  try {
    const [a, b] = state.cart;
    const res = await fetch('/api/candidates', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        points: [[a.lat, a.lng], [b.lat, b.lng]],
        weights: state.weights,
        lang: +document.getElementById('lang-select').value,
        finalists: 3,   // 셋으로 확정한다. 늘리면 사용자가 고르지 못한다
      }),
    }).then((r) => r.json());
    state.cands = res.candidates || [];
    renderCands(res);
  } catch (e) {
    alert('후보를 만들지 못했다: ' + e.message);
  } finally {
    btn.disabled = false; btn.textContent = '경로 찾기';
  }
}

function setupV2() {
  if (!V.has('score') && !V.has('odsay_alts')) return;   // 버전 1 이면 아무것도 안 붙인다
  document.getElementById('v2-pref').hidden = false;
  // 버전 2 는 도보/대중교통을 **점수가 고른다.** 사람이 가르는 탭과 임계값
  // 슬라이더는 그래서 사라진다. 엔진도 ODsay 로 고정이라 고를 것이 없다.
  //
  // 버전 1 의 「경로 찾기」 단추와 결과 절(5·6·7)도 함께 감춘다. 남겨 두면
  // 같은 이름의 단추가 둘이 되어 무엇을 눌러야 하는지 알 수 없다 — 실제로
  // 그런 화면이 나왔다.
  ['sec-mode', 'sec-engine', 'go', 'result-box', 'compare-box', 'geojson-box']
    .forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.hidden = true;
    });
  document.getElementById('pref-text').addEventListener('input', onPrefText);
  document.getElementById('find-cands').addEventListener('click', () => {
    // v5 부터 **교통 엔진을 사용자가 고른다.** 도보는 TMAP 으로 고정이다.
    if (V.has('engine_pick')) { runPicked(); return; }
    // 버전 3 은 구간이 여럿일 수 있다. 두 지점이면 결과가 같다.
    if (V.has('multi_leg')) findCourse(true); else findCandidates();
  });
  document.getElementById('cands-close').addEventListener('click', () => {
    document.getElementById('cands').hidden = true;
    clearCandLayers();
    resizeMaps();
  });
  loadWeights();
}

setupV2();

// ── 버전 3 · 여행 전 / 여행 중 ──────────────────────────────────────────────
// 씬트립은 두 단계로 쓴다.
//   여행 전 — 지점을 담고 **직선으로** 러프하게 잇는다. API 를 부르지 않는다.
//   여행 중 — 그 핀과 순서를 **그대로 이어받아** 실제 경로로 푼다.
// 순서를 여행 전에 잘 정해 두면 여행 중의 호출이 줄고 답도 좋아진다.

state.trip = 'before';
state.planLayer = null;
state.nPlan = [];
state.tPlan = [];

function setTrip(mode) {
  state.trip = mode;
  document.querySelectorAll('#trip-seg button').forEach((b) => {
    b.classList.toggle('on', b.dataset.trip === mode);
  });
  const before = mode === 'before';
  document.getElementById('plan-box').hidden = !before;
  document.getElementById('trip-note').innerHTML = before
    ? '담은 지점을 <b>직선으로</b> 잇는다. API 를 부르지 않는다.'
    : '여행 전에 정한 <b>핀과 순서를 그대로</b> 이어받아 실제 경로로 푼다.';
  const pref = document.getElementById('v2-pref');
  if (pref) pref.hidden = before;
  const cands = document.getElementById('cands');
  if (before) {
    if (cands) cands.hidden = true;
    clearCandLayers(); clearCourse();
    drawPlanLine();
  } else {
    clearPlanLine();
  }
  resizeMaps();
}

/** 여행 전의 직선. 실제 길이 아니라 **순서를 보는 그림** 이다. */
function drawPlanLine() {
  clearPlanLine();
  if (state.cart.length < 2) return;
  const pts = state.cart.map((p) => [p.lat, p.lng]);
  state.planLayer = L.polyline(pts, {
    color: '#6b6b76', weight: 3, opacity: 0.9, dashArray: '6 6',
  }).addTo(map);
  drawPlanOnNaver(); drawPlanOnTmap();
  fitPlan();
}

function clearPlanLine() {
  if (state.planLayer) { map.removeLayer(state.planLayer); state.planLayer = null; }
  (state.nPlan || []).forEach((l) => l.setMap(null)); state.nPlan = [];
  (state.tPlan || []).forEach((l) => l.setMap(null)); state.tPlan = [];
}

function drawPlanOnNaver() {
  if (!state.nmap || state.cart.length < 2) return;
  state.nPlan = [new naver.maps.Polyline({
    map: state.nmap,
    path: state.cart.map((p) => new naver.maps.LatLng(p.lat, p.lng)),
    strokeColor: '#6b6b76', strokeWeight: 3, strokeOpacity: 0.9, strokeStyle: 'shortdash',
  })];
}

function drawPlanOnTmap() {
  if (!state.tmap || state.cart.length < 2) return;
  state.tPlan = [new Tmapv2.Polyline({
    map: state.tmap,
    path: state.cart.map((p) => new Tmapv2.LatLng(p.lat, p.lng)),
    strokeColor: '#6b6b76', strokeWeight: 3,
  })];
}

function fitPlan() {
  if (state.cart.length < 2) return;
  const lat = state.cart.map((p) => p.lat), lng = state.cart.map((p) => p.lng);
  const s0 = Math.min(...lat), n0 = Math.max(...lat);
  const w0 = Math.min(...lng), e0 = Math.max(...lng);
  if (state.base === 'naver' && state.nmap) {
    state.nmap.fitBounds(new naver.maps.LatLngBounds(
      new naver.maps.LatLng(s0, w0), new naver.maps.LatLng(n0, e0)));
  } else if (state.base === 'tmap' && state.tmap) {
    state.tmap.setCenter(new Tmapv2.LatLng((s0 + n0) / 2, (w0 + e0) / 2));
  } else {
    map.fitBounds([[s0, w0], [n0, e0]], { padding: [50, 50] });
  }
}

/** 동선 최적화 — 직선 거리로만. 호출 0건이다. */
async function optimizePlan() {
  if (state.cart.length < 3) { alert('지점을 세 개 이상 담는다'); return; }
  const btn = document.getElementById('plan-optimize');
  btn.disabled = true; btn.textContent = '계산 중…';
  try {
    const d = await fetch('/api/plan', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        points: state.cart.map((p) => [p.lat, p.lng]),
        fixed_start: document.getElementById('fix-start').checked,
      }),
    }).then((r) => r.json());
    if (d.error) { alert(d.error); return; }
    state.cart = d.order.map((i) => state.cart[i]);
    renderCart();
    drawPlanLine();
    paintPlanResult(d);
  } finally {
    btn.disabled = false; btn.textContent = '✨ 동선 최적화';
  }
}

// 세 방법을 나란히 보여 준다. "최근접 이웃으로 충분한가" 를 눈으로 판단해야
// 팀 목업(최근접 이웃만 쓴다)을 바꿀지 정할 수 있다.
function paintPlanResult(d) {
  const box = document.getElementById('plan-result');
  const km = (m) => (m == null ? '—' : (m / 1000).toFixed(1) + ' km');
  box.innerHTML = d.tries.map((t) => `
    <div class="plan-try${t.name === d.picked ? ' win' : ''}">
      <span>${t.name === d.picked ? '★ ' : ''}${t.name}</span>
      <b>${km(t.m)}</b>
      <span class="note">${t.note}</span>
    </div>`).join('')
    + `<p class="plan-sum">담은 순서 ${km(d.before_m)} → <b>${km(d.after_m)}</b>
       (${Math.round((1 - d.after_m / Math.max(1, d.before_m)) * 100)}% 줄었다) ·
       <b>호출 0건</b></p>`;
}

// ── 버전 3 · 여행 중 · 여러 구간 ────────────────────────────────────────────
// 구간마다 후보를 뽑아 각각의 1등을 이어 붙인다. 구간을 넘나드는 최적화는 하지
// 않는다 — 순서는 여행 전에 이미 정해져서 들어오기 때문이다.

state.course = null;
state.courseLayers = [];
state.kCourse = [];
state.legPick = 0;
const LEG_COLOR = ['#e0574d', '#2f6fd0', '#43a86b', '#9b6dd6', '#e07b39', '#0aa2c0', '#c94f8e'];

function clearCourse() {
  (state.courseLayers || []).forEach((l) => map.removeLayer(l));
  (state.nCourse || []).forEach((l) => l.setMap(null));
  (state.tCourse || []).forEach((l) => l.setMap(null));
  (state.kCourse || []).forEach((l) => l.setMap(null));
  state.courseLayers = []; state.nCourse = []; state.tCourse = []; state.kCourse = [];
}

function drawCourse(course) {
  clearCourse();
  const picked = [];
  const legs = course.legs || [];
  // 구간이 하나뿐이면 **후보 1·2·3 위를 함께** 그린다. 여럿이면 구간마다 고른 것을
  // 하나씩 그린다 — 구간 셋에 후보 셋이면 선이 아홉 개라 아무것도 안 보인다.
  const src = legs.length === 1
    ? ((legs[0].candidates || []).slice(0, 3).map((c, k) => ({ leg: legs[0], c, i: k })))
    : legs.map((leg, i) => ({ leg, c: (leg.candidates || [])[leg.pick || 0], i }));
  src.forEach(({ c, i }) => {
    if (!c || !c.coords || !c.coords.length) return;
    picked.push({ i, c });
    const color = LEG_COLOR[i % LEG_COLOR.length];
    const line = L.polyline(c.coords.map((p) => [p[1], p[0]]),
      { color, weight: 6, opacity: 0.85 }).addTo(map);
    line.bindTooltip(`${legs.length === 1 ? (i + 1) + '위' : (i + 1) + '번째 구간'}`
      + ` · ${c.label || ''} · ${fmtMin(c.duration_s)}`);
    line.on('click', () => selectLeg(i));
    state.courseLayers[i] = line;
    if (state.nmap) {
      state.nCourse[i] = (new naver.maps.Polyline({
        map: state.nmap, path: c.coords.map((p) => new naver.maps.LatLng(p[1], p[0])),
        strokeColor: color, strokeWeight: 6, strokeOpacity: 0.85,
      }));
    }
    if (state.tmap) {
      try {
        state.tCourse[i] = new Tmapv2.Polyline({
          map: state.tmap, path: c.coords.map((p) => new Tmapv2.LatLng(p[1], p[0])),
          strokeColor: color, strokeWeight: 6,
        });
      } catch (e) { /* SDK 차이는 무시한다 */ }
    }
    // 카카오도 빼먹으면 안 된다 — 배경을 바꿀 때마다 놓치는 자리다(8/12 에만 다섯 번째)
    if (state.kmap) {
      try {
        const line2 = new kakao.maps.Polyline({
          map: state.kmap,
          path: c.coords.map((p) => new kakao.maps.LatLng(p[1], p[0])),
          strokeColor: color, strokeWeight: 6, strokeOpacity: 0.85,
        });
        kakao.maps.event.addListener(line2, 'click', () => selectLeg(i));
        state.kCourse[i] = line2;
      } catch (e) { console.warn('카카오 경로선 실패', e); }
    }
  });
  if (picked.length) fitPlan();
  drawCourseLegend(course, picked);
  selectLeg(Math.min(state.legPick || 0, Math.max(0, picked.length - 1)));
}

/** 지도 위 범례. 구간이 여럿이면 구간별로, 한 구간이면 후보 1·2·3 위로 보여 준다. */
function drawCourseLegend(course, picked) {
  const box = document.getElementById('cand-legend');
  if (!box) return;
  const legs = course.legs || [];
  let items;
  if (legs.length > 1) {
    items = picked.map(({ i, c }) => ({
      color: LEG_COLOR[i % LEG_COLOR.length],
      label: `${i + 1}번째 ${fmtMin(c.duration_s)}`,
      pick: () => { selectLeg(i); },
    }));
  } else {
    const cs = (legs[0] || {}).candidates || [];
    items = cs.slice(0, 3).map((c, k) => ({
      color: LEG_COLOR[k % LEG_COLOR.length],
      label: `${k + 1}위 ${fmtMin(c.duration_s)}`,
      // 다시 그리면 셋이 새로 깔려 강조가 사라진다. **강조만** 바꾼다.
      pick: () => { legs[0].pick = k; selectLeg(k); },
    }));
  }
  box.innerHTML = '';
  items.forEach((it, k) => {
    const b = document.createElement('button');
    b.style.setProperty('--cc', it.color);
    b.innerHTML = `<span class="cdot" style="background:${it.color}"></span>${it.label}`;
    b.addEventListener('click', (e) => { e.stopPropagation(); it.pick(); });
    box.appendChild(b);
  });
  box.hidden = items.length < 2;
}

/** 구간 하나를 앞으로 끌어올린다. */
function selectLeg(i) {
  state.legPick = i;
  (state.courseLayers || []).forEach((l, k) => {
    if (!l) return;
    l.setStyle(k === i ? { weight: 8, opacity: 0.95 } : { weight: 4, opacity: 0.55 });
    if (k === i) l.bringToFront();
  });
  (state.nCourse || []).forEach((l, k) => {
    if (!l) return;
    try {
      l.setOptions({ strokeWeight: k === i ? 8 : 4,
                     strokeOpacity: k === i ? 0.95 : 0.55, zIndex: k === i ? 200 : 100 });
    } catch (e) { /* 무시 */ }
  });
  (state.kCourse || []).forEach((l, k) => {
    if (!l) return;
    try {
      l.setOptions({ strokeWeight: k === i ? 8 : 4,
                     strokeOpacity: k === i ? 0.95 : 0.55, zIndex: k === i ? 200 : 100 });
    } catch (e) { /* 무시 */ }
  });
  (state.tCourse || []).forEach((l, k) => {
    if (!l) return;
    try {
      if (typeof l.setStrokeWeight === 'function') l.setStrokeWeight(k === i ? 8 : 4);
    } catch (e) { /* 무시 */ }
  });
  document.querySelectorAll('#cand-legend button').forEach((b, k) => {
    b.classList.toggle('on', k === i);
  });

  // 카드도 함께 움직인다 — 음영 · 펼치기 · 그 자리로 스크롤.
  // 지도에서 눌렀는데 아래가 그대로면 무엇을 보고 있는지 알 수 없다.
  const cards = [...document.querySelectorAll('#cands .cand[data-i]')];
  if (cards.length) {
    let hit = null;
    cards.forEach((el) => {
      const on = +el.dataset.i === i;
      el.classList.toggle('picked', on);
      if (on) hit = el;
    });
    if (hit) {
      const more = hit.querySelector('.cand-more');
      if (more && more.hidden) {
        more.hidden = false;
        const caret = hit.querySelector('.caret');
        if (caret) caret.textContent = '▴';
      }
      scrollIntoPanel(hit);
    }
    return;
  }
  const el = document.querySelectorAll('#cands-rest .leg')[i];
  if (el) scrollIntoPanel(el);
}

function renderCourse(d) {
  const wrap = document.getElementById('cands');
  const top = document.getElementById('cands-top');
  const rest = document.getElementById('cands-rest');
  top.innerHTML = ''; rest.innerHTML = '';
  document.getElementById('cands-n').textContent = `${d.summary.ok_count}/${d.summary.leg_count}`;
  document.getElementById('cands-calls').textContent = d.calls_text ? `호출 ${d.calls_text}` : '';

  paintEngineTabs();
  const cmp = typeof compareTable === 'function' ? compareTable() : null;
  if (cmp) top.appendChild(cmp);

  const s = d.summary;
  const sum = document.createElement('div');
  sum.className = 'course-sum';
  sum.innerHTML = `
    <b>전체 여정</b>
    <div class="cand-nums">
      <span><b>${fmtMin(s.duration_s)}</b>${s.duration_fix_s - s.duration_s > 60
        ? ` <i>→ ${fmtMin(s.duration_fix_s)}</i>` : ''}</span>
      <span>도보 <b>${fmtM(s.walk_m)}</b>${s.walk_partial ? ' <i>이상</i>' : ''}</span>
      <span>환승 <b>${s.transfers}</b></span>
      ${s.fare_krw ? `<span><b>${s.fare_krw.toLocaleString()}</b>원</span>` : ''}
      <span class="climb${s.climb_m >= 100 ? ' hot' : ''}">⛰ <b>${Math.round(s.climb_m)} m</b></span>
      <span>계단 <b>${s.stairs == null ? '?' : s.stairs}</b>${s.stairs_partial ? ' <i>이상</i>' : ''}</span>
    </div>`;
  top.appendChild(sum);

  (d.legs || []).forEach((leg, i) => {
    const color = LEG_COLOR[i % LEG_COLOR.length];
    const box = document.createElement('div');
    box.className = 'leg';
    box.style.setProperty('--cc', color);
    const a = state.cart[leg.from], b = state.cart[leg.to];
    const head = `<div class="leg-head"><span class="cdot"></span>
      <b>${i + 1}번째 구간</b>
      <span class="t">${(a && a.name || '').slice(0, 14)} → ${(b && b.name || '').slice(0, 14)}</span></div>`;
    if (!leg.ok) {
      box.innerHTML = head + `<p class="cand-note dim">${leg.error || '경로를 찾지 못했다'}</p>`;
      rest.appendChild(box); return;
    }
    box.innerHTML = head;
    const single = (d.legs || []).length === 1;
    (leg.candidates || []).slice(0, 3).forEach((c, k) => {
      // 한 구간이면 후보 1·2·3 위가 곧 지도의 세 선이다. 카드에 같은 색·번호를 달아
      // **누르면 지도와 카드가 같이 움직이게** 한다.
      const el = candCard(c, k === 0, single ? k : null);
      el.classList.add('in-leg');
      el.addEventListener('click', () => {
        if (single) { leg.pick = k; selectLeg(k); }
        else { leg.pick = k; drawCourse(state.course); renderCourse(state.course); }
      });
      box.appendChild(el);
    });
    rest.appendChild(box);
  });
  wrap.hidden = false;
  resizeMaps();
}

async function findCourse(save) {
  if (state.cart.length < 2) { alert('지점을 두 개 이상 담는다'); return; }
  if (state.cart.length > 8) { alert('지점은 여덟 개까지만 — 호출이 너무 는다'); return; }
  const btn = document.getElementById('find-cands');
  btn.disabled = true; btn.textContent = '찾는 중…';
  try {
    const d = await fetch('/api/course', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        points: state.cart.map((p) => [p.lat, p.lng]),
        weights: state.weights,
        lang: +document.getElementById('lang-select').value,
      }),
    }).then((r) => r.json());
    if (d.error) { alert(d.error); return; }
    (d.legs || []).forEach((l) => { l.pick = 0; }); state.legPick = 0;
    state.course = d;
    if (typeof saveResult === 'function' && V.has('engine_compare')) saveResult('odsay', d);
    renderCourse(d);
    drawCourse(d);
  } catch (e) {
    alert('경로를 만들지 못했다: ' + e.message);
  } finally {
    btn.disabled = false;
    if (typeof paintTmapCost === 'function') paintTmapCost();
    else btn.textContent = '경로 찾기';
  }
}

// ── 🔴 TMAP 전용 ────────────────────────────────────────────────────────────
// 하루 10회뿐인 자원이다. **사용자가 직접 누를 때만** 돈다. 화면에서도 세 겹으로
// 막는다 — 남은 횟수 표시, 누르기 전 확인, 그리고 서버가 confirm 을 요구한다.

state.ledger = null;

/** 단추에 **지금 누르면 몇 회 드는지** 를 적는다.
 *
 * TMAP 대중교통은 점 대 점 전용이라 **지점 N 개면 N−1 회** 가 든다(경유지 파라미터가
 * 없다). 하루 10회뿐인데 6지점이면 한 번에 절반이 나간다. 확인 창에만 적어 두었더니
 * 무심코 눌러 5회가 나갔다 — **누르기 전부터 값이 보여야 한다.**
 */
function paintTmapCost() {
  // v5 부터 전용 단추가 없다. **주 단추(경로 찾기)에 값을 얹는다.**
  const btn = document.getElementById('find-cands');
  const note = document.getElementById('tmap-left');
  if (!btn) return;
  const picked = state.transit || 'kakao';

  if (picked !== 'tmap') {                 // 카카오는 값을 셀 필요가 없다
    btn.innerHTML = '경로 찾기';
    btn.disabled = state.cart.length < 2;
    if (note) note.hidden = true;
    return;
  }
  if (note) note.hidden = false;

  const need = Math.max(0, state.cart.length - 1);
  const led = state.ledger;
  const left = led ? led.left : '?';
  const budget = led ? led.budget : 10;

  if (!need) {
    btn.innerHTML = '경로 찾기';
    btn.disabled = true;
    if (note) {
      note.innerHTML = `지점을 두 개 이상 담는다 · 오늘 남은 <b>${left}/${budget}</b> 회`;
      note.className = 'hint';
    }
    return;
  }
  const over = led && need > led.left;
  btn.innerHTML = `경로 찾기 <b>· TMAP ${need}회 쓴다</b>`;
  btn.disabled = !!over;
  if (note) {
    note.innerHTML = over
      ? `<b>모자란다</b> — 구간 ${need}개인데 오늘 남은 것은 ${left}회다`
      : `지점 ${state.cart.length}개 → 구간 ${need}개 → <b>${need}회</b> 든다 ·
         오늘 남은 <b>${left}/${budget}</b> 회 → 쓰고 나면 <b>${Math.max(0, left - need)}회</b>`;
    note.className = 'hint' + (over || (led && led.left - need <= 2) ? ' warn' : '');
  }
}

async function paintLedger() {
  try {
    state.ledger = await fetch('/api/tmap-ledger').then((r) => r.json());
  } catch (e) { state.ledger = null; }
  paintTmapCost();
  return state.ledger;
}

async function runTmapOnly(save) {
  const need = Math.max(0, state.cart.length - 1);
  if (need < 1) { alert('지점을 두 개 이상 담는다'); return; }
  const led = await paintLedger();
  if (led && need > led.left) {
    alert(`구간이 ${need}개인데 오늘 남은 호출은 ${led.left}회다.`);
    return;
  }
  const ok = confirm(
    `TMAP 대중교통을 ${need}회 부른다.\n\n`
    + `  지점 ${state.cart.length}개 → 구간 ${need}개 → 구간마다 1회\n`
    + `  (경유지를 못 받는 API 라 합칠 수 없다)\n\n`
    + `오늘 남은 것 ${led ? led.left : '?'}/${led ? led.budget : 10} 회`
    + ` → 쓰고 나면 ${led ? Math.max(0, led.left - need) : '?'}회\n\n`
    + '진행할까?');
  if (!ok) return;

  const btn = document.getElementById('find-cands');
  btn.disabled = true; btn.textContent = 'TMAP 에 묻는 중…';
  try {
    const d = await fetch('/api/tmap-only', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        confirm: true,                       // 서버가 이것을 요구한다
        points: state.cart.map((p) => [p.lat, p.lng]),
        weights: state.weights,
        lang: +document.getElementById('lang-select').value,
        count: 10,
      }),
    }).then((r) => r.json());
    if (d.error) { alert('TMAP: ' + d.error); return; }
    (d.legs || []).forEach((l) => { l.pick = 0; }); state.legPick = 0;
    state.course = d;
    if (typeof saveResult === 'function' && V.has('engine_compare')) saveResult('tmap', d);
    renderCourse(d);
    drawCourse(d);
    document.getElementById('cands-calls').textContent =
      `TMAP 대중교통 ${d.used}회 사용 · 오늘 ${d.ledger.used}/${d.budget}`;
  } catch (e) {
    alert('TMAP 전용 호출에 실패했다: ' + e.message);
  } finally {
    paintLedger();
  }
}

// ── 버전 4 · 세 엔진을 나란히 ───────────────────────────────────────────────
// 같은 경로를 엔진마다 따로 풀어 **저장해 두고** 버튼으로 오간다. 갈아탈 때 다시
// 부르지 않는다 — TMAP 은 하루 10건뿐이라 두 번 부르면 그만큼 손해다.
//
// 저장은 **지점 목록** 을 열쇠로 삼는다. 장바구니가 바뀌면 이전 결과는 다른 경로의
// 것이므로 버린다. 안 그러면 A 코스의 TMAP 결과를 B 코스 옆에 놓고 비교하게 된다.

/* ── 교통 엔진 고르기 (v5) ─────────────────────────────────────────────────
 *
 * **도보는 TMAP 으로 고정이다.** 고를 수 있는 것이 아니라 고를 것이 없다 —
 * 계단(`facilityType 17`)을 주는 곳이 TMAP 뿐이고, 카카오는 출발지→역 ·
 * 역→도착지 도보를 아예 주지 않아 어차피 TMAP 으로 메워야 한다.
 *
 * 교통만 바꾼다. 셋의 성격이 많이 다르다 —
 *   카카오  하루 1,000건(추정) · 한 호출에 15개 · 경로선 포함 · 오르막이 시간에
 *   ODsay  약 1,000건 · 한 호출에 16~25개 · **양 끝 도보를 100% 준다**
 *   TMAP   **하루 10건** · 구간마다 1회 · 셋 중 공식 문서가 있는 유일한 것
 */
const TRANSIT = {
  kakao: {
    label: '🟡 카카오',
    note: '하루 1,000건(추정) · 한 호출에 후보 15개 · 경로선이 응답에 딸려 온다. '
        + '오르막이 시간에 이미 반영돼 있다. '
        + '<b>양 끝 도보를 0% 준다</b> — TMAP 도보로 메운다(구간당 7회쯤).',
    run: (save) => runKakaoOnly(save),
  },
  odsay: {
    label: '🔵 ODsay',
    note: '약 1,000건 · 한 호출에 후보 16~25개. '
        + '<b>양 끝 도보를 100% 준다</b> — 어디서 걷는지 짚어 주므로 TMAP 도보를 '
        + '덜 부른다. 대신 <b>경로선 좌표가 없어</b> 따로 받아야 하고, 자체 도보 '
        + '추정이 실측과 어긋난다(538 m → 실측 752 m).',
    run: (save) => findCourse(save),
  },
  tmap: {
    label: '🔴 TMAP',
    note: '<b>하루 10회뿐이다.</b> 경유지를 못 받는 API 라 <b>구간마다 1회</b> 든다 — '
        + '지점 6개면 5회다. 일상적으로 쓸 수 있는 양이 아니다. '
        + '<b>셋 중 공식 문서가 있는 유일한 것</b>이라 다른 둘을 대조할 때 쓴다.',
    run: (save) => runTmapOnly(save),
  },
};

/* 도보 엔진. **교통과 따로 고른다.**
 *
 * 카카오 하나로 도보+교통이 다 된다 — 도보 API 가 따로 있고 품질도 좋다
 * (지하 환승로를 알고 오르막을 시간에 반영한다). **잃는 것은 계단 하나다.**
 */
const WALKERS = {
  kakao: {
    note: '<b>기본값.</b> 교통과 같은 회사라 길찾기가 카카오 하나로 끝난다. '
        + '오르막을 시간에 반영하고 지하 환승로도 안다(을지로3가 260 m). '
        + '하루 1,000건 <b>전용</b>. '
        + '<b>다만 계단을 안 준다</b> — 계단이 「모름」 으로 나온다(0 이 아니다).',
  },
  tmap: {
    note: '<b>계단이 필요할 때 고른다</b>(<code>facilityType 17</code>) — 주는 곳이 '
        + '여기뿐이다. <b>무릎·캐리어·유모차 사용자에게는 이쪽</b>이다. '
        + '좌표도 2~4배 촘촘하다. 하루 1,000건이지만 자동차와 나눠 쓴다.',
  },
};

function setWalk(id) {
  if (!WALKERS[id]) return;
  state.walk = id;
  localStorage.setItem('stnavi.walk', id);
  document.querySelectorAll('#walk-seg button').forEach((b) => {
    b.classList.toggle('on', b.dataset.walk === id);
  });
  document.getElementById('walk-note').innerHTML = WALKERS[id].note + walkWarn();
}

/** 계단을 중히 보는 설정인데 도보가 카카오면 짚어 준다.
 *
 * 조용히 두면 안 되는 자리다 — 「무릎이 아파요」 라고 적어 가중치를 3.0 으로
 * 올려 놓고도 **계단이 모름이라 그 변수가 아무 일도 안 한다.**
 * 실측에서 그 차이로 1위가 뒤집힌 구간이 있었다(서울역→이태원).
 */
function walkWarn() {
  if (state.walk !== 'kakao') return '';
  const w = (state.weights || {}).stairs;
  if (!(w >= 2)) return '';
  return '<br><b class="warn">⚠ 지금 설정은 계단을 중히 보는데(가중치 '
       + w + ') 카카오 도보는 계단을 안 준다.</b> 계단이 중요하면 TMAP 으로 바꿔라.';
}

function setTransit(id) {
  if (!TRANSIT[id]) return;
  state.transit = id;
  localStorage.setItem('stnavi.transit', id);
  document.querySelectorAll('#transit-seg button').forEach((b) => {
    b.classList.toggle('on', b.dataset.transit === id);
  });
  document.getElementById('transit-note').innerHTML = TRANSIT[id].note;
  // 남은 횟수는 TMAP 일 때만 뜻이 있다
  if (id === 'tmap') paintLedger();     // 장부를 읽고 → paintTmapCost 로 이어진다
  else paintTmapCost();
}

function runPicked() {
  if (state.cart.length < 2) { alert('지점을 두 개 이상 담는다'); return; }
  TRANSIT[state.transit || 'kakao'].run(true);
}

function refreshWalkNote() {
  if (!V.has('engine_pick') || !state.walk) return;
  const el = document.getElementById('walk-note');
  if (el) el.innerHTML = WALKERS[state.walk].note + walkWarn();
}

function setupEnginePick() {
  if (!V.has('engine_pick')) return;
  document.getElementById('engine-pick-box').hidden = false;
  document.querySelectorAll('#transit-seg button').forEach((b) => {
    b.addEventListener('click', () => setTransit(b.dataset.transit));
  });
  document.querySelectorAll('#walk-seg button').forEach((b) => {
    b.addEventListener('click', () => setWalk(b.dataset.walk));
  });
  setTransit(localStorage.getItem('stnavi.transit') || 'kakao');
  setWalk(localStorage.getItem('stnavi.walk') || 'kakao');
}

const ENGINES_V4 = [
  { id: 'odsay', label: 'ODsay + TMAP도보', color: '#c0504d',
    note: '지금 확정한 조합', run: () => findCourse(true) },
  { id: 'tmap',  label: '🔴 TMAP 전용', color: '#e07b39',
    note: '하루 10건', run: () => runTmapOnly(true) },
  { id: 'kakao', label: '🟡 카카오 전용', color: '#e0a800',
    note: '하루 1,000건 · 도보 좌표 포함', run: () => runKakaoOnly(true) },
];

state.saved = {};        // 엔진 → 결과
state.savedKey = '';     // 어느 경로의 결과인가
state.engine = 'odsay';

function cartKey() {
  return state.cart.map((p) => `${p.lat.toFixed(5)},${p.lng.toFixed(5)}`).join('|');
}

/** 장바구니가 바뀌었으면 저장해 둔 것을 버린다. 다른 경로끼리 견주면 안 된다. */
function checkSavedKey() {
  const k = cartKey();
  if (k !== state.savedKey) { state.saved = {}; state.savedKey = k; }
}

function saveResult(engine, res) {
  checkSavedKey();
  state.saved[engine] = res;
  state.engine = engine;
  paintEngineTabs();
}

function paintEngineTabs() {
  const box = document.getElementById('engine-tabs');
  if (!box) return;
  const have = ENGINES_V4.filter((e) => state.saved[e.id]);
  if (!V.has('engine_compare') || have.length < 1) { box.hidden = true; return; }
  box.innerHTML = '';
  ENGINES_V4.forEach((e) => {
    const res = state.saved[e.id];
    const b = document.createElement('button');
    b.style.setProperty('--ec', e.color);
    b.classList.toggle('on', state.engine === e.id);
    b.disabled = !res;
    const s2 = res && res.summary;
    b.innerHTML = `${e.label}` + (s2
      ? `<span class="sub">${fmtMin(s2.duration_s)} · ${fmtM(s2.walk_m)}</span>`
      : `<span class="sub">아직</span>`);
    b.addEventListener('click', () => {
      if (!res) return;
      state.engine = e.id;
      renderCourse(res); drawCourse(res); paintEngineTabs();
    });
    box.appendChild(b);
  });
  box.hidden = false;
}

/** 두 개 이상 모이면 한 표에 놓는다. 무엇이 얼마나 다른지 숫자로 봐야 한다. */
function compareTable() {
  const have = ENGINES_V4.filter((e) => state.saved[e.id]);
  if (have.length < 2) return null;
  const ROWS = [
    ['소요', (s) => s.duration_s, (v) => fmtMin(v), 'low'],
    ['보정 소요', (s) => s.duration_fix_s, (v) => fmtMin(v), 'low'],
    ['도보', (s) => s.walk_m, (v) => fmtM(v), 'low'],
    ['환승', (s) => s.transfers, (v) => `${v}회`, 'low'],
    ['요금', (s) => s.fare_krw, (v) => `${(v || 0).toLocaleString()}원`, 'low'],
    ['오르막', (s) => s.climb_m, (v) => `${Math.round(v)} m`, 'low'],
    ['계단', (s) => s.stairs, (v) => (v == null ? '?' : `${v}곳`), 'low'],
  ];
  const t = document.createElement('table');
  t.className = 'cmp';
  t.innerHTML = '<thead><tr><th>항목</th>'
    + have.map((e) => `<th><span class="eng"><span class="edot" style="background:${e.color}"></span>`
        + `${e.label.replace(/^[🔴🟡]\s*/, '')}</span></th>`).join('')
    + '</tr></thead>';
  const tb = document.createElement('tbody');
  ROWS.forEach(([name, get, fmt]) => {
    const vals = have.map((e) => get(state.saved[e.id].summary));
    const known = vals.filter((v) => v != null);
    const best = known.length ? Math.min(...known) : null;
    tb.innerHTML += `<tr><td>${name}</td>`
      + vals.map((v) => `<td class="${v != null && v === best && known.length > 1 ? 'best' : ''}">`
          + `${v == null ? '?' : fmt(v)}</td>`).join('')
      + '</tr>';
  });
  t.appendChild(tb);
  return t;
}

async function runKakaoOnly(save) {
  if (state.cart.length < 2) { alert('지점을 두 개 이상 담는다'); return; }
  const btn = document.getElementById('find-cands');
  btn.disabled = true; btn.textContent = '카카오에 묻는 중…';
  try {
    const d = await fetch('/api/kakao-only', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ points: state.cart.map((p) => [p.lat, p.lng]),
                             weights: state.weights,
                             walk: state.walk || 'kakao' }),
    }).then((r) => r.json());
    if (d.error) { alert('카카오: ' + d.error); return; }
    // 고도를 못 쟀으면 **조용히 넘기지 않는다.** 0 으로 두면 평지처럼 읽힌다.
    const wb = document.getElementById('warn-box');
    wb.hidden = !d.warn;
    if (d.warn) wb.innerHTML = `<b>⚠ 오르막을 못 쟀다</b><div>· ${d.warn}</div>`
      + '<div>· 카카오 시간에는 오르막이 이미 들어 있어 순위는 크게 안 흔들린다</div>';
    (d.legs || []).forEach((l) => { l.pick = 0; }); state.legPick = 0;
    state.course = d;
    if (save) saveResult('kakao', d);
    renderCourse(d); drawCourse(d);
  } catch (e) {
    alert('카카오 호출 실패: ' + e.message);
  } finally {
    btn.disabled = false;
    paintTmapCost();          // 지금 고른 엔진에 맞는 글자로 되돌린다
  }
}

function setupV3() {
  if (!V.has('plan_mode')) return;
  document.getElementById('v3-mode').hidden = false;
  document.querySelectorAll('#trip-seg button').forEach((b) => {
    b.addEventListener('click', () => setTrip(b.dataset.trip));
  });
  document.getElementById('plan-optimize').addEventListener('click', optimizePlan);
  const pin = document.getElementById('pin-mode');
  if (pin) pin.addEventListener('click', () => setPinMode(!state.pinMode));

  // 지도에서 OSM 을 뺀다. 실제 앱이 쓰는 지도만 남긴다.
  if (V.has('no_osm')) {
    const osmBtn = document.querySelector('#basemap-seg button[data-base="osm"]');
    if (osmBtn) osmBtn.hidden = true;
    // 네이버가 준비되면 그리로 옮긴다. 준비 전에는 OSM 이 그대로 보인다 —
    // 빈 화면을 보여 주는 것보다 낫다.
    // 네이버가 준비되면 **한 번만** 옮긴다. 예전에는 이 타이머가 뒤늦게 살아나
    // 사용자가 고른 배경을 도로 네이버로 되돌렸다.
    let moved = false;
    const go = () => {
      if (moved) return;
      if (state.nmap) { moved = true; if (state.base === 'osm') setBase('naver'); }
      else setTimeout(go, 300);
    };
    setTimeout(go, 300);
  }
  // v5 부터 전용 단추가 없다 — 교통 엔진을 골라 주 단추로 부른다(setupEnginePick).
  setTrip('before');
}



/* ── 여행 가이드 챗봇 (v5) ─────────────────────────────────────────────────
 *
 * **계산은 서버가 한다.** 여기서는 말을 보내고 답을 그린다. 모델이 부른 도구를
 * 함께 보여 주는 이유 — 8B 짜리가 엉뚱한 도구를 부르면 그것이 바로 보여야 한다.
 * 답만 보면 그럴듯한 헛소리를 걸러 낼 수 없다.
 */
const guideChat = [];
// 서버가 「보여 준 장소」를 이 열쇠로 기억한다. 새로고침하면 새 대화가 된다.
const guideSid = 'g' + Math.random().toString(36).slice(2, 10);

/* v6 은 지도 위에 뜬 창을, 그 전 버전은 왼쪽 패널을 쓴다. **요소 id 를 여기
 * 한 곳에서만 고른다** — 함수마다 흩어 두면 한쪽만 고치는 실수가 난다
 * (8/12 에 배경 지도를 더할 때 다섯 군데를 빠뜨렸던 그 자리와 같은 모양이다). */
function gEl(which) {
  const fab = V.has('fab');
  return document.getElementById(
    { log: fab ? 'fab-log' : 'guide-log',
      inp: fab ? 'fab-in' : 'guide-in',
      send: fab ? 'fab-send' : 'guide-send',
      state: fab ? 'fab-state' : 'guide-state' }[which]);
}

function gPush(cls, html) {
  const log = gEl('log');
  const d = document.createElement('div');
  d.className = 'g-msg ' + cls;
  d.innerHTML = html;
  log.appendChild(d);
  log.scrollTop = log.scrollHeight;
  return d;
}

/** 모델이 마크다운 표를 뱉는다. 통째로 파싱하지 않고 표만 살린다. */
function gFmt(t) {
  const esc = (x) => x.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
  const lines = esc(t).split('\n');
  let out = '', tbl = null;
  const flush = () => { if (tbl) { out += '<table>' + tbl + '</table>'; tbl = null; } };
  for (const ln of lines) {
    if (/^\s*\|.*\|\s*$/.test(ln)) {
      const cells = ln.trim().slice(1, -1).split('|').map((c) => c.trim());
      if (cells.every((c) => /^-{2,}$/.test(c) || c === '')) continue;   // 구분선
      tbl = (tbl || '') + '<tr>' + cells.map((c) => `<td>${c}</td>`).join('') + '</tr>';
    } else { flush(); out += ln + '\n'; }
  }
  flush();
  return out.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').trim();
}

async function guideSend() {
  const inp = gEl('inp');
  const btn = gEl('send');
  const text = inp.value.trim();
  if (!text) return;

  // **지금 위치는 지도 한가운데다.** 배경마다 API 가 달라 viewBounds 를 쓴다 —
  // Leaflet 것만 읽으면 숨은 지도의 옛 중심으로 물어 엉뚱한 동네가 나온다.
  const v = viewBounds();
  if (!v) { gPush('g-err', '지도가 아직 안 떴다'); return; }

  inp.value = '';
  gPush('g-me', gFmt(text));
  guideChat.push({ role: 'user', content: text });
  btn.disabled = true; inp.disabled = true;
  const wait = gPush('g-ai', '<i>생각하는 중…</i>');

  try {
    const d = await fetch('/api/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ here: [v.lat, v.lng], messages: guideChat,
                             weights: state.weights, sid: guideSid,
                             // **화면이 아는 것을 매번 같이 보낸다.** 담은 지점이
                             // 바뀌거나 빠지면 그대로 반영된다 — 서버가 따로
                             // 기억하면 화면과 어긋난다.
                             context: chatContext() }),
    }).then((r) => r.json());

    if (d.error) {
      wait.className = 'g-msg g-err';
      wait.innerHTML = `<b>${d.error}</b>` + (d.hint ? `<br><small>${d.hint}</small>` : '');
      guideChat.pop();               // 실패한 turn 은 이력에 남기지 않는다
      return;
    }
    (d.used || []).forEach((u) => {
      gPush('g-tool', `↳ ${u.tool}(${Object.entries(u.args || {})
        .map(([k, val]) => `${k}=${val}`).join(', ')})`);
    });
    wait.innerHTML = gFmt(d.reply || '(답이 비었다)');
    wait.parentNode.appendChild(wait);          // 도구 줄 아래로 내린다
    guideChat.push({ role: 'assistant', content: d.reply || '' });

    // 추천한 곳을 지도에 찍고, 채팅창에 누를 수 있는 목록을 붙인다
    if (d.places && d.places.length) {
      drawPinoPins(d.places);
      gPushPlaces(d.places);
    }
    // 경로를 물었으면 지도에 그린다 — 말로만 하면 어디인지 모른다
    if (d.route && d.route.coords && d.route.coords.length > 1) {
      drawGuideRoute(d.route);
    }
    gEl('state').innerHTML =
      `로컬 LLM · ${d.took_s}초` + (d.used?.length ? ` · 도구 ${d.used.length}회` : '');
  } catch (e) {
    wait.className = 'g-msg g-err';
    wait.textContent = '못 물어봤다: ' + e.message;
    guideChat.pop();
  } finally {
    btn.disabled = false; inp.disabled = false; inp.focus();
  }
}

/** 가이드가 찾아 준 경로를 지금 배경에 그린다. */
function drawGuideRoute(r) {
  try {
    state.course = { legs: [{ ok: true, from: 0, to: 1, pick: 0,
                              candidates: [{ ...r, coords: r.coords, uid: 'guide' }] }],
                     engine: 'kakao', summary: {} };
    state.legPick = 0;
    if (typeof drawCourse === 'function') drawCourse(state.course);
  } catch (e) { console.warn('가이드 경로 그리기 실패', e); }
}

/* 챗봇이 추천한 곳을 **피노 핀**으로 지도에 찍는다 (v6).
 *
 * 배경마다 지도 객체가 달라 네 군데를 다 손봐야 한다 — 8/12 에 배경을 더할 때
 * 다섯 자리를 빠뜨렸던 그 교훈이다. 지우는 자리도 같이 둔다.
 */
let pinoMarks = [];
let pinoPlaces = [];
let pinoPicked = -1;         // 지금 고른 것. **한 곳만** 빨갛다

function clearPinoPins() {
  pinoMarks.forEach((m) => { try { m.remove?.(); m.setMap?.(null); } catch (e) {} });
  pinoMarks = [];
  pinoPlaces = [];
  pinoPicked = -1;
  const card = document.getElementById('place-card');
  if (card) card.hidden = true;
}

/** 채팅창 안에 누를 수 있는 장소 목록. 누르면 그 핀이 빨갛게 바뀐다. */
function gPushPlaces(places) {
  const box = document.createElement('div');
  box.className = 'g-msg g-ai';
  box.style.maxWidth = '100%';
  places.forEach((p, i) => {
    const row = document.createElement('div');
    row.className = 'g-place';
    row.innerHTML = `<span class="no">${i + 1}</span>`
      + `<span class="nm"></span><span class="dist">${p.dist_m ?? ''}m</span>`;
    row.querySelector('.nm').textContent = p.name;
    row.addEventListener('click', () => pinoFocus(i));
    box.appendChild(row);
  });
  const log = gEl('log');
  log.appendChild(box);
  log.scrollTop = log.scrollHeight;
}

function drawPinoPins(places) {
  clearPinoPins();
  const pts = places.filter((p) => p.lat && p.lng);
  if (!pts.length) return;
  pinoPlaces = pts;

  pts.forEach((p, i) => {
    const url = pinoDataUrl({ w: 40, picked: false, id: 'g' + i });
    const title = `${i + 1}. ${p.name}${p.dist_m ? ` \u00b7 ${p.dist_m}m` : ''}`;
    try {
      // **핀을 눌러도 카드가 뜬다.** 채팅 목록에서만 되면 지도만 보는 사람은
      // 그 정보에 닿을 방법이 없다.
      let mk;
      if (state.base === 'naver' && state.nmap) {
        mk = new naver.maps.Marker({
          position: new naver.maps.LatLng(p.lat, p.lng), map: state.nmap, title,
          // 핀 끝이 좌표를 가리키게 아래쪽 가운데를 기준으로
          icon: { url, size: new naver.maps.Size(40, 53),
                  anchor: new naver.maps.Point(20, 53) },
        });
        naver.maps.Event.addListener(mk, 'click', () => pinoFocus(i));
      } else if (state.base === 'kakao' && state.kmap) {
        mk = new kakao.maps.Marker({
          position: new kakao.maps.LatLng(p.lat, p.lng), map: state.kmap, title,
          image: new kakao.maps.MarkerImage(url, new kakao.maps.Size(40, 53),
                   { offset: new kakao.maps.Point(20, 53) }),
        });
        kakao.maps.event.addListener(mk, 'click', () => pinoFocus(i));
      } else if (state.base === 'tmap' && state.tmap) {
        mk = new Tmapv2.Marker({
          position: new Tmapv2.LatLng(p.lat, p.lng), map: state.tmap, title,
          iconHTML: `<img src="${url}" width="40" height="53">`,
        });
        try { mk.addListener('click', () => pinoFocus(i)); } catch (e) {}
      } else {
        mk = L.marker([p.lat, p.lng], {
          icon: L.icon({ iconUrl: url, iconSize: [40, 53], iconAnchor: [20, 53] }),
          title,
        }).addTo(map);
        mk.on('click', () => pinoFocus(i));
      }
      pinoMarks.push(mk);
    } catch (e) { console.warn('피노 핀 실패', e); }
  });

  // 추천한 곳이 다 보이게 지도를 맞춘다
  try {
    const lats = pts.map((p) => p.lat), lngs = pts.map((p) => p.lng);
    const s0 = Math.min(...lats), n0 = Math.max(...lats);
    const w0 = Math.min(...lngs), e0 = Math.max(...lngs);
    if (state.base === 'naver' && state.nmap) {
      state.nmap.fitBounds(new naver.maps.LatLngBounds(
        new naver.maps.LatLng(s0, w0), new naver.maps.LatLng(n0, e0)));
    } else if (state.base === 'kakao' && state.kmap) {
      const b = new kakao.maps.LatLngBounds();
      pts.forEach((p) => b.extend(new kakao.maps.LatLng(p.lat, p.lng)));
      state.kmap.setBounds(b);
    } else if (state.base !== 'tmap') {
      map.fitBounds([[s0, w0], [n0, e0]], { padding: [40, 40] });
    }
  } catch (e) { console.warn('피노 핀 범위 맞추기 실패', e); }
}

/** 고른 곳의 **핀 자체를 빨갛게 바꾸고 크게 키운다.**
 *
 * 처음에는 살아 있는 피노를 지도 위에 따로 겹쳐 띄웠는데 —
 * *"새로운 빨간색 핀이 거기로 가서 겹쳐보이게 하는게 아니라"* (사용자 지적).
 * 맞는 말이다. 겹치면 핀이 둘로 보이고, 지도를 움직이면 어긋난다.
 * **원래 있던 마커의 아이콘을 갈아 끼운다.**
 */
function pinoFocus(i) {
  const p = pinoPlaces[i];
  if (!p) return;
  pinoPicked = i;
  repaintPinoPins();

  const ll = [p.lat, p.lng];
  try {
    if (state.base === 'naver' && state.nmap) {
      state.nmap.panTo(new naver.maps.LatLng(...ll));
    } else if (state.base === 'kakao' && state.kmap) {
      state.kmap.panTo(new kakao.maps.LatLng(...ll));
    } else if (state.base === 'tmap' && state.tmap) {
      state.tmap.setCenter(new Tmapv2.LatLng(...ll));
    } else {
      map.panTo(ll);
    }
  } catch (e) { console.warn('피노 이동 실패', e); }

  document.querySelectorAll('.g-place').forEach((el, k) => {
    el.classList.toggle('on', k === i);
  });
  showPlaceCard(p);
}

/** 지금 고른 것만 빨갛게·크게. 나머지는 보통 크기로 되돌린다. */
function repaintPinoPins() {
  pinoMarks.forEach((m, i) => {
    const on = i === pinoPicked;
    const w = on ? 58 : 40, h = Math.round(w * 160 / 120);
    const url = pinoDataUrl({ w, picked: on, id: (on ? 'on' : 'off') + i });
    try {
      if (state.base === 'naver' && state.nmap) {
        m.setIcon({ url, size: new naver.maps.Size(w, h),
                    anchor: new naver.maps.Point(w / 2, h) });
        m.setZIndex(on ? 300 : 100);
      } else if (state.base === 'kakao' && state.kmap) {
        m.setImage(new kakao.maps.MarkerImage(url, new kakao.maps.Size(w, h),
                     { offset: new kakao.maps.Point(w / 2, h) }));
        m.setZIndex(on ? 300 : 100);
      } else if (state.base === 'tmap' && state.tmap) {
        m.setIconHTML(`<img src="${url}" width="${w}" height="${h}">`);
      } else {
        m.setIcon(L.icon({ iconUrl: url, iconSize: [w, h], iconAnchor: [w / 2, h] }));
        if (on) m.setZIndexOffset(300);
      }
    } catch (e) { /* SDK 마다 메서드가 조금씩 다르다 — 없으면 그냥 둔다 */ }
  });
}

/** 핀을 누르면 뜨는 정보 카드. 네이버에서 가져올 수 있는 것을 다 보인다. */
async function showPlaceCard(p) {
  const box = document.getElementById('place-card');
  box.hidden = false;
  box.innerHTML = `<div class="pc-head"><b></b>
      <button class="pc-x" title="닫기">✕</button></div>
    <p class="pc-load">네이버에서 찾는 중…</p>`;
  box.querySelector('b').textContent = p.name;
  box.querySelector('.pc-x').addEventListener('click', () => { box.hidden = true; });

  let d;
  try {
    d = await fetch('/api/place-card', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: p.name, addr: p.addr, lat: p.lat, lng: p.lng }),
    }).then((r) => r.json());
  } catch (e) { d = { found: false, why: '못 불러왔다' }; }

  const rows = [];
  const add = (k, v) => { if (v !== null && v !== undefined && v !== '') rows.push([k, v]); };
  if (d.found && !d.limited) {
    add('분류', d.category);
    add('영업', d.hours);
    add('주소', d.addr);
    add('전화', d.phone);
    add('방문자 리뷰', d.review_count && d.review_count.toLocaleString() + '건');
    add('별점', d.score);
    add('블로그 리뷰', d.blog_reviews && d.blog_reviews.toLocaleString() + '건');
  }
  add('거리', p.dist_m ? p.dist_m + ' m' : null);
  add('업종(TMAP)', p.kind);

  const imgs = (d.images || []).slice(0, 3)
    .map((u) => `<img src="${u}" alt="">`).join('');

  box.innerHTML = `
    <div class="pc-head">
      <b></b>
      <button class="pc-x" title="닫기">✕</button>
    </div>
    ${d.found && d.naver_name && d.naver_name !== p.name
      ? `<p class="pc-sub">네이버: <b class="nn"></b></p>` : ''}
    ${imgs ? `<div class="pc-imgs">${imgs}</div>` : ''}
    ${rows.length ? `<table class="pc-tab">${rows.map(([k, v]) =>
        `<tr><th>${k}</th><td>${String(v)}</td></tr>`).join('')}</table>` : ''}
    ${!d.found ? `<p class="pc-none">네이버에 없다 — ${d.why || ''}</p>` : ''}
    ${d.limited ? `<p class="pc-none">${d.why}</p>` : ''}
    ${d.url ? `<a class="pc-go" href="${d.url}" target="_blank" rel="noopener">
        네이버에서 열기 ↗</a>` : ''}`;
  box.querySelector('b').textContent = p.name;
  const nn = box.querySelector('.nn');
  if (nn) nn.textContent = d.naver_name;
  box.querySelector('.pc-x').addEventListener('click', () => { box.hidden = true; });
}

/** 챗봇에게 넘길 **지금 화면 상태**.
 *
 * 「1번 주변 맛집 알려줘」 가 되려면 모델이 ①②③ 이 무엇인지 알아야 한다.
 * 담은 지점·고른 핀·지도 중심을 매 요청마다 함께 보낸다.
 *
 * **서버가 따로 기억하지 않는다.** 담은 것을 빼면 다음 요청부터 그냥 사라진다 —
 * 서버에 쌓아 두면 화면에서 지웠는데 모델은 아직 알고 있는 어긋남이 생긴다.
 */
function chatContext() {
  const cart = (state.cart || []).map((p, i) => ({
    no: i + 1, name: p.name, address: p.address || '',
    lat: p.lat, lng: p.lng,
    kind: p.kind === 'pin' ? '직접 찍은 핀' : (p.biz || '촬영지'),
  }));
  const picked = pinoPicked >= 0 ? pinoPlaces[pinoPicked] : null;
  return {
    cart,
    picked: picked ? { name: picked.name, lat: picked.lat, lng: picked.lng,
                       addr: picked.addr } : null,
  };
}

/* ── 떠 있는 챗봇 (v6) ──────────────────────────────────────────────────── */
function fabToggle(open) {
  const panel = document.getElementById('fab-panel');
  const btn = document.getElementById('fab');
  const show = open === undefined ? panel.hidden : open;
  panel.hidden = !show;
  btn.classList.toggle('on', show);
  btn.textContent = show ? '✕' : '✨';
  if (show) setTimeout(() => gEl('inp')?.focus(), 30);
}

/** 지도를 사람이 움직이면 살아 있는 피노를 지운다.
 *
 * 그건 지도 위에 겹친 DOM 이라 지도를 옮기면 **엉뚱한 자리를 가리킨 채 남는다.**
 * 어긋난 채로 두는 것보다 없는 편이 낫다 — 정지 핀은 그대로 있으니 위치는
 * 여전히 보인다. `pinoFocus` 가 옮기는 것은 우리가 옮기는 것이라 제외해야 하는데,
 * 잠깐 사이를 두어 그 이동이 끝난 뒤부터 듣는다.
 */
function watchMapMove() {
  let armed = false;
  const drop = () => { if (armed) killPinoAlive(); };
  setInterval(() => { armed = !!pinoAliveEl; }, 600);
  try {
    map.on('movestart zoomstart', drop);
    if (state.nmap) naver.maps.Event.addListener(state.nmap, 'dragstart', drop);
    if (state.kmap) kakao.maps.event.addListener(state.kmap, 'dragstart', drop);
    if (state.tmap) state.tmap.addListener('dragstart', drop);
  } catch (e) { /* 배경이 아직 안 떴으면 다음에 붙는다 */ }
}

function setupFab() {
  if (!V.has('fab')) return;
  document.getElementById('fab-wrap').hidden = false;
  // v6 은 떠 있는 창을 쓰므로 왼쪽 패널의 가이드 상자는 숨긴다 —
  // 같은 기능이 두 군데 있으면 어느 쪽을 눌러야 하는지 알 수 없다.
  const old = document.getElementById('guide-box');
  if (old) old.hidden = true;

  document.getElementById('fab').addEventListener('click', () => fabToggle());
  document.getElementById('fab-close').addEventListener('click', () => fabToggle(false));
  document.getElementById('fab-send').addEventListener('click', guideSend);
  document.getElementById('fab-in').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.isComposing) guideSend();
  });
  // 지도를 보다가 Esc 로 닫는다
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !document.getElementById('fab-panel').hidden) fabToggle(false);
  });
}

function setupGuide() {
  if (!V.has('guide')) return;
  if (V.has('fab')) return;      // v6 은 떠 있는 창을 쓴다 — 왼쪽 상자는 안 켠다
  document.getElementById('guide-box').hidden = false;
  document.getElementById('guide-send').addEventListener('click', guideSend);
  document.getElementById('guide-in').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.isComposing) guideSend();
  });
}

setupV3();
setupEnginePick();
setupPoiSearch();
setupGuide();
setupFab();
// 배경 지도가 다 뜬 뒤에 걸어야 한다
setTimeout(watchMapMove, 2500);

boot();
