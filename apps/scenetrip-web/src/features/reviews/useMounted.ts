import { useEffect, useState } from 'react'

/** 시트가 닫힌 뒤 애니메이션이 끝날 때까지만 내용을 살려 둔다 — 닫혀 있는 동안은 조회도 하지 않는다. */
export function useMountedWhile(open: boolean, ms = 320): boolean {
  const [mounted, setMounted] = useState(open)
  useEffect(() => {
    if (open) {
      setMounted(true)
      return
    }
    const t = setTimeout(() => setMounted(false), ms)
    return () => clearTimeout(t)
  }, [open, ms])
  return open || mounted
}
