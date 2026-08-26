import { writeFileSync } from 'node:fs'

// 마스코트. 포즈별로 눈·소품만 갈린다 — 몸통은 지도 핀(PinImage) 그대로다.
const cat = (id, { eyes, mouth, prop = '', shift = 0, w = 186 }) => `
<svg width="${w}" height="${w * 228 / 186}" viewBox="0 0 130 160" style="overflow: visible;">
  <defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8FCCF7"/><stop offset="1" stop-color="#7A68ED"/></linearGradient></defs>
  <ellipse cx="60" cy="150" rx="30" ry="6" fill="#7A68ED" opacity=".14"/>
  <path d="M78 106 C 100 110, 112 92, 101 74" fill="none" stroke="url(#${id})" stroke-width="10" stroke-linecap="round"/>
  <path d="M25 39 L 28 2 L 54 18 Z" fill="url(#${id})" stroke="#fff" stroke-width="3" stroke-linejoin="round"/><path d="M32 34 L 34 13 L 47 22 Z" fill="#F7A8C0"/>
  <path d="M95 39 L 92 2 L 66 18 Z" fill="url(#${id})" stroke="#fff" stroke-width="3" stroke-linejoin="round"/><path d="M88 34 L 86 13 L 73 22 Z" fill="#F7A8C0"/>
  <path d="M30.3 83.7 A 42 42 0 1 1 89.7 83.7 L 60 141 Z" fill="url(#${id})" stroke="#fff" stroke-width="3.5" stroke-linejoin="round"/>
  <circle cx="60" cy="52" r="31" fill="#fff"/>
  ${eyes}
  <path d="M${56.6 + shift} 57 L ${63.4 + shift} 57 L ${60 + shift} 61.2 Z" fill="#F0849F"/>
  ${mouth}
  <g stroke="#B9B3E0" stroke-width="1.5" stroke-linecap="round"><path d="M42 53 L 33 51"/><path d="M42 57 L 33.5 58"/><path d="M78 53 L 87 51"/><path d="M78 57 L 86.5 58"/></g>
  <circle cx="43.5" cy="58" r="4" fill="#F7A8C0" opacity=".55"/><circle cx="76.5" cy="58" r="4" fill="#F7A8C0" opacity=".55"/>
  ${prop}
</svg>`

const openEyes = (dx = 0) => `
  <ellipse cx="${49 + dx}" cy="47" rx="4.2" ry="5.6" fill="#4A3FA8"/><ellipse cx="${71 + dx}" cy="47" rx="4.2" ry="5.6" fill="#4A3FA8"/>
  <circle cx="${50.4 + dx}" cy="45" r="1.5" fill="#fff"/><circle cx="${72.4 + dx}" cy="45" r="1.5" fill="#fff"/>`
const happyEyes = `
  <path d="M43 47 C 46 42, 52 42, 55 47" fill="none" stroke="#4A3FA8" stroke-width="2.4" stroke-linecap="round"/>
  <path d="M65 47 C 68 42, 74 42, 77 47" fill="none" stroke="#4A3FA8" stroke-width="2.4" stroke-linecap="round"/>`
const wMouth = (dx = 0) => `<path d="M${60 + dx} 61.2 C ${60 + dx} 65, ${56 + dx} 66.5, ${53.6 + dx} 63.6 M${60 + dx} 61.2 C ${60 + dx} 65, ${64 + dx} 66.5, ${66.4 + dx} 63.6" fill="none" stroke="#4A3FA8" stroke-width="1.8" stroke-linecap="round"/>`
const smile = `<path d="M53 62 C 56 68, 64 68, 67 62" fill="none" stroke="#4A3FA8" stroke-width="2" stroke-linecap="round"/>`

const dots = (active) => Array.from({ length: 4 }, (_, i) =>
  `<div style="width: ${i === active ? 20 : 7}px; height: 7px; border-radius: 4px; background: ${i === active ? '#0088FF' : 'rgba(60,60,67,.22)'};"></div>`).join('')

