/* 발표 HTML을 연 브라우저의 evaluate에서 실행하는 오프라인 DOM 동작 회귀 검사. */
async function testSceneTripNavigation() {
  const results = [];
  const check = (name, condition) => results.push({ name, passed: Boolean(condition) });
  const slides = [...document.querySelectorAll('#deck .slide')];
  const control = (id) => document.getElementById(id);
  const active = () => slides.findIndex((slide) => slide.classList.contains('is-active'));
  const settle = () => new Promise((resolve) => setTimeout(resolve, 50));
  const key = (value, options = {}, target = document.body) => target.dispatchEvent(
    new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options }));
  const select = async (index) => {
    control('slide-select').value = slides[index].id;
    control('slide-select').dispatchEvent(new Event('change'));
    await settle();
  };
  await select(0);
  check('첫 장 이전 버튼 비활성화', control('previous-slide').disabled);
  control('next-slide').click(); await settle();
  check('다음 버튼·hash·counter 동기화', active() === 1 && location.hash === `#${slides[1].id}` && control('slide-count').textContent.startsWith('2 /'));
  key('ArrowRight'); await settle(); check('오른쪽 화살표', active() === 2);
  key(' ' , { shiftKey: true }); await settle(); check('Shift Space 이전', active() === 1);
  key('End'); await settle(); check('마지막 장·다음 버튼 비활성화', active() === slides.length - 1 && control('next-slide').disabled);
  key('ArrowRight'); await settle(); check('마지막 경계 유지', active() === slides.length - 1);
  key('Home'); await settle(); check('Home 첫 장', active() === 0);
  key('ArrowRight', { ctrlKey: true }); await settle(); check('수정 키 조합 무시', active() === 0);
  key('ArrowRight', {}, control('next-slide')); await settle(); check('버튼에서 입력 가로채지 않음', active() === 0);
  const input = document.createElement('input'); document.body.append(input);
  key('ArrowRight', {}, input); await settle(); check('입력 필드에서 단축키 무시', active() === 0); input.remove();
  control('toggle-overview').click(); await settle();
  check('목차 열기·접근성 상태', control('overview').open && control('toggle-overview').getAttribute('aria-expanded') === 'true');
  key('ArrowRight'); await settle(); check('목차 열린 중 발표 단축키 무시', active() === 0);
  control('overview-list').querySelectorAll('a')[3].click(); await settle();
  check('목차 링크 이동·닫기', active() === 3 && !control('overview').open);
  control('toggle-overview').click();
  key('Escape', {}, control('overview')); await settle();
  check('Escape 닫기·초점 복원', !control('overview').open && document.activeElement === control('toggle-overview'));
  control('toggle-notes').click(); await settle();
  check('강사 노트 실제 표시', getComputedStyle(slides[3].querySelector('.notes')).display !== 'none' && control('toggle-notes').getAttribute('aria-pressed') === 'true');
  control('toggle-notes').click(); check('강사 노트 다시 숨김', getComputedStyle(slides[3].querySelector('.notes')).display === 'none');
  control('toggle-reading').click(); await settle();
  check('읽기 모드 모든 장 표시', slides.every((slide) => !slide.hidden && getComputedStyle(slide).display !== 'none'));
  key('ArrowRight'); await settle(); check('읽기 중 단축키 무시', active() === 3);
  control('toggle-reading').click(); await settle(); check('발표로 복귀 한 장 표시', slides.filter((slide) => !slide.hidden).length === 1);
  location.hash = `#${slides[5].id}`; await settle(); check('직접 hash 링크 이동', active() === 5);
  location.hash = '#%invalid'; await settle(); check('잘못된 hash에도 유지', active() === 5);
  let printed = false; const originalPrint = window.print;
  window.print = () => { printed = true; }; control('print-slides').click(); window.print = originalPrint;
  check('인쇄 버튼', printed);
  check('원격 스크립트·스타일 의존 없음', !document.querySelector('script[src],link[rel="stylesheet"]'));
  await select(0);
  return { passed: results.every((result) => result.passed), count: results.length, results };
}
