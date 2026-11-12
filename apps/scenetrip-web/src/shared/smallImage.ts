/*
 * 목록·카드의 작은 사진(MZ2AZ-561, 계획 docs/project/plans/photo-thumbnails.md §2-5).
 * 서버가 작은 사진(긴 변 720px JPEG)을 nullable 칸으로 실어 준다 — 아직 없거나 쓸 수 없는 주소면 원본을 그린다.
 * 확대 보기·상세 머리·본문 사진은 이것을 쓰지 않고 원본 그대로다.
 */
import { webUrl } from '@/features/search/rules/webAddress'

/** 작은 사진 주소가 쓸 만하면 그것, 아니면 원본 그대로(손대지 않는다). 둘 다 없으면 null. */
export function smallImage(thumb: string | null | undefined, full: string | null | undefined): string | null {
  return webUrl(thumb) ?? full ?? null
}