const page = ({ file, index, art, title, body, ko, cta, last }) => writeFileSync(file, `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <style>
    body { margin: 0; font-family: -apple-system, "SF Pro Text", system-ui, sans-serif; -webkit-font-smoothing: antialiased; }
  </style>
</helmet>
<div style="width: 402px; height: 874px; background: #fff; display: flex; flex-direction: column; overflow: hidden;">

  <div style="height: 59px; flex-shrink: 0;"></div>

  <!-- 건너뛰기. 넷째 장에서는 없앤다 — 거기 버튼이 이미 「시작하기」다 -->
  <div style="height: 44px; display: flex; align-items: center; justify-content: flex-end; padding: 0 16px; flex-shrink: 0;">
    ${last ? '' : '<div style="font-size: 17px; color: rgba(60,60,67,.6);">Skip</div>'}
  </div>

  <!-- 그림 -->
  <div style="flex-grow: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 0 24px; gap: 4px;">
    ${art}
  </div>

  <!-- 글. 영어가 본문이고 한국어는 보조줄이다 — 외국인 대상 앱이라 -->
  <div style="padding: 0 32px; text-align: center; flex-shrink: 0;">
    <div style="font-size: 28px; font-weight: 700; color: #000; letter-spacing: -.5px; line-height: 1.25;">${title}</div>
    <div style="margin-top: 12px; font-size: 16px; color: rgba(60,60,67,.6); line-height: 1.5;">${body}</div>
    <div style="margin-top: 14px; font-size: 13px; color: rgba(60,60,67,.3); line-height: 1.45;">${ko}</div>
  </div>

  <!-- 점 -->
  <div style="display: flex; gap: 6px; justify-content: center; align-items: center; padding: 30px 0 22px; flex-shrink: 0;">
    ${dots(index)}
  </div>

  <!-- 버튼. 50pt — 앱의 다른 큰 버튼과 같은 높이다 -->
  <div style="padding: 0 16px 40px; flex-shrink: 0;">
    <div style="height: 50px; border-radius: 12px; background: #0088FF; color: #fff; font-size: 17px; font-weight: 600; display: flex; align-items: center; justify-content: center; gap: 7px;">${cta}</div>
  </div>
</div>
</x-dc>
</body>
</html>
`)

// ── ① 찾는다 ────────────────────────────────────────────────
page({
  file: 'Tutorial1.dc.html', index: 0,
  art: `
    <div style="position: relative; width: 300px; height: 300px; display: flex; align-items: center; justify-content: center;">
      <!-- 지도 조각 -->
      <svg width="300" height="300" viewBox="0 0 300 300" style="position: absolute; inset: 0;">
        <rect x="18" y="30" width="264" height="240" rx="20" fill="#F2F2F7"/>
        <g stroke="#DCDCE1" stroke-width="7" fill="none" stroke-linecap="round">
          <path d="M18 120 H 282"/><path d="M18 205 H 282"/><path d="M96 30 V 270"/><path d="M212 30 V 270"/>
        </g>
        <rect x="18" y="30" width="264" height="240" rx="20" fill="none" stroke="#E5E5EA" stroke-width="2"/>
        <!-- 다른 촬영지 핀 -->
        <g opacity=".5">
          <path d="M46.7 62.5 A 13 13 0 1 1 65.3 62.5 L 56 80 Z" fill="#B9C9F0"/>
          <path d="M232.7 232.5 A 13 13 0 1 1 251.3 232.5 L 242 250 Z" fill="#B9C9F0"/>
          <path d="M244.7 46.5 A 13 13 0 1 1 263.3 46.5 L 254 64 Z" fill="#B9C9F0"/>
        </g>
      </svg>
      <div style="position: relative;">${cat('t1', { eyes: openEyes(), mouth: wMouth(), prop: '<g transform="translate(90 88) rotate(20)"><circle cx="0" cy="0" r="17" fill="rgba(255,255,255,.6)" stroke="#7A68ED" stroke-width="4.5"/><path d="M12 12 L 24 24" stroke="#7A68ED" stroke-width="5.5" stroke-linecap="round"/></g>' })}</div>
    </div>
    <!-- 검색창 -->
    <div style="width: 100%; max-width: 300px; height: 40px; border-radius: 10px; background: #F2F2F7; display: flex; align-items: center; gap: 8px; padding: 0 12px; box-sizing: border-box; margin-top: 6px;">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="rgba(60,60,67,.6)" stroke-width="2.4" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M16.5 16.5 L 21 21"/></svg>
      <div style="font-size: 15px; color: #000;">눈물의 여왕</div>
      <div style="width: 1.5px; height: 18px; background: #0088FF; margin-left: -4px;"></div>
    </div>`,
  title: 'Where the scene<br>was filmed',
  body: 'Search by drama, movie, or the scene itself.<br>Real locations, straight onto the map.',
  ko: '드라마 이름으로도, 장면 설명으로도 찾는다',
  cta: 'Next',
})

