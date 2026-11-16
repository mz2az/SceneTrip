/*
 * 밖으로 넘기는 이동(앱 안 브라우저 → 바깥 브라우저, web-keep-flow.md §5-2 ②) — 반드시 이것 하나만 지난다.
 * `intent://…`·`x-safari-https://…` 처럼 페이지를 떠나는 이동이라, 시험은 이 모듈을 가짜로 바꿔 어디로 넘기려 했는지만 본다.
 */
export const handoff = {
  open(href: string): void {
    window.location.href = href
  },
}
