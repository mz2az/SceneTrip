/*
 * `Picker(...).pickerStyle(.segmented)` — iOS 26 의 둥근 분할 단추. 선택된 칸은 흰 알약.
 * 검색 탭의 작품/장소 전환과 리뷰 정렬이 같이 쓴다.
 */
import { Pressable, Txt } from '@/design/primitives'

export interface SegmentedItem<T extends string> {
  id: T
  label: string
}

export function Segmented<T extends string>({
  items, selected, onSelect, size = 15, label,
}: { items: ReadonlyArray<SegmentedItem<T>>; selected: T; onSelect: (id: T) => void; size?: number; label?: string }) {
  const index = Math.max(0, items.findIndex((i) => i.id === selected))
  const width = 100 / items.length
  return (
    <div
      role="tablist"
      aria-label={label}
      style={{ position: 'relative', display: 'flex', height: 32, padding: 2, borderRadius: 999, background: 'rgba(120,120,128,0.12)', flex: 'none' }}
    >
      {/* 선택된 알약 — 칸 사이를 미끄러진다. */}
      <div
        style={{
          position: 'absolute', top: 2, bottom: 2, left: `calc(2px + (100% - 4px) * ${index} / ${items.length})`, width: `calc((100% - 4px) * ${width / 100})`,
          borderRadius: 999, background: '#fff', boxShadow: '0 0 0 0.5px rgba(0,0,0,0.06), 0 2px 6px rgba(0,0,0,0.1)', transition: 'left 0.2s ease',
        }}
      />
      {items.map((item) => (
        <Pressable
          key={item.id}
          plain
          role="tab"
          aria-selected={item.id === selected}
          onClick={() => onSelect(item.id)}
          style={{ position: 'relative', flex: 1, display: 'grid', placeItems: 'center', minWidth: 0 }}
        >
          <Txt size={size} lines={1}>{item.label}</Txt>
        </Pressable>
      ))}
    </div>
  )
}
