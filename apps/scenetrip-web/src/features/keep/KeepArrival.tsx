/*
 * 넘어온 사람(`?keep=32,25`) 처리 — 정본: docs/project/plans/web-keep-flow.md §5-4.
 * 앱이 뜰 때 한 번, 주소의 `keep`(parseKeepParam)을 읽어 작품마다 촬영지를 받아 addMany 로 차례로 담고(이미 담긴 것은 그대로),
 * `keep_arrive(다시 담은 작품 수)` 를 보내고, 주소에서 `keep` 을 지운 /my-map 으로 바꾼다.
 * 앱 안 브라우저가 아니면 시트를 ② 에서 연다(이제 홈 화면 추가를 권할 수 있다). 앱 안 브라우저면 열지 않는다(같은 권유가 돌지 않게).
 * 촬영지를 받지 못한 작품은 건너뛴다. 한 편도 못 담았으면 시트를 열지 않고, 시트에 넘기는 작품은 담긴 것만이다(§9-4).
 * 담는 동안 `keepStore.arriving` 을 켜 둔다(내 지도의 받는 중 표시). 그리는 것은 없다(RootTabs 에 붙어 있다).
 */
import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { track, webEvent } from '@/analytics'
import { paths } from '@/app/routes'
import { SceneData } from '@/features/search/sceneData'
import { cartActions } from '@/state/cart'
import { accountGeneration } from '@/state/shared'
import { isInAppBrowser, parseKeepParam } from './keepRules'
import { keepActions } from './keepStore'

export function KeepArrival(): null {
  const { search } = useLocation()
  const navigate = useNavigate()
  // StrictMode 의 이중 실행에도 한 번 — ref 는 그 사이에 살아 있다.
  const started = useRef(false)
  // 처음 뜰 때의 주소만 본다. 뒤에 바뀌는 주소는 앱 안 이동이다.
  const firstSearch = useRef(search)

  useEffect(() => {
    if (started.current) return
    started.current = true
    const ids = parseKeepParam(firstSearch.current)
    if (ids.length === 0) return
    void (async () => {
      // 담는 동안 내 지도가 「아직 담은 곳이 없어요」 대신 받는 중 표시를 보이게(§9-4). 무슨 일이 있어도 끝에 끈다.
      keepActions.setArriving(true)
      // 작품 촬영지 받기는 작품 상세와 같은 길(상한 100, 넘으면 offset 으로 이어 받기)을 쓴다.
      const scene = new SceneData()
      const generation = accountGeneration()
      const arrived: number[] = []
      try {
        for (const contentId of ids) {
          if (generation !== accountGeneration()) break
          let placeIds: number[]
          try {
            placeIds = (await scene.placesOfContent(contentId)).map((p) => p.id)
          } catch {
            continue
          }
          if (placeIds.length === 0) continue
          let result: { saved: number; already: number; failed: number }
          try {
            result = await cartActions.addMany(placeIds, contentId, undefined, 'arrive')
          } catch {
            continue
          }
          // 한 곳이라도 담겼(또는 이미 담겨 있었)으면 그 작품은 넘어온 것으로 친다 — 시트가 넘기는 주소에도 이것만 싣는다.
          if (result.saved + result.already > 0) arrived.push(contentId)
        }
      } finally {
        keepActions.setArriving(false)
      }
      track(webEvent.keepArrive(arrived.length))
      navigate(paths.myMap, { replace: true })
      // 한 편도 못 담았거나 도중에 계정이 바뀌었으면 시트를 열지 않는다(§9-4).
      if (arrived.length === 0 || generation !== accountGeneration()) return
      if (!isInAppBrowser(navigator.userAgent)) keepActions.openAt('keep', arrived)
    })()
  }, [navigate])

  return null
}
