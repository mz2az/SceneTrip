// 단위 시험 공통 준비. jsdom 에는 localStorage 가 있다(`@vitest-environment node` 시험에는 없다).
import { afterEach } from 'vitest'

afterEach(() => {
  if (typeof localStorage !== 'undefined') localStorage.clear()
})
