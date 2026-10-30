/*
 * 마이페이지가 읽는 것들 — iOS `ProfileTabView.load()` (ProfileTab/ProfileTabView.swift:355).
 *
 * **넷을 나란히 받는다.** 앞서 줄줄이 기다렸더니 코스 상세(스탬프용) 하나가 느리면 찜 목록이 그동안 「없습니다」 로 보였다(2026-08-28 버그).
 * 계정이 바뀌면(로그인·로그아웃·탈퇴·기록 삭제) 세대가 달라져 늦은 응답은 버려지고 다시 읽는다.
 */
import { useCallback, useEffect, useState } from 'react'
import type { components } from '@/api/generated/schema'
import { api, installHeader, ok } from '@/api/client'
import { communityStore } from '@/features/community/store'
import { tr } from '@/i18n'
import { cartActions } from '@/state/cart'
import { likeActions, likeStore } from '@/state/likes'
import { accountGeneration, onAccountReset, useOnAccountChange } from '@/state/shared'
import { type VisitStamp, collectStamps } from './stamps'

export type CourseSummary = components['schemas']['CourseSummary']

export interface ProfileData {
  courses: readonly CourseSummary[]
  /** 아직 모르면 null — 줄에 「…」 로 보인다. */
  courseCount: number | null
  stamps: readonly VisitStamp[]
  likesLoading: boolean
  likesFailure: string | null
  reload: () => Promise<void>
}

async function listCourses(): Promise<CourseSummary[] | null> {
  try {
    return (await ok(api.GET('/courses', { params: { header: installHeader() } }))).items
  } catch {
    return null
  }
}

async function getCourse(courseId: number): Promise<components['schemas']['CourseDetail'] | null> {
  try {
    return await ok(api.GET('/courses/{courseId}', { params: { header: installHeader(), path: { courseId } } }))
  } catch {
    return null
  }
}

export function useProfileData(signedIn: boolean): ProfileData {
  const [courses, setCourses] = useState<readonly CourseSummary[]>([])
  const [courseCount, setCourseCount] = useState<number | null>(null)
  const [stamps, setStamps] = useState<readonly VisitStamp[]>([])
  const [likesLoading, setLikesLoading] = useState(true)
  const [likesFailure, setLikesFailure] = useState<string | null>(null)

  const reload = useCallback(async () => {
    const generation = accountGeneration()
    setLikesLoading(true)
    const likesTask = likeActions.refresh()
    const cartTask = cartActions.refresh()
    const coursesTask = listCourses()
    const postsTask = signedIn ? communityStore.api.refresh({ mine: true }) : Promise.resolve(communityStore.api.clearMyPosts())

    // 못 받았어도 앞서 받아 둔 목록이 있으면 그것을 보인다 — 찜은 있는데 목록이 빌 때만 「못 받음」 이다.
    // 오류 원문을 그대로 보이지 않는다 — 서버 문장은 한국어이고 내부 정보가 섞인다(MZ2AZ-345).
    const reached = await likesTask
    if (generation !== accountGeneration()) return
    const { works, contentIds } = likeStore.get()
    setLikesFailure(reached || works.length > 0 || contentIds.size === 0 ? null : tr('찜한 작품을 불러오지 못했어요. 잠시 뒤 다시 해 주세요'))
    setLikesLoading(false)

    await cartTask
    const list = await coursesTask
    if (generation !== accountGeneration()) return
    if (list) {
      setCourses(list)
      setCourseCount(list.length)
    }

    // 방문 스탬프 — 코스마다 상세를 받아 visitedAt 이 찍힌 것만 모은다.
    const collected = await collectStamps(list ?? [], getCourse)
    if (generation !== accountGeneration()) return
    setStamps(collected)
    await postsTask
  }, [signedIn])

  useEffect(() => {
    void reload()
  }, [reload])
  useOnAccountChange(reload)

  // 계정이 떠났다 — 화면 사본을 먼저 비운다(재조회가 실패해도 옛 데이터를 되살리지 않는다).
  useEffect(
    () =>
      onAccountReset(() => {
        setCourses([])
        setCourseCount(0)
        setStamps([])
        setLikesFailure(null)
        setLikesLoading(false)
      }),
    [],
  )

  return { courses, courseCount, stamps, likesLoading, likesFailure, reload }
}
