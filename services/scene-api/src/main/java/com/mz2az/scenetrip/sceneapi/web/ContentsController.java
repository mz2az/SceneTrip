package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.ContentsApi;
import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentList;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.content.ContentStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 목록·검색·상세.
 *
 * <p>{@link ContentsApi} 의 메서드 중 아직 구현하지 않은 것은 인터페이스의 기본 구현이 501 을 돌려준다. 명세에 있는 경로가 조용히 404 가 되는 대신
 * "아직 만들지 않았다" 를 정확히 말한다.
 */
@RestController
class ContentsController implements ContentsApi {

  private final ContentStore store;

  ContentsController(ContentStore store) {
    this.store = store;
  }

  @Override
  public ResponseEntity<ContentList> listContents(
      Lang acceptLanguage,
      String q,
      Long personId,
      ContentCategory category,
      Integer limit,
      Integer offset) {

    // 공백만 들어온 q 는 없는 것으로 본다. 그대로 두면 모든 설명에 걸리는 조건이 되어
    // 필터가 아니라 전체 조회가 된다.
    String query = (q == null || q.isBlank()) ? null : q.strip();

    ContentStore.Page page = store.list(query, personId, category, acceptLanguage, limit, offset);

    ContentList body = new ContentList(page.items(), page.total(), limit, offset);
    return Responses.ok(body, Responses.used(acceptLanguage, page.anyInRequestedLang()));
  }
}
