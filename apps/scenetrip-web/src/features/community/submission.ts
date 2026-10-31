/*
 * 게시 — iOS `CommunitySubmission` (CommunityTab/CommunitySubmission.swift).
 * 멱등 키 없는 POST 의 응답을 잃으면 같은 초안을 다시 보내지 않는다 — 「게시 여부 확인」으로 내 글 목록에서 찾는다.
 */
import { ApiFailure } from '@/api/failure'
import { tr } from '@/i18n'
import { accountGeneration } from '@/state/shared'
import { createStore } from '@/state/storage'
import { type CommunityClient, serverCommunityClient } from './client'
import type { PostCourse, PostDetail } from './model'
import { type PostInput, canPost, failureText, matches, uncertain } from './rules'

export interface SubmissionState {
  working: boolean
  /** 응답을 못 받아 올라갔는지 모른다 — 다시 보내지 않고 확인만 한다. */
  uncertain: boolean
  message: string | null
  photoInvalid: boolean
  completed: boolean
}

/** 응답을 잃었을 때 내 글에서 찾을 때, 보낸 시각 앞뒤의 여유(초). */
const CLOCK_SLACK_SECONDS = 5
const MINE_SCAN = 100

export interface Submission {
  readonly store: ReturnType<typeof createStore<SubmissionState>>
  submit(input: PostInput, expectedCourse?: PostCourse | null): Promise<PostDetail | null>
  check(): Promise<PostDetail | null>
}

export function createSubmission(deps: { client?: CommunityClient; accountEpoch?: () => number; now?: () => Date } = {}): Submission {
  const client = deps.client ?? serverCommunityClient
  const epochNow = deps.accountEpoch ?? accountGeneration
  const now = deps.now ?? (() => new Date())
  const store = createStore<SubmissionState>({ working: false, uncertain: false, message: null, photoInvalid: false, completed: false })
  const patch = (p: Partial<SubmissionState>) => store.set((s) => ({ ...s, ...p }))

  let submitted: PostInput | null = null
  let expectedCourse: PostCourse | null = null
  let baseline = new Set<number>()
  let submittedEpoch = 0
  let submittedAt = new Date(0)

  return {
    store,
    async submit(input, course = null) {
      const s = store.get()
      if (s.working || s.uncertain || s.completed || !canPost(input.title, input.body, input.photoKeys.length)) return null
      patch({ working: true, message: null, photoInvalid: false })
      try {
        submittedEpoch = epochNow()
        // 옛 글을 이번 게시 성공으로 잘못 판정하지 않도록 전송 전 목록을 받는다.
        try {
          baseline = new Set((await client.list(true, MINE_SCAN, 0)).items.map((p) => p.id))
        } catch (error) {
          patch({ message: failureText(error) })
          return null
        }
        if (epochNow() !== submittedEpoch) return null
        submitted = input
        expectedCourse = course
        submittedAt = now()
        try {
          const saved = await client.create(input)
          if (epochNow() !== submittedEpoch) return null
          patch({ completed: true })
          return saved
        } catch (error) {
          if (epochNow() !== submittedEpoch) return null
          const unsure = uncertain(error)
          patch({
            uncertain: unsure,
            photoInvalid: error instanceof ApiFailure && error.apiCode === 'POST_PHOTO_INVALID',
            message: unsure ? tr('게시 응답을 받지 못했어요. 중복 게시를 막기 위해 게시 여부를 확인해 주세요') : failureText(error),
          })
          return null
        }
      } finally {
        patch({ working: false })
      }
    },
    async check() {
      const s = store.get()
      if (!s.uncertain || s.working || s.completed || !submitted || epochNow() !== submittedEpoch) return null
      if (submitted.photoKeys.length === 0) {
        patch({ message: tr('사진 없는 글은 게시 여부를 확정할 수 없어요. 내 글 목록에서 확인해 주세요. 중복을 막기 위해 다시 보내지 않습니다') })
        return null
      }
      const sent = submitted
      patch({ working: true })
      try {
        const page = await client.list(true, MINE_SCAN, 0)
        const earliest = submittedAt.getTime() - CLOCK_SLACK_SECONDS * 1000
        const candidates = page.items.filter((p) => !baseline.has(p.id) && new Date(p.createdAt).getTime() >= earliest && p.title === sent.title)
        const found: PostDetail[] = []
        for (const candidate of candidates) {
          const detail = await client.detail(candidate.id)
          if (matches(detail, sent, expectedCourse)) found.push(detail)
        }
        if (epochNow() !== submittedEpoch) return null
        if (found.length === 1) {
          patch({ completed: true })
          return found[0]!
        }
        patch({ message: tr('게시 여부를 아직 확인하지 못했어요. 잠시 뒤 다시 확인해 주세요. 초안은 그대로 있습니다') })
      } catch (error) {
        patch({ message: failureText(error) })
      } finally {
        patch({ working: false })
      }
      return null
    },
  }
}
