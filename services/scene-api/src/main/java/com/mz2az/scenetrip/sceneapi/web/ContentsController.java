package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.ContentsApi;
import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentDetail;
import com.mz2az.scenetrip.sceneapi.api.model.ContentList;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceList;
import com.mz2az.scenetrip.sceneapi.content.ContentStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
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

  private final ContentStore contentStore;
  private final PlaceStore placeStore;

  ContentsController(ContentStore contentStore, PlaceStore placeStore) {
    this.contentStore = contentStore;
    this.placeStore = placeStore;
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

    ContentStore.Page page =
        contentStore.list(query, personId, category, acceptLanguage, limit, offset);

    ContentList body = new ContentList(page.items(), page.total(), limit, offset);
    return Responses.ok(body, Responses.used(acceptLanguage, page.anyInRequestedLang()));
  }

  /**
   * 작품 상세.
   *
   * <p>촬영지 목록은 여기 넣지 않는다 — 개수가 많고 페이지네이션이 필요해서 별도 조회다.
   */
  @Override
  public ResponseEntity<ContentDetail> getContent(Long contentId, Lang acceptLanguage) {
    ContentStore.Detail detail =
        contentStore
            .findDetail(contentId, acceptLanguage)
            .orElseThrow(
                () -> ApiException.notFound("CONTENT_NOT_FOUND", "작품 " + contentId + " 이(가) 없습니다"));

    return Responses.ok(detail.content(), Responses.used(acceptLanguage, detail.inRequestedLang()));
  }

  /**
   * 한 작품의 촬영지 목록.
   *
   * <p>{@code GET /places?contentId=} 와 같은 조회를 경로로 표현한 것이다. 저장소를 공유하므로 정렬·페이지네이션·응답 모양이 저절로 같다.
   *
   * <p>목업은 각 행에 번호를 매겨 촬영 순서처럼 보여 주지만 수집 데이터에 촬영 순서가 없다. 응답은 인기도 내림차순이고 번호는 프론트가 배열 순서대로 매긴다.
   */
  @Override
  public ResponseEntity<PlaceList> listContentPlaces(
      Long contentId, Lang acceptLanguage, Double lat, Double lng, Integer limit, Integer offset) {

    PlaceQueryRules.requireCompleteOrigin(lat, lng);

    // 없는 작품과 촬영지가 아직 없는 작품을 구별한다. 둘 다 빈 목록이 나오지만
    // 앞의 것은 404 이고 뒤의 것은 200 에 빈 배열이다.
    if (!contentStore.exists(contentId)) {
      throw ApiException.notFound("CONTENT_NOT_FOUND", "작품 " + contentId + " 이(가) 없습니다");
    }

    PlaceStore.Page page =
        placeStore.list(
            new PlaceStore.Criteria(
                null,
                contentId,
                null,
                lat,
                lng,
                null,
                PlaceStore.Sort.POPULARITY,
                acceptLanguage,
                limit,
                offset));

    PlaceList body = new PlaceList(page.items(), page.total(), limit, offset);
    return Responses.ok(body, Responses.used(acceptLanguage, page.anyInRequestedLang()));
  }
}
