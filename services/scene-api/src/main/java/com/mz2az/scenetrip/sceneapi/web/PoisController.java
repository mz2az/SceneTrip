package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.PoisApi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCard;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCardBatch;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiList;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinks;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardService;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 편의시설(POI) 지도 조회·상세.
 *
 * <p>{@link PlacesController} 와 같은 규칙({@link PlaceQueryRules})을 쓰되 POI 만의 것이 둘 있다 — 영역 조건이 없으면
 * 거부한다(50 만 건을 전국 대상으로 돌려줄 정렬 기준이 없다), 정렬에 인기도가 없다.
 *
 * <p>응답 언어는 언제나 {@code ko} 다. 자료가 한국어뿐이라 요청 언어와 무관하다 — 명세가 그렇게 적어 두었고, 다국어가 생기면 서버만 바뀐다.
 */
@RestController
class PoisController implements PoisApi {

  private final PoiStore store;
  private final PoiCardService cards;
  private final ReviewViews reviews;
  private final NaverLinks naver;

  PoisController(PoiStore store, PoiCardService cards, ReviewViews reviews, NaverLinks naver) {
    this.store = store;
    this.cards = cards;
    this.reviews = reviews;
    this.naver = naver;
  }

  @Override
  public ResponseEntity<PoiList> listPois(
      Lang acceptLanguage,
      String bbox,
      Double lat,
      Double lng,
      Integer radiusMeters,
      PoiCategoryGroup categoryGroup,
      String categories,
      Boolean hasImage,
      String q,
      String sort,
      Integer spreadZoom,
      Integer limit,
      Integer offset) {

    PlaceQueryRules.requireCompleteOrigin(lat, lng);
    String query = searchTerm(q);
    List<String> kinds = categoryList(categories);
    Bbox area = PlaceQueryRules.resolveArea(bbox, radiusMeters);
    boolean hasOrigin = lat != null;
    // 이름 검색이면 영역 없이도 된다(1.23.0) — 「이 지역 밖에서도 찾기」. 걸린 것만 보므로 전국이 대상이어도 줄 세울 수 있다.
    if (area == null && !(hasOrigin && radiusMeters != null) && query == null) {
      throw ApiException.badRequest(
          "MISSING_AREA_FILTER",
          "bbox 또는 lat·lng+radiusMeters 중 하나는 있어야 합니다 — 전국을 대상으로 돌려줄 정렬 기준이 없습니다");
    }
    PoiStore.Sort order = resolveSort(sort, hasOrigin);
    if (order == PoiStore.Sort.SPREAD && (area == null || spreadZoom == null)) {
      throw ApiException.badRequest(
          "INVALID_PARAMETER", "sort=spread 는 bbox 와 spreadZoom 이 있어야 합니다 — 칸은 화면 기준이다");
    }

    PoiStore.Page page =
        store.list(
            new PoiStore.Criteria(
                area,
                lat,
                lng,
                radiusMeters,
                categoryGroup,
                order,
                limit,
                offset,
                acceptLanguage,
                query,
                order == PoiStore.Sort.SPREAD ? spreadZoom : null,
                kinds,
                // true 일 때만 거른다 — false·없음은 거르지 않는다(1.31.0).
                Boolean.TRUE.equals(hasImage)));

    // 칸마다 따로 폴백한다(명세 「언어」). Content-Language 는 분류 이름의 언어 — PoiStore 클래스 주석.
    return Responses.ok(
        new PoiList(page.items(), page.total(), limit, offset),
        Responses.used(acceptLanguage, page.shownLangs()));
  }

