/*
 * 목록 줄의 대표 사진 — iOS `PostRemotePhoto` · `CommunityCoverPhoto` (CommunityTab/PostRemotePhoto.swift).
 * 만료되는 사진 주소는 저장하지 않는다. 안 열리면 그 글의 상세를 다시 받아 새 주소로 바꾼다(30 초에 한 번, 눌러서 하는 것은 언제나).
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { Pressable, Spinner } from '@/design/primitives'
import { tr } from '@/i18n'
import type { CommunityPost } from './model'
import { createPhotoRenewal, unsigned } from './renewal'
import { communityStore } from './store'

type Status = 'loading' | 'loaded' | 'failed'

export function CoverPhoto({ post }: { post: CommunityPost }) {
  const [url, setUrl] = useState<string | null>(post.remotePhotos?.[0] ?? null)
  const [status, setStatus] = useState<Status>('loading')
  const [attempt, setAttempt] = useState(0)
  const renewal = useMemo(() => createPhotoRenewal(), [])
  const renewedFor = useRef<string | null>(null)

  // 목록을 새로 받아 주소가 바뀌면 따라간다.
  const incoming = post.remotePhotos?.[0] ?? null
  useEffect(() => {
    if (incoming) setUrl(incoming)
  }, [incoming])
  useEffect(() => () => renewal.cancel(), [renewal])

  const renew = (forced: boolean) => {
    if (post.serverId === undefined) return
    void renewal.run(forced, async () => {
      const fresh = await communityStore.api.detail(post)
      const first = fresh.remotePhotos?.[0]
      if (first) setUrl(first)
    })
  }
  const key = url ? unsigned(url) : ''
  const onError = () => {
    setStatus('failed')
    if (renewedFor.current === key) return
    renewedFor.current = key
    renew(false)
  }

  if (!url) return null
  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', background: 'var(--system-gray5)', overflow: 'hidden' }}>
      <img
        key={`${attempt}`}
        src={url}
        alt=""
        onLoad={() => setStatus('loaded')}
        onError={onError}
        style={{ width: '100%', height: '100%', objectFit: 'cover', display: status === 'failed' ? 'none' : 'block' }}
      />
      {status === 'loading' && <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}><Spinner /></div>}
      {status === 'failed' && (
        <Pressable
          aria-label={tr('사진 다시 받기')}
          onClick={(e) => {
            e.stopPropagation()
            setStatus('loading')
            setAttempt((n) => n + 1)
            renew(true)
          }}
          style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}
        >
          <Icon name="arrow.clockwise" size={17} color="accent" />
        </Pressable>
      )}
    </div>
  )
}
