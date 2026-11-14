/*
 * 날아가기는 실패하면 안 날린다 — 저장 결과를 받은 뒤, 새로 담겼을 때만(web-ad-landing.md §10-3-9).
 * flyWhenSaved(save, from, options): save 가 'saved' 로 풀리면 flyToMyMap(그리는 수단이 없는 jsdom 에서는 배지만 튄다 — myMapLandStore +1).
 */
import { describe, expect, it } from 'vitest'
import { flyWhenSaved, myMapLandStore } from './flyToMyMap'

describe('flyWhenSaved', () => {
  it('저장 결과가 오기 전에는 날지 않고, saved 면 내려앉는다', async () => {
    let resolve: (v: string) => void = () => {}
    const before = myMapLandStore.get()
    const done = flyWhenSaved(new Promise<string>((r) => (resolve = r)), document.body)
    await Promise.resolve()
    expect(myMapLandStore.get()).toBe(before)
    resolve('saved')
    await done
    expect(myMapLandStore.get()).toBe(before + 1)
  })

  it.each(['failed', 'already'])('%s 면 날지 않는다', async (result) => {
    const before = myMapLandStore.get()
    await flyWhenSaved(Promise.resolve(result), document.body)
    expect(myMapLandStore.get()).toBe(before)
  })
})
