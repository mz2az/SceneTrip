---
number: 0028
title: 웹앱은 aspect_rules_js 로 Bazel 에 연결한다 (npm 락을 그대로 읽고, 개발 서버만 Bazel 밖)
status: accepted        # 2026-10-31 사용자 승인 (새 툴체인·MODULE.bazel·justfile·tools 수정)
date: 2026-10-31
supersedes:
superseded-by:
amended-by:
---

# ADR 0028: 웹앱은 aspect_rules_js 로 Bazel 에 연결한다 (npm 락을 그대로 읽고, 개발 서버만 Bazel 밖)

## 배경

`apps/scenetrip-web`(Vite + React + TypeScript)은 지금까지 자체 완결형 npm 프로젝트였다. 저장소의 두 법칙(CLAUDE.md §0)은
Bazel 이 모든 것을 빌드하고 `just` 가 모든 것을 실행한다는 것이다. 웹은 새 언어 툴체인이라 `CLAUDE.md` §9 에 따라
사용자 승인이 필요했고, 2026-10-31 사용자가 `MODULE.bazel`·`MODULE.bazel.lock`·루트 `BUILD.bazel`·`justfile`·`tools/` 수정을
포함해 연결을 승인했다(`.github/` 는 제외).

실제 제약:

- 호스팅은 Cloudflare Pages 이고 `npm ci && npm run build` 로 짓는다(`docs/project/plans/web-release.md`). npm 스크립트와
  `package-lock.json` 은 그대로여야 한다. 사용자도 npm 으로 일한다.
- 웹 생성물(API 타입·영어 문구표·해태 그림)은 커밋하지 않는다. 입력이 다른 모듈(`contracts/openapi`, `apps/scenetrip-ios`)에 있다.
- Bazel 8.7.0 고정(`.bazelversion`). 빌드는 호스트 Node·npm 에 의존하면 안 된다(AGENTS.md §4.3).

## 결정

우리는 **`aspect_rules_js` 3.5.2 + `rules_nodejs` 6.7.7(Node 24.22.1 고정)** 로 웹앱을 Bazel 에 연결한다.

- npm 패키지는 `npm_translate_lock` 이 **`package-lock.json` 을 입력**으로 받아 가져온다(저장소 `@npm_web`). rules_js 는 내부 형식으로
  pnpm 잠금이 필요하므로 `update_pnpm_lock = True` 로 `apps/scenetrip-web/pnpm-lock.yaml` 을 **파생**해 커밋한다. 사람이 고치는
  파일이 아니다 — `package.json`/`package-lock.json` 이 바뀌면 다음 Bazel 호출이 알아서 갱신하고, 그 diff 를 같은 커밋에 넣는다.
  `.aspect/rules/external_repository_action_cache/` 의 입력 해시 파일도 함께 커밋한다(rules_js 의 요구).
- 타깃: `:gen`(openapi-typescript 와 iOS 리소스 동기화 스크립트), `:web`(Vite 빌드 → `dist/`), `:unit_test`(vitest, tag `unit`),
  `:lint`(eslint), `:typecheck`(tsc). `just web-build`·`web-test`·`web-lint`·`web-gen`. `just check` 의 `build`·`test` 가 `//...` 이라
  웹 타깃이 저절로 들어가고, `just gen` 은 `:gen` 도 만든다.
- **개발 서버(`just web-dev`)만 Bazel 밖의 `npm run dev`** 다. 소스 트리를 직접 감시해 HMR 해야 하는데 Bazel 은 소스를 bin 에 복사해 돌린다.
  산출물의 정본이 아니라 개발 편의이므로 법칙 1(“소스 오브 트루스”)은 지켜진다.
- `Vite` 번들 액션과 `unit_test` 는 `patch_node_fs = False` 등 몇 가지 조정이 필요했다. Vite 번들은 `.bazelrc` 의
  `--strategy=Vite=local` 로 샌드박스 밖에서 돌린다(이유는 아래 “감수하는 비용”).
