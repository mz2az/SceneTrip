import { readFileSync, realpathSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { type Plugin, defineConfig, loadEnv, searchForWorkspaceRoot } from 'vite'

/**
 * 개발 서버 전용 — DEV API 의 GET 200 응답을 메모리에 잠깐 둔다(기본 10분).
 * DEV 는 IP 당 분당 약 60 회라 화면 확인을 연달아 돌리면 429 가 나고(`docs/project/web-app.md` §7-9) 서버에도 부담이다.
 * 응답은 바이트 그대로 돌려주고, 200 이 아닌 것·GET 이 아닌 것은 건드리지 않는다. `SCENETRIP_NO_API_CACHE=1` 로 끈다. 제품 빌드에는 들어가지 않는다(`apply: 'serve'`).
 */
function devApiCache(ttlMs: number): Plugin {
  const cache = new Map<string, { status: number; headers: Record<string, string | string[]>; body: Buffer; at: number }>()
  return {
    name: 'scenetrip-dev-api-cache',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.method !== 'GET' || !req.url?.startsWith('/v1/')) return next()
        // 압축을 풀어 둔 바이트를 그대로 보관하려고 압축을 받지 않는다.
        req.headers['accept-encoding'] = 'identity'
        const key = `${req.url}|${req.headers['accept-language'] ?? ''}|${req.headers.authorization ?? ''}`
        const hit = cache.get(key)
        if (hit && Date.now() - hit.at < ttlMs) {
          res.writeHead(hit.status, hit.headers)
          res.end(hit.body)
          return
        }
        const chunks: Buffer[] = []
        const write = res.write.bind(res)
        const end = res.end.bind(res)
        res.write = ((chunk: unknown, ...rest: unknown[]) => {
          if (chunk) chunks.push(Buffer.from(chunk as Buffer))
          return (write as (...a: unknown[]) => boolean)(chunk, ...rest)
        }) as typeof res.write
        res.end = ((chunk?: unknown, ...rest: unknown[]) => {
          if (chunk && typeof chunk !== 'function') chunks.push(Buffer.from(chunk as Buffer))
          if (res.statusCode === 200) {
            const headers = { ...res.getHeaders() } as Record<string, string | string[]>
            delete headers['content-length']
            cache.set(key, { status: 200, headers, body: Buffer.concat(chunks), at: Date.now() })
          }
          return (end as (...a: unknown[]) => typeof res)(chunk, ...rest)
        }) as typeof res.end
        next()
      })
    },
  }
}

/**
 * 첫 화면(web-ad-landing.md §10-3-1 · §10-3-2 · §11).
 *
 *   1. index.html 의 `%SCENETRIP_INTRO_HAETAE%` 에 해태 그림(src/features/landing/intro-haetae.webp, 10KB 이하)을 data URI 로 끼운다 —
 *      JS 를 받기 전에 그려지는 HTML 첫 화면이 그림 하나 때문에 또 왕복하지 않게.
 *   2. 빌드에서만: 첫 화면 덮개 덩어리(LandingCover)와 앱 덩어리(AppRoot)는 동적 import 라 진입 JS 를 다 받고 실행해야 요청이 나간다.
 *      HTML 의 인라인 스크립트가 처음 온 사람이면 덮개 덩어리를, 아니면 앱 덩어리를 `modulepreload` 로 미리 받게 한다(진입 JS 와 나란히).
 *      덩어리 이름은 빌드가 정하므로 결과물에서 읽는다 — 입력이 같으면 결과도 같다(ADR 0028 의 Bazel·npm 동일성).
 */
function introFirstScreen(): Plugin {
  const haetae = () => `data:image/webp;base64,${readFileSync(new URL('./src/features/landing/intro-haetae.webp', import.meta.url)).toString('base64')}`
  return {
    name: 'scenetrip-intro-first-screen',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        let out = html.replace('%SCENETRIP_INTRO_HAETAE%', haetae())
        const bundle = ctx.bundle
        if (!bundle) return out
        const chunks = Object.values(bundle).filter((c) => c.type === 'chunk')
        const byName = new Map(chunks.map((c) => [c.fileName, c]))
        const closure = (suffix: string): string[] => {
          const start = chunks.find((c) => c.facadeModuleId?.endsWith(suffix))
          if (!start) return []
          const seen = new Set<string>()
          const walk = (file: string) => {
            const c = byName.get(file)
            if (!c || c.isEntry || seen.has(file)) return
            seen.add(file)
            c.imports.forEach(walk)
          }
          walk(start.fileName)
          return [...seen]
        }
        const cssOf = (files: string[]): string[] =>
          files.flatMap((f) => [...((byName.get(f) as { viteMetadata?: { importedCss?: Set<string> } } | undefined)?.viteMetadata?.importedCss ?? [])])
        const intro = closure('/src/features/landing/LandingCover.tsx')
        const app = closure('/src/app/AppRoot.tsx')
        if (intro.length === 0 && app.length === 0) return out
        const pick = (files: string[]) => ({ js: files.map((f) => '/' + f), css: cssOf(files).map((f) => '/' + f) })
        // 스타일은 rel=stylesheet 로 미리 넣는다(동적으로 넣은 것이라 그리기를 막지 않는다) — 동적 import 가 같은 주소를 보면 다시 넣지 않는다.
        const script =
          `<script>(function(){var p=document.documentElement.classList.contains('st-intro')?${JSON.stringify(pick(intro))}:${JSON.stringify(pick(app))};` +
          `function add(rel,h){var l=document.createElement('link');l.rel=rel;l.crossOrigin='';l.href=h;document.head.appendChild(l)}` +
          `p.js.forEach(function(h){add('modulepreload',h)});p.css.forEach(function(h){add('stylesheet',h)})})()</script>`
        out = out.replace('</head>', `    ${script}\n  </head>`)
        return out
      },
    },
  }
}

// 브라우저는 AWS DEV 를 직접 부를 수 없다 — 서버가 CORS 허용 헤더를 주지 않는다
// (증거: docs/project/web-app.md 의 CORS 절). 그래서 개발 서버가 /v1 을 대신 전달한다.
// 배포할 때도 같은 출처에서 /v1 을 API 로 보내는 역프록시가 필요하다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.SCENETRIP_API_TARGET || 'https://api-dev.scenetrip.io'
  return {
    plugins: [react(), introFirstScreen(), ...(env.SCENETRIP_NO_API_CACHE === '1' ? [] : [devApiCache(10 * 60_000)])],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      // Bazel 의 runfiles 에서는 입력 파일이 심볼릭 링크라 실제 경로가 현재 폴더 밖이다(vitest 가 test-setup.ts 를 못 연다).
      // package.json 의 실제 위치(= 소스를 복사해 둔 bin 폴더)를 허용한다. npm 으로 돌릴 때는 현재 폴더와 같아 아무것도 달라지지 않는다.
      fs: { allow: [searchForWorkspaceRoot(process.cwd()), dirname(realpathSync('package.json'))] },
      port: 5173,
      proxy: { '/v1': { target, changeOrigin: true, secure: true } },
    },
    preview: { proxy: { '/v1': { target, changeOrigin: true, secure: true } } },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
      setupFiles: ['src/test-setup.ts'],
    },
  }
})
