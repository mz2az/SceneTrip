/* 피노 핀 — 본 저장소(`~/workspace/SceneTrip`)의 마스코트를 웹으로 옮긴 것.
 *
 * 원본은 SwiftUI(`apps/scenetrip-ios/src/Onboarding/PinoShapes.swift`,
 * `PinoMascot.swift`)와 Kotlin 이라 웹에서 쓸 수 없다. **좌표와 색을 그대로
 * 옮겨** SVG 로 다시 그렸다. 120×160 좌표계도 원본과 같게 두었다 — 나중에
 * 원본이 바뀌면 숫자만 맞춰 보면 된다.
 *
 * 핀 끝(60,141)이 좌표를 가리킨다. 지도에 앉힐 때 **아래쪽 가운데**를 기준으로
 * 잡아야 한다.
 */
const PINO = {
  // 원본 PinImage / Pino 의 색을 그대로
  deep: '#7B69ED',      // 보라  (0.48,0.41,0.93)
  light: '#8FCCF7',     // 하늘  (0.56,0.80,0.97)
  pickTop: '#FF736B',   // 고른 것 — 빨강
  blush: '#F7A8C0',
  eye: '#4A3FA8',
  nose: '#F0849F',
};

/** 물방울 — 원본 `PinoTeardrop`.
 *  중심 (60,54) 반지름 42 의 호를 0.75π→0.25π 로 그리고 (60,141) 로 모은다. */
function pinoTeardropPath() {
  const cx = 60, cy = 54, r = 42;
  const a0 = Math.PI * 0.75, a1 = Math.PI * 0.25;
  const x0 = cx + r * Math.cos(a0), y0 = cy + r * Math.sin(a0);
  const x1 = cx + r * Math.cos(a1), y1 = cy + r * Math.sin(a1);
  // 시계방향으로 큰 호(위쪽)를 지난다
  return `M ${x0} ${y0} A ${r} ${r} 0 1 1 ${x1} ${y1} L 60 141 Z`;
}

const tri = (p) => `M ${p[0]} ${p[1]} L ${p[2]} ${p[3]} L ${p[4]} ${p[5]} Z`;

/**
 * 피노 핀 SVG 문자열.
 * @param {object} o
 * @param {number} o.w      너비(px). 높이는 4:3 비율로 따라온다
 * @param {boolean} o.picked 고른 것인가 — 빨강으로 바뀐다
 * @param {string} o.id     그라데이션 id 가 겹치면 색이 섞인다. 마커마다 다르게
 */
function pinoSvg({ w = 44, picked = false, id = 'p' } = {}) {
  const h = Math.round(w * 160 / 120);
  const g = `pino-g-${id}`;
  const [c0, c1] = picked ? ['#FF735B', '#E32933'] : [PINO.light, PINO.deep];
  return `
<svg width="${w}" height="${h}" viewBox="0 0 120 160" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="${g}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${c0}"/><stop offset="1" stop-color="${c1}"/>
    </linearGradient>
    <filter id="sh-${id}" x="-30%" y="-20%" width="160%" height="150%">
      <feDropShadow dx="0" dy="2" stdDeviation="3" flood-opacity="0.28"/>
    </filter>
  </defs>
  <g filter="url(#sh-${id})">
    <!-- 귀 (물방울보다 먼저 — 뒤로 간다) -->
    <path d="${tri([25, 39, 28, 2, 54, 18])}" fill="url(#${g})" stroke="#fff"
          stroke-width="3" stroke-linejoin="round"/>
    <path d="${tri([95, 39, 92, 2, 66, 18])}" fill="url(#${g})" stroke="#fff"
          stroke-width="3" stroke-linejoin="round"/>
    <path d="${tri([32, 34, 34, 13, 47, 22])}" fill="${PINO.blush}"/>
    <path d="${tri([88, 34, 86, 13, 73, 22])}" fill="${PINO.blush}"/>

    <!-- 물방울 -->
    <path d="${pinoTeardropPath()}" fill="url(#${g})" stroke="#fff" stroke-width="3.5"/>

    <!-- 얼굴 -->
    <circle cx="60" cy="52" r="31" fill="#fff"/>
    <circle cx="43.5" cy="58" r="4" fill="${PINO.blush}" opacity="0.55"/>
    <circle cx="76.5" cy="58" r="4" fill="${PINO.blush}" opacity="0.55"/>
    <ellipse cx="49" cy="47" rx="4.2" ry="5.6" fill="${PINO.eye}"/>
    <ellipse cx="71" cy="47" rx="4.2" ry="5.6" fill="${PINO.eye}"/>
    <circle cx="50.4" cy="45" r="1.5" fill="#fff"/>
    <circle cx="72.4" cy="45" r="1.5" fill="#fff"/>
    <path d="${tri([56, 57, 64, 57, 60, 62])}" fill="${PINO.nose}"/>
  </g>
</svg>`.trim();
}

