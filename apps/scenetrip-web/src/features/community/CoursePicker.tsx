/*
 * 내 코스 고르기 — iOS `CoursePickSheet` (CommunityTab/CommunityComposeView.swift). 고르면 **그 코스의 속(일차·장소)을 받아**
 * 글에 붙일 사본을 만든다. 직접 찍은 핀은 공개하지 않는다.
 */
import { useCallback, useEffect, useState } from 'react'
import type { components } from '@/api/generated/schema'
import { api, installHeader, ok } from '@/api/client'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import './icons'
import { type PostCourse, courseFromMine } from './model'
import { PostCourseBadge } from './PostCourseBadge'
import { failureText } from './rules'

type CourseSummary = components['schemas']['CourseSummary']

export function CoursePicker({ onPick, onClose }: { onPick: (course: PostCourse) => void; onClose: () => void }) {
  const [courses, setCourses] = useState<CourseSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [fetching, setFetching] = useState<number | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setMessage(null)
    try {
      setCourses((await ok(api.GET('/courses', { params: { header: installHeader() } }))).items)
    } catch (error) {
      setMessage(failureText(error))
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => {
    void reload()
  }, [reload])

  const pick = async (item: CourseSummary) => {
    setFetching(item.id)
    try {
      const detail = await ok(api.GET('/courses/{courseId}', { params: { path: { courseId: item.id }, header: installHeader() } }))
      onPick(courseFromMine(detail))
      onClose()
    } catch (error) {
      setMessage(failureText(error))
    } finally {
      setFetching(null)
    }
  }

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <HStack justify="between" pad="6px 16px 0" style={{ minHeight: 50 }}>
        <Pressable onClick={onClose} style={{ minWidth: 70, textAlign: 'left' }}><Txt size={17} color="accent">{tr('닫기')}</Txt></Pressable>
        <Txt size={17} weight="semibold">{tr('내 코스')}</Txt>
        <div style={{ minWidth: 70 }} />
      </HStack>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        {loading ? (
          <div style={{ display: 'grid', placeItems: 'center', height: 200 }}><Spinner /></div>
        ) : message ? (
          <VStack spacing={12} align="center" pad={20}>
            <Txt size={13} color="secondary">{message}</Txt>
            <Pressable onClick={() => void reload()}><Txt size={17} color="accent">{tr('다시 시도')}</Txt></Pressable>
          </VStack>
        ) : courses.length === 0 ? (
          <VStack spacing={8} align="center" style={{ padding: '60px 32px' }}>
            <Icon name="point.topleft.down.to.point.bottomright.curvepath" size={40} color="secondary" />
            <Txt size={22} weight="bold" align="center">{tr('붙일 코스가 없습니다')}</Txt>
            <Txt size={15} color="secondary" align="center">{tr('홈의 코스 만들기에서 먼저 코스를 만들어 보세요')}</Txt>
          </VStack>
        ) : (
          courses.map((item) => (
            <Pressable key={item.id} plain disabled={fetching !== null} onClick={() => void pick(item)} style={{ width: '100%', padding: '0 16px' }}>
              <HStack className="hair-b" spacing={12} style={{ padding: '15px 0' }}>
                <PostCourseBadge size={30} />
                <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
                  <Txt size={15} weight="medium" lines={1}>{item.title}</Txt>
                  <Txt size={12} color="secondary">{tr('%d일 · %d곳', item.dayCount, item.placeCount ?? 0)}</Txt>
                </VStack>
                {fetching === item.id && <Spinner />}
              </HStack>
            </Pressable>
          ))
        )}
      </div>
    </VStack>
  )
}
