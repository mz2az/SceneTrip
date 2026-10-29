// contracts/openapi/scene-api-v1.yaml → src/api/generated/schema.d.ts
// 손으로 쓴 API 클라이언트를 두지 않는다: 타입은 계약에서 생성하고, 호출은 openapi-fetch 가 그 타입으로 한다.
import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { OPENAPI_SPEC, WEB_ROOT } from './paths.mjs'

const out = resolve(WEB_ROOT, 'src/api/generated/schema.d.ts')
mkdirSync(resolve(out, '..'), { recursive: true })
const bin = resolve(WEB_ROOT, 'node_modules/.bin/openapi-typescript')
execFileSync(bin, [OPENAPI_SPEC, '-o', out], { stdio: 'inherit' })