/** `data:` URL 로. 지도 SDK 는 대개 이미지 주소를 받는다. */
function pinoDataUrl(opt) {
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(pinoSvg(opt));
}


/* ── 살아 있는 피노 (v6) ────────────────────────────────────────────────────
 *
 * 앱에는 GIF 가 없다. `PinoMascot.breathe()` 가 **코드로 그리는 애니메이션**이라
 * 웹에서도 같은 방식으로 옮길 수 있다. 값은 원본 그대로 —
 *
 *   꼬리 살랑   1.2초 왕복 무한        (-7° ↔ 9°, 회전축 80,104)
 *   눈 깜빡     2.6~3.4초마다 110 ms   **불규칙해야** 살아 보인다
 *   귀 씰룩     세 번에 한 번 160 ms   (왼 -7° · 오 5°)
 *
 * 원본 주석 그대로 — "같은 주기로 반복하면 기계처럼 보이므로 사이를 조금씩
 * 벌려 둔다." 그래서 깜빡임 주기를 3.4초/2.6초로 번갈아 둔다.
 *
 * **DOM 요소로 만든다.** 지도 마커에 넣는 정지 SVG(`pinoSvg`)와 달리 이건
 * 애니메이션이 필요해서 `<img>` 로는 안 된다.
 */
function pinoAlive({ w = 64, picked = false, id = 'a' } = {}) {
  const h = Math.round(w * 160 / 120);
  const g = `pino-a-${id}`;
  const [c0, c1] = picked ? ['#FF735B', '#E32933'] : [PINO.light, PINO.deep];
  const el = document.createElement('div');
  el.className = 'pino-alive';
  el.style.width = w + 'px';
  el.style.height = h + 'px';
  el.innerHTML = `
<svg viewBox="0 0 120 160" width="${w}" height="${h}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="${g}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${c0}"/><stop offset="1" stop-color="${c1}"/>
    </linearGradient>
  </defs>
  <!-- 꼬리 — 물방울 뒤로 -->
  <path class="pino-tail"
        d="M 78 106 C 100 110 112 92 101 74"
        fill="none" stroke="url(#${g})" stroke-width="10" stroke-linecap="round"/>
  <!-- 귀 -->
  <g class="pino-ear-l">
    <path d="${tri([25, 39, 28, 2, 54, 18])}" fill="url(#${g})" stroke="#fff"
          stroke-width="3" stroke-linejoin="round"/>
    <path d="${tri([32, 34, 34, 13, 47, 22])}" fill="${PINO.blush}"/>
  </g>
  <g class="pino-ear-r">
    <path d="${tri([95, 39, 92, 2, 66, 18])}" fill="url(#${g})" stroke="#fff"
          stroke-width="3" stroke-linejoin="round"/>
    <path d="${tri([88, 34, 86, 13, 73, 22])}" fill="${PINO.blush}"/>
  </g>
  <!-- 물방울 -->
  <path d="${pinoTeardropPath()}" fill="url(#${g})" stroke="#fff" stroke-width="3.5"/>
  <!-- 얼굴 -->
  <circle cx="60" cy="52" r="31" fill="#fff"/>
  <circle cx="43.5" cy="58" r="4" fill="${PINO.blush}" opacity="0.55"/>
  <circle cx="76.5" cy="58" r="4" fill="${PINO.blush}" opacity="0.55"/>
  <g class="pino-eyes">
    <ellipse cx="49" cy="47" rx="4.2" ry="5.6" fill="${PINO.eye}"/>
    <ellipse cx="71" cy="47" rx="4.2" ry="5.6" fill="${PINO.eye}"/>
    <circle cx="50.4" cy="45" r="1.5" fill="#fff"/>
    <circle cx="72.4" cy="45" r="1.5" fill="#fff"/>
  </g>
  <path d="${tri([56, 57, 64, 57, 60, 62])}" fill="${PINO.nose}"/>
</svg>`;

  // 깜빡임·귀 씰룩은 CSS 로는 불규칙하게 못 만든다 — 원본처럼 타이머로 돈다
  let beat = 0;
  const eyes = el.querySelector('.pino-eyes');
  const ears = [el.querySelector('.pino-ear-l'), el.querySelector('.pino-ear-r')];
  const tick = () => {
    if (!el.isConnected) return;                 // 지워졌으면 멈춘다
    eyes.classList.add('blink');
    setTimeout(() => eyes.classList.remove('blink'), 110);
    if (beat % 3 === 0) {
      ears.forEach((e) => e.classList.add('twitch'));
      setTimeout(() => ears.forEach((e) => e.classList.remove('twitch')), 160);
    }
    beat += 1;
    el._t = setTimeout(tick, beat % 2 === 0 ? 3400 : 2600);
  };
  el._t = setTimeout(tick, 2600);
  el.stop = () => clearTimeout(el._t);
  return el;
}