- 빌드 중 Aspect 의 사용 통계 수집은 `.bazelrc` 에서 끈다(`DO_NOT_TRACK`).

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| pnpm 으로 전환하고 `pnpm-lock.yaml` 만 정본으로 | 사용자의 npm 워크플로와 Cloudflare 의 `npm ci` 를 바꾼다. 두 잠금이 갈릴 위험은 `update_pnpm_lock` 이 줄인다 |
| `aspect_rules_ts`·`aspect_rules_esbuild` 로 번들 | Vite(rolldown) 설정·플러그인(react, 개발 프록시)을 Bazel 규칙으로 다시 쓰게 된다. 산출물이 `npm run build` 와 달라진다 |
| `rules_nodejs` 의 `npm_install` 류(구식) | 현재 유지보수 중이 아니고 npm 패키지를 하나의 거대한 저장소로 가져와 캐시 효율이 나쁘다 |
| `genrule` 로 호스트 `npm run build` 호출 | 호스트 Node·네트워크에 의존한다. 법칙 1 위반 |
| 웹을 Bazel 에 연결하지 않고 CI 에서 npm 만 | 법칙 위반, `just check` 가 웹을 보지 못한다 |

## 결과

**좋아지는 것**

- `just check` 한 번이 웹의 타입 검사·린트·단위 시험(503)·번들까지 본다. Node/npm 패키지 버전이 한 곳(MODULE.bazel + 잠금)에 고정된다.
- `bazel-bin/apps/scenetrip-web/dist` 가 `npm run build` 결과와 **바이트까지 같다**(실측: `diff -r` 동일, `_headers`·`_redirects` 포함).
- 계약(`contracts/openapi/scene-api-v1.yaml`) 변경이 웹 타입 생성·타입 검사로 이어진다.

**나빠지는 것 / 감수하는 비용**

- 최초 빌드는 규칙·Node·npm 패키지(약 1500 개 파일)를 내려받는다. 이후는 캐시.
- 이행 의존성 갱신: 새 모듈(`aspect_rules_js`, `rules_nodejs`, `bazel_lib`, `hermetic_launcher`, `aspect_tools_telemetry` 등)이 들어오고
  `platforms` 가 1.0.0 → 1.1.0 으로 해석된다(경고만 남고 기존 모듈 빌드는 영향이 없음을 `just check` 로 확인).
- 직접 고치는 파일이 늘었다: `pnpm-lock.yaml`(파생), `pnpm-workspace.yaml`(`allowBuilds: {}`), `REPO.bazel`(`node_modules` 무시).
- Vite 번들은 샌드박스 밖(`local`)에서 돈다. 샌드박스가 입력을 심볼릭 링크로 깔고, 네이티브 rolldown 이 `index.html` 의 실제 경로를
  샌드박스 밖으로 풀어 “번들 루트 밖”이라며 실패한다. `resolve.preserveSymlinks` 는 pnpm 형 `node_modules` 를 못 풀어 쓸 수 없다.
  입력은 여전히 Bazel 이 선언한 것뿐이고 네트워크가 없으며 결과가 결정적이라는 점은 그대로고, 잃는 것은 선언하지 않은 파일을 읽지 못하게
  막는 격리다. 원격 실행으로 옮길 때 다시 본다.
- iOS 그림 목록(`IOS_IMAGES`)은 다른 패키지라 glob 이 닿지 않아 BUILD 에 손으로 적는다. 새 imageset 이 생기고 웹이 쓰면 한 줄 추가.

**후속 작업**

- CI(`.github/`, 이번에 건드리지 않음): `ci-full` 이 웹을 포함하므로 별도 잡은 필요 없지만, 러너에 Node 가 없어도 Bazel 이 받는다.
  `package-lock.json` 이 바뀐 PR 에서 `pnpm-lock.yaml` 도 같이 바뀌어야 하므로 CI 에 `ASPECT_RULES_JS_FROZEN_PNPM_LOCK=1` 을 주면
  “갱신 누락”을 실패로 잡는다(`.github/` 수정은 별도 승인).
- 코드 분할(번들 1.2 MB)과 `puppeteer-core` 스크립트(`shot.mjs`·`e2e-smoke.mjs`)는 Bazel 밖의 도구로 남는다.

## 검증

- `just web-build`·`web-test`·`web-lint` 가 초록이고 vitest 503 개가 Bazel 안에서 통과한다.
- `diff -r apps/scenetrip-web/dist bazel-bin/apps/scenetrip-web/dist` 가 비어 있다.
- 다시 볼 때: Cloudflare Pages 첫 배포 직후, Bazel/rules_js 메이저 업그레이드 때, 원격 실행·원격 캐시를 켤 때.
