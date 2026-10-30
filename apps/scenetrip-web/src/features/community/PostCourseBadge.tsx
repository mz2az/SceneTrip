import { Icon } from '@/design/Icon'
import './icons'

const ICON_RATIO = 0.47 // CommunityComposeView.swift:PostCourseBadge — `.font(.system(size: size * 0.47))`
const RADIUS_RATIO = 0.24

/** 코스 표시 — 피노 색 네모 안의 경로 그림. 글쓰기·글 보기·목록이 같이 쓴다. */
export function PostCourseBadge({ size = 34 }: { size?: number }) {
  return (
    <div style={{ width: size, height: size, borderRadius: size * RADIUS_RATIO, background: 'var(--pin-deep)', display: 'grid', placeItems: 'center', flex: 'none' }}>
      <Icon name="point.topleft.down.to.point.bottomright.curvepath" size={size * ICON_RATIO} color="white" />
    </div>
  )
}
