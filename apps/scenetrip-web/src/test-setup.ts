// 단위 시험 공통 준비. jsdom 에는 localStorage 가 있다.
import { afterEach } from 'vitest'

afterEach(() => {
  localStorage.clear()
})
