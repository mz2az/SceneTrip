/*
 * 하는 동안의 두 번째 부름은 버리는 감싸개 (MZ2AZ-551).
 *
 * 「코스 만들기」·「추천 코스 저장」 을 빠르게 두 번 누르면 코스가 둘 생겼다. `busy` 는 React 상태라 다음 그리기 전까지는
 * 두 번째 누름이 통과한다. 이 감싸개는 같은 동기 틀 안에서 바로 잠그므로 두 번째 부름은 undefined 로 끝난다.
 * 끝나면(실패해도) 풀린다. 컴포넌트에서는 `useRef(onceWhileBusy(fn))` 처럼 한 번만 만들어 쥔다.
 */
export function onceWhileBusy<A extends unknown[], R>(work: (...args: A) => Promise<R>): (...args: A) => Promise<R | undefined> {
  let busy = false
  return async (...args: A) => {
    if (busy) return undefined
    busy = true
    try {
      return await work(...args)
    } finally {
      busy = false
    }
  }
}