// ── ② 짜 준다 ───────────────────────────────────────────────
page({
  file: 'Tutorial2.dc.html', index: 1,
  art: `
    <div style="position: relative; width: 320px; height: 300px; display: flex; align-items: center; justify-content: center;">
      <!-- 일차 카드 세 장 -->
      <div style="position: absolute; left: 0; top: 24px; display: flex; flex-direction: column; gap: 8px;">
        <div style="width: 128px; border-radius: 12px; background: #fff; box-shadow: 0 2px 10px rgba(0,0,0,.10); padding: 9px 11px;">
          <div style="font-size: 11px; font-weight: 900; color: #7A68ED;">1일차</div>
          <div style="font-size: 12px; color: #000; margin-top: 3px;">덕수궁 돌담길</div>
          <div style="font-size: 12px; color: #000;">정동길 · 서울시청</div>
        </div>
        <div style="width: 128px; border-radius: 12px; background: #fff; box-shadow: 0 2px 10px rgba(0,0,0,.10); padding: 9px 11px;">
          <div style="font-size: 11px; font-weight: 900; color: #7A68ED;">2일차</div>
          <div style="font-size: 12px; color: #000; margin-top: 3px;">북촌한옥마을</div>
          <div style="font-size: 12px; color: #000;">삼청동길 · 경복궁</div>
        </div>
        <div style="width: 128px; border-radius: 12px; background: #fff; box-shadow: 0 2px 10px rgba(0,0,0,.10); padding: 9px 11px; opacity: .55;">
          <div style="font-size: 11px; font-weight: 900; color: #7A68ED;">3일차</div>
          <div style="font-size: 12px; color: #000; margin-top: 3px;">주문진 방파제</div>
        </div>
      </div>
      <div style="position: absolute; right: 4px;">${cat('t2', { eyes: happyEyes, mouth: wMouth(), prop: '<g fill="#FFD426"><path d="M104 16 l3.6 8.4 8.4 3.6 -8.4 3.6 -3.6 8.4 -3.6 -8.4 -8.4 -3.6 8.4 -3.6z"/><path d="M14 70 l2.4 5.6 5.6 2.4 -5.6 2.4 -2.4 5.6 -2.4 -5.6 -5.6 -2.4 5.6 -2.4z"/></g>' })}</div>
    </div>`,
  title: 'Set your pace.<br>PINO plans the days.',
  body: 'Packed fits 5 stops a day, Easy fits 3.<br>Nearby spots get grouped, day by day.',
  ko: '빡빡하게 하루 5곳 · 널널하게 3곳',
  cta: 'Next',
})

// ── ③ 데려간다 ──────────────────────────────────────────────
page({
  file: 'Tutorial3.dc.html', index: 2,
  art: `
    <div style="position: relative; width: 320px; height: 300px; display: flex; align-items: center; justify-content: center;">
      <svg width="320" height="300" viewBox="0 0 320 300" style="position: absolute; inset: 0;">
        <!-- 도보(점선) → 지하철(실선) → 도보 -->
        <path d="M56 246 C 76 232, 84 216, 104 208" fill="none" stroke="#0088FF" stroke-width="5" stroke-linecap="round" stroke-dasharray="1 9"/>
        <path d="M104 208 C 150 186, 176 120, 214 92" fill="none" stroke="#7A68ED" stroke-width="6" stroke-linecap="round"/>
        <path d="M214 92 C 234 78, 244 62, 258 54" fill="none" stroke="#0088FF" stroke-width="5" stroke-linecap="round" stroke-dasharray="1 9"/>
        <circle cx="56" cy="246" r="9" fill="#fff" stroke="#0088FF" stroke-width="5"/>
        <circle cx="104" cy="208" r="6" fill="#7A68ED"/><circle cx="214" cy="92" r="6" fill="#7A68ED"/>
        <path d="M248.7 40.5 A 13 13 0 1 1 267.3 40.5 L 258 58 Z" fill="#7A68ED"/>
        <g font-family="-apple-system, system-ui" font-size="11" font-weight="600" fill="rgba(60,60,67,.6)">
          <text x="42" y="272">현재 위치</text><text x="112" y="222">도보 4분</text>
          <text x="168" y="156">2호선 · 6개 역</text><text x="228" y="80">도보 6분</text>
        </g>
      </svg>
      <div style="position: absolute; left: -6px; top: -8px;">${cat('t3', { w: 128, eyes: openEyes(3), shift: 1.5, mouth: wMouth(1.5), prop: '<path d="M84 76 C 98 84, 105 90, 111 99" fill="none" stroke="#fff" stroke-width="16" stroke-linecap="round"/><path d="M84 76 C 98 84, 105 90, 111 99" fill="none" stroke="url(#t3)" stroke-width="10" stroke-linecap="round"/><g transform="translate(114 104)"><ellipse cx="0" cy="0" rx="17" ry="13" fill="url(#t3)" stroke="#fff" stroke-width="3.5"/><circle cx="-6.5" cy="-7.5" r="3.8" fill="#F7A8C0"/><circle cx="2.5" cy="-10" r="3.8" fill="#F7A8C0"/><circle cx="10.5" cy="-5.5" r="3.8" fill="#F7A8C0"/></g>' })}</div>
    </div>`,
  title: 'Tap Directions<br>when you feel like going',
  body: 'From wherever you are standing — subway,<br>bus, and every turn of the walk.',
  ko: '지금 서 있는 자리에서 지하철·버스·골목까지',
  cta: 'Next',
})

