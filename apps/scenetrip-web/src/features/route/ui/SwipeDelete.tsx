import { Icon } from '@/design/Icon'
import { Txt } from '@/design/primitives'
import { Pressable } from './Press'
import { tr } from '@/i18n'

/**
 * 밀어서 나오는 「삭제」 — 빨간 캡슐에 쓰레기통, 아래에 글자(iOS 26 swipeActions, `Label("삭제", systemImage: "trash")`).
 * 캡슐 크기는 줄 높이에 따라 달라진다: 코스 목록 줄(69pt) 60×41 위쪽 정렬, 일정 줄(큰 줄) 50×53 가운데.
 */
export function SwipeDelete({ width, height, top, onPress }: { width: number; height: number; top?: number; onPress: () => void }) {
  const aligned = top !== undefined
  return (
    <Pressable
      plain
      onClick={onPress}
      aria-label={tr('삭제')}
      style={{
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: aligned ? 'flex-start' : 'center', gap: 4,
        paddingTop: top,
      }}
    >
      <span
        style={{
          width, height, borderRadius: height, background: 'var(--system-red)', display: 'grid', placeItems: 'center',
          boxShadow: '0 1px 2px rgba(0,0,0,0.08)',
        }}
      >
        <Icon name="trash" size={20} weight="semibold" color="#fff" />
      </span>
      <Txt size={13} color="secondary">{tr('삭제')}</Txt>
    </Pressable>
  )
}
