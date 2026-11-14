/*
 * 담기 중심 흐름(docs/project/plans/web-keep-flow.md) — 밖에서 쓰는 것.
 * 한꺼번에 담기는 `cartActions.addMany`(@/state/cart), 이동은 `useTabRouter().openMyMap / openPlanFromSaved`(@/app/tabRouter).
 */
export {
  type KeepEnv,
  type MapBounds,
  type MapGroup,
  KEEP_MAX,
  handoffHref,
  isInAppBrowser,
  keepEnv,
  keepUrl,
  mapBounds,
  mapGroups,
  moreWorks,
  parseKeepParam,
} from './keepRules'
export { type KeepState, type KeepStep, type SaveAllProgress, keepActions, keepStore, saveAllProgress, useKeep, useSaveAllProgress } from './keepStore'
export { canPromptInstall, currentKeepEnv, promptInstall, startInstallPromptCapture, useCanPromptInstall } from './installPrompt'
export { SaveAllBar, SaveAllBarPlaceholder, type SaveAllBarProps } from './SaveAllBar'
export { MyMapCard, type MyMapCardProps } from './MyMapCard'
export { MyMapCover } from './MyMapCover'
export { KeepSheet } from './KeepSheet'
export { KeepArrival } from './KeepArrival'
export { handoff } from './handoff'
