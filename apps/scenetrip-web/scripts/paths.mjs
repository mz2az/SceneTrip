// 이 모듈이 저장소 안에서 어디에 있는지를 한 곳에 모은다.
// 폴더 이름이 바뀌어도(apps/web → apps/scenetrip-web, 2026-10-31) 깊이는 그대로라 이 파일은 그대로 동작한다.
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
// apps/<이름>/ 의 두 단계 위가 저장소 루트다.
export const REPO_ROOT = resolve(WEB_ROOT, '../..')
export const OPENAPI_SPEC = resolve(REPO_ROOT, 'contracts/openapi/scene-api-v1.yaml')
export const IOS_ROOT = resolve(REPO_ROOT, 'apps/scenetrip-ios')
