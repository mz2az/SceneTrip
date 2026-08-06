package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.SearchApi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.SuggestionList;
import com.mz2az.scenetrip.sceneapi.search.SuggestionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자동완성.
 *
 * <p>{@link SearchApi} 는 명세에서 생성된 인터페이스다. 경로·파라미터 이름·응답 타입이 전부 거기 붙어 있으므로 이 클래스에는 그것들이 없다 — 명세와
 * 어긋나면 컴파일이 실패한다.
 */
@RestController
class SearchController implements SearchApi {

  private final SuggestionStore store;

  SearchController(SuggestionStore store) {
    this.store = store;
  }

  @Override
  public ResponseEntity<SuggestionList> suggest(String q, Lang acceptLanguage, Integer limit) {
    // 앞뒤 공백 제거는 명세가 서버의 몫이라고 적어 둔 것이다. 공백만 친 경우
    // search_normalize() 가 빈 문자열을 내고 질의가 0 건을 돌려준다 — 빈 목록은
    // 오류가 아니다.
    SuggestionStore.Result result = store.suggest(q.strip(), acceptLanguage, limit);
    return Responses.ok(
        new SuggestionList(result.items()),
        Responses.used(acceptLanguage, result.anyInRequestedLang()));
  }
}
