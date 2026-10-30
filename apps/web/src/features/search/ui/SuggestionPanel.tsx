/*
 * 자동완성 패널 (§3-3). 출처: apps/scenetrip-ios/src/SearchTab/SuggestionPanel.swift
 *
 * 랭킹과 갈래는 서버가 정한다 — 여기서는 그 순서를 섹션으로 배치만 한다: 첫 작품은 포스터 카드로, 장소는 장소끼리, 전체는 연관 검색어 칩으로.
 * 패널은 검색창 바로 아래에 붙어 내용만큼만 자란다(높이 프레임을 걸지 않는다 — 실측으로 검색창을 덮었다).
 */
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spacer, Txt, VStack, rr, shadow } from '@/design/primitives'
import { tr } from '@/i18n'
import type { ContentDetail } from '../sceneData'
import { pickAliasBadge } from '../rules/wording'
import { type EntityType, PLACE_ROWS_MAX, RECOMMENDED, type Suggestion, matchedAlias, suggestionDetail, symbolOf } from '../rules/suggestions'
import { FONT, ListDivider, RemoteImage } from './common'
import { contentMeta } from './Rows'

export function AliasBadge({ text }: { text: string }) {
  return (
    <Txt size={FONT.caption2} color="accent" style={{ padding: '2px 6px', borderRadius: 999, background: 'rgba(0,136,255,0.12)', whiteSpace: 'nowrap' }}>
      {text}
    </Txt>
  )
}

function SectionLabel({ title }: { title: string }) {
  return (
    <HStack pad="8.7px 14px 4px">
      <Txt size={FONT.caption} color="secondary">{title}</Txt>
    </HStack>
  )
}

function Row({ icon, text, detail, alias, onClick }: { icon: string; text: string; detail: string; alias?: string | null; onClick: () => void }) {
  return (
    <Pressable plain onClick={onClick} style={{ width: '100%' }}>
      <HStack spacing={10} pad="10.7px 14px">
        <span style={{ width: 18, display: 'grid', placeItems: 'center', flex: 'none' }}>
          <Icon name={icon} size={FONT.body} color="secondary" />
        </span>
        <Txt size={FONT.body}>{text}</Txt>
        {alias && <AliasBadge text={alias} />}
        <Spacer />
        {detail !== '' && <Txt size={FONT.caption} color="secondary" lines={1} style={{ maxWidth: 160 }}>{detail}</Txt>}
      </HStack>
    </Pressable>
  )
}

export function SuggestionPanel({
  draft, suggestions, topWork, onCommit, onOpenWork, onSelectPlace,
}: {
  draft: string
  suggestions: readonly Suggestion[]
  /** 가장 연관된 작품 — 포스터·메타와 함께 맨 위에 뜨고, 누르면 작품 상세로 바로 들어간다(검색어 치환이 아니다). */
  topWork: ContentDetail | null
  /** 두 번째 값은 고른 것의 갈래다. 어느 탭을 열지 화면이 그것으로 정한다. 갈래를 모르면 null. */
  onCommit: (term: string, kind: EntityType | null) => void
  onOpenWork: (work: ContentDetail) => void
  /** 장소 제안을 골랐다 — 검색어 커밋이 아니라 그 장소로 이동하는 동작이다. */
  onSelectPlace: (item: Suggestion) => void
}) {
  const places = suggestions.filter((s) => s.type === 'place').slice(0, PLACE_ROWS_MAX)
  const matched = topWork ? suggestions.find((s) => s.type === 'content' && s.id === topWork.id) : undefined
  const topAlias = topWork ? pickAliasBadge(topWork.title, topWork.aliases ?? [], matched?.matchedTerm) : null
  return (
    <VStack
      style={{ margin: '2px 14px 0', background: 'var(--system-background)', ...rr(14), boxShadow: shadow('rgba(0,0,0,0.12)', 10, 3), overflow: 'hidden' }}
    >
      {draft === '' ? (
        <>
          <SectionLabel title={tr('추천 검색어')} />
          {RECOMMENDED.map((item) => (
            // 보이는 글자로 검색한다 — 영어일 때 「Guardian」을 눌렀는데 검색창에 한국어가 들어가면 안 된다.
            <Row key={item.term} icon={symbolOf(item.type)} text={tr(item.term)} detail="" onClick={() => onCommit(tr(item.term), item.type)} />
          ))}
        </>
      ) : (
        <>
          {topWork && (
            <>
              <Pressable plain onClick={() => onOpenWork(topWork)} style={{ width: '100%' }}>
                <HStack spacing={12} pad={14}>
                  <RemoteImage url={topWork.posterUrl} symbol="film" style={{ width: 46, height: 62, ...rr(6), flex: 'none' }} />
                  <VStack spacing={4} align="start" style={{ flex: 1, minWidth: 0 }}>
                    <Txt size={FONT.headline} weight="semibold">{topWork.title}</Txt>
                    <Txt size={FONT.caption} color="secondary">{contentMeta(topWork)}</Txt>
                    {topAlias && <AliasBadge text={topAlias} />}
                  </VStack>
                  <Icon name="arrow.up.left" size={FONT.caption} color="tertiary" />
                </HStack>
              </Pressable>
              <ListDivider />
            </>
          )}
          {places.length > 0 && (
            <>
              <SectionLabel title={tr('장소')} />
              {places.map((item) => (
                <Row key={`${item.type}-${item.id}`} icon="mappin.and.ellipse" text={item.name} detail={suggestionDetail(item)} alias={matchedAlias(item)} onClick={() => onSelectPlace(item)} />
              ))}
            </>
          )}
          {suggestions.length > 0 && (
            <>
              <SectionLabel title={tr('연관 검색어')} />
              <div className="scroll-x" style={{ padding: '10px 14px' }}>
                <HStack spacing={8} style={{ width: 'max-content' }}>
                  {suggestions.map((item) => (
                    <Pressable
                      key={`${item.type}-${item.id}`}
                      plain
                      // 칩도 갈래대로 움직인다 — 장소 칩은 그 장소로.
                      onClick={() => (item.type === 'place' ? onSelectPlace(item) : onCommit(item.name, item.type))}
                      style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 10px', borderRadius: 999, background: 'var(--system-gray6)' }}
                    >
                      <Icon name={symbolOf(item.type)} size={FONT.caption} />
                      <Txt size={FONT.caption} lines={1}>{item.name}</Txt>
                    </Pressable>
                  ))}
                </HStack>
              </div>
            </>
          )}
        </>
      )}
    </VStack>
  )
}
