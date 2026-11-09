/*
 * 도시 대표 사진 — 사용자가 도시마다 한 장씩 고른 것(2026-11-09, 후보는 DEV 촬영지 인기순 상위). MZ2AZ-545.
 * 홈 사진 머리가 촬영지 목록(476곳 × 2개 언어)을 기다리지 않고 바로 뜨게 웹에 함께 싣는다. 원본(217~645KB)을 가로 720~800px 로 줄였다.
 * `placeId` 는 DEV·PRD 같은 촬영지 번호, 이름·작품은 그 촬영지의 목록 응답(ko·en)에서 옮겼다.
 */
import busan from './covers/busan.jpg'
import daegu from './covers/daegu.jpg'
import gyeongju from './covers/gyeongju.jpg'
import incheon from './covers/incheon.jpg'
import jeju from './covers/jeju.jpg'
import pohang from './covers/pohang.jpg'
import seoul from './covers/seoul.jpg'
import suwon from './covers/suwon.jpg'

export interface CityCover {
  /** 도시 열쇠 — `homeCities.ts` 의 한국어 열쇠. */
  key: string
  city: { ko: string; en: string }
  placeId: number
  place: { ko: string; en: string }
  work: { ko: string; en: string }
  src: string
}

/** 사진 머리에 도는 순서 — 홈 도시 줄의 고정 다섯, 그다음 촬영지 수 순. */
export const CITY_COVERS: readonly CityCover[] = [
  { key: '서울', city: { ko: '서울', en: 'Seoul' }, placeId: 186, place: { ko: '뚝섬한강공원', en: 'Ttukseom Hangang Park' }, work: { ko: '마이데몬', en: 'My Demon' }, src: seoul },
  { key: '부산', city: { ko: '부산', en: 'Busan' }, placeId: 403, place: { ko: '감천문화마을', en: 'Gamcheon Culture Village' }, work: { ko: '유미의 세포들', en: "Yumi's Cells" }, src: busan },
  { key: '경주', city: { ko: '경주', en: 'Gyeongju' }, placeId: 390, place: { ko: '경주 오릉', en: 'Gyeongju Oreung Royal Tombs' }, work: { ko: '21세기 대군부인', en: 'Perfect Crown' }, src: gyeongju },
  { key: '인천', city: { ko: '인천', en: 'Incheon' }, placeId: 199, place: { ko: '월미도', en: 'Wolmido' }, work: { ko: '브로커', en: 'Broker' }, src: incheon },
  { key: '제주', city: { ko: '제주', en: 'Jeju' }, placeId: 295, place: { ko: '군산오름', en: 'Gunsan Oreum' }, work: { ko: '폭싹 속았수다', en: 'When Life Gives You Tangerines' }, src: jeju },
  { key: '수원', city: { ko: '수원', en: 'Suwon' }, placeId: 410, place: { ko: '화성행궁', en: 'Hwaseong Haenggung Palace' }, work: { ko: '왕의 남자', en: 'King and the Clown' }, src: suwon },
  { key: '포항', city: { ko: '포항', en: 'Pohang' }, placeId: 195, place: { ko: '호미곶해맞이광장', en: 'Homigot Sunrise Plaza' }, work: { ko: '스프링 피버', en: 'Spring Fever' }, src: pohang },
  { key: '대구', city: { ko: '대구', en: 'Daegu' }, placeId: 140, place: { ko: '서문시장', en: 'Seomun Market' }, work: { ko: '김비서가 왜 그럴까', en: "What's Wrong with Secretary Kim" }, src: daegu },
]

const byKey = new Map(CITY_COVERS.map((c) => [c.key, c]))

/** 고른 대표 사진이 있는 도시면 그 사진. */
export const fixedCoverOf = (key: string): CityCover | undefined => byKey.get(key)
