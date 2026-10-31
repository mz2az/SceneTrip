/*
 * 사진 확대의 순수 규칙 — iOS `ScenePhotoRules` (SearchTab/ScenePhotoRules.swift, MZ2AZ-420). 100~400 %, 두 번 누르면 200 %.
 * 웹은 UIScrollView 가 없으므로 이동(pan)·고정점 확대는 여기서 계산한다.
 */
export const MIN_SCALE = 1
export const MAX_SCALE = 4
export const DOUBLE_TAP_SCALE = 2

export interface Size {
  width: number
  height: number
}

export interface View {
  scale: number
  /** 그림 가운데에서 얼마나 옮겨졌는가(px). */
  x: number
  y: number
}

export const IDENTITY: View = { scale: MIN_SCALE, x: 0, y: 0 }

export function clampScale(value: number): number {
  if (!Number.isFinite(value)) return MIN_SCALE
  return Math.min(Math.max(value, MIN_SCALE), MAX_SCALE)
}

/** 화면에 맞춰 넣은 크기(비율 유지, 확대 없이 축소만이 아니라 화면을 꽉 채우는 쪽으로 — `scaledToFit`). */
export function fittedSize(image: Size, viewport: Size): Size {
  const ok = [image.width, image.height, viewport.width, viewport.height].every((n) => Number.isFinite(n) && n > 0)
  if (!ok) return { width: 0, height: 0 }
  const ratio = Math.min(viewport.width / image.width, viewport.height / image.height)
  return { width: image.width * ratio, height: image.height * ratio }
}

/** 확대한 그림이 화면 밖으로 나가지 않게 — 그림이 화면보다 작으면 가운데에 둔다. */
export function clampOffset(view: View, fitted: Size, viewport: Size): View {
  const maxX = Math.max(0, (fitted.width * view.scale - viewport.width) / 2)
  const maxY = Math.max(0, (fitted.height * view.scale - viewport.height) / 2)
  return { scale: view.scale, x: Math.min(Math.max(view.x, -maxX), maxX), y: Math.min(Math.max(view.y, -maxY), maxY) }
}

/** 화면 가운데 기준 좌표 `(px, py)` 의 점을 붙든 채 배율을 바꾼다(핀치·휠·두 번 누르기). */
export function zoomAt(view: View, nextScale: number, px: number, py: number, fitted: Size, viewport: Size): View {
  const scale = clampScale(nextScale)
  if (scale === MIN_SCALE) return IDENTITY
  const k = scale / view.scale
  return clampOffset({ scale, x: px - (px - view.x) * k, y: py - (py - view.y) * k }, fitted, viewport)
}

/** 두 번 누름 — 이미 확대돼 있으면 되돌리고, 아니면 그 점을 200 % 로. */
export function doubleTap(view: View, px: number, py: number, fitted: Size, viewport: Size): View {
  return view.scale > MIN_SCALE + 0.01 ? IDENTITY : zoomAt(view, DOUBLE_TAP_SCALE, px, py, fitted, viewport)
}

/** 「−」 「+」 단추 — 반으로, 두 배로. */
export const halve = (view: View, fitted: Size, viewport: Size): View => zoomAt(view, view.scale / 2, 0, 0, fitted, viewport)
export const doubled = (view: View, fitted: Size, viewport: Size): View => zoomAt(view, view.scale * 2, 0, 0, fitted, viewport)

export const percent = (view: View): number => Math.round(view.scale * 100)
