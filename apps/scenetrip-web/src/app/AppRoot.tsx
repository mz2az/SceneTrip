/*
 * 웹은 2026-11-03 첫 실행 덮개를 모두 뺐다 — 광고로 들어온 사람이 바로 지도를 본다. iOS 는 그대로
 */
import { useLang } from '@/i18n'
import { RootTabs } from './RootTabs'

export function AppRoot() {
  const lang = useLang()
  // 언어가 바뀌면 화면을 통째로 다시 만든다 — 서버 내용도 그 언어로 다시 받아야 한다.
  return <RootTabs key={lang} />
}
