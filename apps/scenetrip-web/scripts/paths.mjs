// 이 모듈이 저장소 안에서 어디에 있는지를 한 곳에 모은다.
// 폴더 이름이 바뀌어도(apps/web → apps/scenetrip-web, 2026-10-31) 깊이는 그대로라 이 파일은 그대로 동작한다.
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
// apps/<이름>/ 의 두 단계 위가 저장소 루트다.
export const REPO_ROOT = resolve(WEB_ROOT, '../..')
export const OPENAPI_SPEC = resolve(REPO_ROOT, 'contracts/openapi/scene-api-v1.yaml')
// Bazel(`//apps/scenetrip-web:gen`)은 소스 폴더가 읽기 전용이고 산출물이 다른 트리에 놓이므로 두 곳을 환경 변수로 바꾼다.
//  - SCENETRIP_IOS_ROOT   iOS 모듈 위치 (읽기). 기본: 저장소의 apps/scenetrip-ios
//  - SCENETRIP_WEB_GEN_OUT 생성물을 쓸 웹 모듈 루트 (쓰기). 기본: 이 모듈 폴더(npm run gen 은 소스 트리에 쓴다)
// 상대 경로는 Bazel 실행 루트(JS_BINARY__EXECROOT, rules_js 가 준다) 기준이다. 없으면 현재 폴더.
const fromEnv = (name, fallback) => {
  const v = process.env[name]
  return v ? resolve(process.env.JS_BINARY__EXECROOT ?? process.cwd(), v) : fallback
}
export const IOS_ROOT = fromEnv('SCENETRIP_IOS_ROOT', resolve(REPO_ROOT, 'apps/scenetrip-ios'))
export const GEN_OUT = fromEnv('SCENETRIP_WEB_GEN_OUT', WEB_ROOT)
