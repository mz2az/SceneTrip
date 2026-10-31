/*
 * 촬영 장면은 실제 씬 주소만 사용한다. 없는 주소는 확대할 사진이 아니다 (MZ2AZ-420).
 * 출처: apps/scenetrip-ios/src/SearchTab/ScenePhotoRules.swift
 */
import type { components } from '@/api/generated/schema'
import type { GalleryPhoto } from '@/features/reviews/photoGalleryRules'
import { webUrl } from './webAddress'

export type SceneItem = components['schemas']['Scene']

export const MIN_SCALE = 1
export const MAX_SCALE = 4
export const DOUBLE_TAP_SCALE = 2

/** 서버 순서 그대로(같은 사진이 반복돼도) — 열 수 없는 주소만 뺀다. 포스터·옛 칸으로 대신 채우지 않는다. */
export function scenePhotos(scene: SceneItem): GalleryPhoto[] {
  const out: GalleryPhoto[] = []
  for (const raw of scene.sceneImageUrls ?? []) {
    const url = webUrl(raw)
    if (!url) continue
    out.push({ key: `s:${scene.contentId}:${url}`, url })
  }
  return out
}

export function scenePhoto(scene: SceneItem): GalleryPhoto | null {
  return scenePhotos(scene)[0] ?? null
}

export function clampScale(value: number): number {
  if (!Number.isFinite(value)) return MIN_SCALE
  return Math.min(Math.max(value, MIN_SCALE), MAX_SCALE)
}

export interface Size {
  width: number
  height: number
}

export interface Point {
  x: number
  y: number
}

/**
 * 확대한 사진을 끌어 옮길 때 사진이 칸 밖으로 달아나지 않게 한다 — 확대한 크기가 칸보다 크면 그만큼만, 작으면 가운데에 둔다
 * (iOS `centerImage` 의 inset 과 같다). `fitted` 는 확대 전에 칸에 맞춘 크기, `viewport` 는 칸 크기.
 */
export function clampPan(offset: Point, scale: number, fitted: Size, viewport: Size): Point {
  const maxX = Math.max(0, (fitted.width * scale - viewport.width) / 2)
  const maxY = Math.max(0, (fitted.height * scale - viewport.height) / 2)
  return { x: Math.min(Math.max(offset.x, -maxX), maxX), y: Math.min(Math.max(offset.y, -maxY), maxY) }
}

/** 눌린 자리를 중심으로 `next` 배율이 되도록 옮김을 다시 계산한다(칸 가운데가 원점). */
export function zoomAbout(offset: Point, scale: number, next: number, anchor: Point): Point {
  const ratio = next / scale
  return { x: anchor.x - (anchor.x - offset.x) * ratio, y: anchor.y - (anchor.y - offset.y) * ratio }
}

/** 그림을 자르지 않고 보이는 칸에 맞춘 크기. 값이 이상하면 0×0. */
export function fittedSize(image: Size, viewport: Size): Size {
  const ok = [image.width, image.height, viewport.width, viewport.height].every((v) => Number.isFinite(v) && v > 0)
  if (!ok) return { width: 0, height: 0 }
  const ratio = Math.min(viewport.width / image.width, viewport.height / image.height)
  return { width: image.width * ratio, height: image.height * ratio }
}
