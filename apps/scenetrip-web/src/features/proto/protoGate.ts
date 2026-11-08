/*
 * 새 화면(시안)을 어디서 켜는가 — MZ2AZ-539, 계획 docs/project/plans/web-proto-apply.md.
 *
 * - 로컬 개발 서버(`just web-dev`): 지금처럼 `?proto=a|b|off` 로 켜고 끈다. 값이 없으면 꺼진다.
 * - 시험용 웹(dev.scenetrip.io = Pages `dev` 브랜치): **기본으로 켠다(시안 A)**. `?proto=off` 로만 끈다.
 * - 운영(그 밖의 주소): 켜지지 않는다.
 * 「시안」 표시·스위치와 서버가 없는 기능(자유게시판·글 검색·좋아요·댓글)은 로컬에서만 보인다.
 */
import type { Proto } from './protoState'

/** 새 화면을 기본으로 켜는 시험용 웹 주소. */
export const DEV_SITE_HOSTS: readonly string[] = ['dev.scenetrip.io', 'dev.scenetrip-app.pages.dev']

export function isDevSite(hostname: string): boolean {
  return DEV_SITE_HOSTS.includes(hostname.toLowerCase())
}

/** 새 화면 장치를 켤 수 있는 곳 — 로컬 개발 서버 또는 시험용 웹. */
export function protoEnabled(dev: boolean, hostname: string): boolean {
  return dev || isDevSite(hostname)
}

/**
 * 어느 시안을 보일까. `asked` 는 주소의 `?proto=`, `stored` 는 탭이 기억한 값. 주소가 이긴다.
 * 옛 시안 C 는 B 의 「알아서 짜 주세요」 로 합쳤다. 값이 없으면 시험용 웹은 A(사용자가 다듬어 온 안), 로컬은 끈다.
 */
export function pickProto({ dev, hostname, asked, stored }: { dev: boolean; hostname: string; asked: string | null; stored: string | null }): Proto | null {
  if (!protoEnabled(dev, hostname)) return null
  if (asked === 'off') return null
  const value = asked ?? stored
  const merged = value === 'c' ? 'b' : value
  if (merged === 'a' || merged === 'b') return merged
  return isDevSite(hostname) ? 'a' : null
}

/** 「시안」 표시·시안 스위치 — 로컬 개발 서버에서만. */
export function showsPrototypeMarks(dev: boolean): boolean {
  return dev
}

/** 서버가 아직 없는 기능(자유게시판·글 검색·좋아요·댓글) — 로컬 개발 서버에서만. */
export function showsLocalOnlyFeatures(dev: boolean): boolean {
  return dev
}
