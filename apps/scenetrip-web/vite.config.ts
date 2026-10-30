import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { type Plugin, defineConfig, loadEnv } from 'vite'

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

// 브라우저는 AWS DEV 를 직접 부를 수 없다 — 서버가 CORS 허용 헤더를 주지 않는다
// (증거: docs/project/web-app.md 의 CORS 절). 그래서 개발 서버가 /v1 을 대신 전달한다.
// 배포할 때도 같은 출처에서 /v1 을 API 로 보내는 역프록시가 필요하다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.SCENETRIP_API_TARGET || 'https://api-dev.scenetrip.io'
  return {
    plugins: [react(), ...(env.SCENETRIP_NO_API_CACHE === '1' ? [] : [devApiCache(10 * 60_000)])],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
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