  @Override
  public ResponseEntity<PoiDetail> getPoi(Long poiId, Lang acceptLanguage, Double lat, Double lng) {
    PlaceQueryRules.requireCompleteOrigin(lat, lng);

    PoiStore.Detail detail =
        store
            .findDetail(poiId, acceptLanguage, lat, lng)
            .orElseThrow(
                () -> ApiException.notFound("POI_NOT_FOUND", "편의시설 " + poiId + " 이(가) 없습니다"));

    // 촬영지와 같은 곳이면 별점·사진첩은 그 촬영지의 것이다(MZ2AZ-371) — 리뷰 창구와 같은 규칙.
    Long placeId = detail.poi().getPlaceId();
    ReviewStore.Target target = placeId == null ? ReviewStore.Target.POI : ReviewStore.Target.PLACE;
    long targetId = placeId == null ? poiId : placeId;
    ReviewViews.Gallery gallery = reviews.gallery(target, targetId, ReviewViews.DETAIL_PHOTOS, 0);
    detail
        .poi()
        .rating(reviews.summary(target, targetId))
        .photos(gallery.items())
        .photoCount(gallery.total())
        // 처음 여는 곳이면 지금 네이버에 장소 번호를 묻는다(ADR 0021). 모르면 비어 있고 앱은 이름 검색으로 넘긴다.
        .naverPlaceUrl(naver.placeUrl(detail.poi()).orElse(null));
    return Responses.ok(detail.poi(), detail.shownLang());
  }

  /** 카드 단건. 표에 없으면 지금 출처에 묻는다. 못 찾아도 200 — 404 는 POI 자체가 없을 때뿐. */
  @Override
  public ResponseEntity<PoiCard> getPoiCard(Long poiId) {
    PoiCard card =
        cards
            .card(poiId)
            .orElseThrow(
                () -> ApiException.notFound("POI_NOT_FOUND", "편의시설 " + poiId + " 이(가) 없습니다"));
    return ResponseEntity.ok(card);
  }

  /** 카드 여럿. 표에 있는 것만, 없는 것은 pending. 출처를 부르지 않는다. */
  @Override
  public ResponseEntity<PoiCardBatch> listPoiCards(List<Long> ids) {
    return ResponseEntity.ok(cards.cards(ids));
  }

  /** 이름 검색어 — 앞뒤 공백을 지우고 2자 이상(1.23.0, MZ2AZ-283). 1자는 47만 행 거의 전부에 걸려 400. 없으면 null. */
  private static String searchTerm(String q) {
    if (q == null) {
      return null;
    }
    String trimmed = q.strip();
    if (trimmed.codePointCount(0, trimmed.length()) < 2) {
      throw ApiException.badRequest("INVALID_PARAMETER", "q 는 앞뒤 공백을 빼고 2자 이상이어야 합니다");
    }
    return trimmed;
  }

  /** 세부 종류는 최대 10 개(1.31.0) — 계약. */
  private static final int MAX_CATEGORIES = 10;

  /**
   * 세부 종류(1.31.0, keep-poi.md §1-5) — 쉼표로 나누고 앞뒤 공백을 떼고 빈 조각을 버린 목록. 없으면 null(거르지 않는다). 남은 조각이 없거나
   * 10 개를 넘으면 400.
   */
  private static List<String> categoryList(String categories) {
    if (categories == null) {
      return null;
    }
    List<String> pieces =
        Arrays.stream(categories.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    if (pieces.isEmpty() || pieces.size() > MAX_CATEGORIES) {
      throw ApiException.badRequest(
          "INVALID_PARAMETER", "categories 는 빈 조각을 빼고 1~" + MAX_CATEGORIES + " 개여야 합니다");
    }
    return pieces;
  }

  /**
   * 정렬. 기본은 기준점이 있으면 거리순, 없으면 이름순 — 명세 §pois. {@code distance} 를 기준점 없이 달라고 하면 거부한다. {@code spread}
   * 는 지도 고정 칸마다 순위 1등부터(1.23.0, MZ2AZ-540).
   */
  private static PoiStore.Sort resolveSort(String sort, boolean hasOrigin) {
    if (sort == null) {
      return hasOrigin ? PoiStore.Sort.DISTANCE : PoiStore.Sort.ALPHABETICAL;
    }
    return switch (sort) {
      case "alphabetical" -> PoiStore.Sort.ALPHABETICAL;
      case "spread" -> PoiStore.Sort.SPREAD;
      case "distance" -> {
        if (!hasOrigin) {
          throw ApiException.badRequest("INVALID_SORT", "sort=distance 는 lat·lng 기준점이 있어야 합니다");
        }
        yield PoiStore.Sort.DISTANCE;
      }
      default ->
          throw ApiException.badRequest(
              "INVALID_PARAMETER", "sort 는 distance · alphabetical · spread 중 하나입니다");
    };
  }
}