// ── ④ 거든다 ────────────────────────────────────────────────
page({
  file: 'Tutorial4.dc.html', index: 3, last: true,
  art: `
    <div style="position: relative; width: 320px; height: 300px; display: flex; align-items: center; justify-content: center;">
      <!-- 반경 -->
      <svg width="320" height="300" viewBox="0 0 320 300" style="position: absolute; inset: 0;">
        <circle cx="160" cy="152" r="131" fill="#0088FF" opacity=".06"/>
        <circle cx="160" cy="152" r="131" fill="none" stroke="#0088FF" stroke-width="1.5" stroke-dasharray="6 6" opacity=".45"/>
      </svg>
      <!-- 갈래 넷. 색은 RouteNavMock.tone 그대로 -->
      <div style="position: absolute; left: 8px; top: 40px; display: flex; align-items: center; gap: 6px; background: #fff; border-radius: 100px; padding: 6px 12px 6px 8px; box-shadow: 0 2px 10px rgba(0,0,0,.10);">
        <div style="width: 9px; height: 9px; border-radius: 5px; background: #F0942C;"></div><div style="font-size: 13px; font-weight: 600;">음식점</div>
      </div>
      <div style="position: absolute; right: 2px; top: 148px; display: flex; align-items: center; gap: 6px; background: #fff; border-radius: 100px; padding: 6px 12px 6px 8px; box-shadow: 0 2px 10px rgba(0,0,0,.10);">
        <div style="width: 9px; height: 9px; border-radius: 5px; background: #2ECC70;"></div><div style="font-size: 13px; font-weight: 600;">명소</div>
      </div>
      <div style="position: absolute; left: 10px; bottom: 62px; display: flex; align-items: center; gap: 6px; background: #fff; border-radius: 100px; padding: 6px 12px 6px 8px; box-shadow: 0 2px 10px rgba(0,0,0,.10);">
        <div style="width: 9px; height: 9px; border-radius: 5px; background: #0088FF;"></div><div style="font-size: 13px; font-weight: 600;">교통</div>
      </div>
      <div style="position: absolute; right: 18px; bottom: 8px; display: flex; align-items: center; gap: 6px; background: #fff; border-radius: 100px; padding: 6px 12px 6px 8px; box-shadow: 0 2px 10px rgba(0,0,0,.10);">
        <div style="width: 9px; height: 9px; border-radius: 5px; background: #7A68ED;"></div><div style="font-size: 13px; font-weight: 600;">숙소</div>
      </div>
      <div style="position: relative;">${cat('t4', { eyes: openEyes(), mouth: smile, prop: '<g transform="translate(124 10)"><rect x="-24" y="-16" width="50" height="32" rx="13" fill="#0088FF"/><path d="M-13 14 L -18 24 L -4 16 Z" fill="#0088FF"/><g fill="#fff"><circle cx="-12" cy="0" r="2.8"/><circle cx="1" cy="0" r="2.8"/><circle cx="14" cy="0" r="2.8"/></g></g>' })}</div>
    </div>`,
  title: 'Eat on the way.<br>Ask when you are stuck.',
  body: 'Restaurants, sights, transit and stays<br>around you. PINO handles the Korean.',
  ko: '반경 안의 음식점·명소·교통·숙소, 그리고 챗봇',
  cta: 'Get started',
})

console.log('wrote Tutorial1..4')
