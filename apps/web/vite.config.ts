import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// 브라우저는 AWS DEV 를 직접 부를 수 없다 — 서버가 CORS 허용 헤더를 주지 않는다
// (증거: docs/project/web-app.md 의 CORS 절). 그래서 개발 서버가 /v1 을 대신 전달한다.
// 배포할 때도 같은 출처에서 /v1 을 API 로 보내는 역프록시가 필요하다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.SCENETRIP_API_TARGET || 'https://api-dev.scenetrip.io'
  return {
    plugins: [react()],
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
