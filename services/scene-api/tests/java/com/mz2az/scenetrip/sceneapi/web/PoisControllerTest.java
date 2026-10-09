package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.Photo;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoSource;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCard;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCardBatch;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiImage;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.api.model.RatingSummary;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinks;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardService;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 편의시설 조회의 HTTP 계층. 검증하는 것은 <b>파라미터들 사이의 관계</b>와 그것이 {@link PoiStore.Criteria} 로 옮겨지는 방식이다. DB 는 없다
 * — SQL 은 통합 레인이 본다.
 */
@WebMvcTest(PoisController.class)
@Import(LanguageConfiguration.class)
class PoisControllerTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  @Autowired private MockMvc mvc;
  @MockitoBean private PoiStore store;
  @MockitoBean private PoiCardService cards;

  // 상세의 naverPlaceUrl(계약 1.6.0, ADR 0021). 네이버 검색과 저장은 NaverLinksTest 가 본다 — 여기서는 「모름」 이 기본이다.
  @MockitoBean private NaverLinks naverLinks;

  // 상세의 별점·사진첩(계약 1.4.0). 리뷰 표의 SQL 은 통합 레인이 본다 — 여기서는 「리뷰 없음」 이 기본이다.
  @MockitoBean private ReviewViews reviews;

  @BeforeEach
  void noReviews() {
    when(reviews.summary(any(), anyLong()))
        .thenReturn(new RatingSummary(0, List.of(0, 0, 0, 0, 0)));
    when(reviews.gallery(any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new ReviewViews.Gallery(List.of(), 0));
  }

  private static PoiSummary poi(long id, String name) {
    return new PoiSummary(id, name, "한식", PoiCategoryGroup.FOOD, 37.498, 127.027)
        .categoryLabel("한식")
        .distanceMeters(12);
  }

  /** 스토어가 분류 이름을 한국어로 보였다고 답한다 — 언어가 요점이 아닌 시험용. */
  private void givenPois(int total, PoiSummary... items) {
    givenPois(Set.of(Lang.KO), total, items);
  }

  private void givenPois(Set<Lang> shown, int total, PoiSummary... items) {
    when(store.list(any())).thenReturn(new PoiStore.Page(List.of(items), total, shown));
  }

  private Lang requestedLang() {
    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    return captor.getValue().lang();
  }

  @Test
  @DisplayName("뷰포트 + 중심 — 200, bbox 가 파싱되고 기본 정렬은 거리순")
  void viewportWithOrigin() throws Exception {
    givenPois(4027, poi(1, "서초강산스토리"), poi(2, "포490"));

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .param("lat", "37.498")
                .param("lng", "127.027")
                .param("categoryGroup", "food"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].name").value("서초강산스토리"))
        .andExpect(jsonPath("$.items[0].categoryGroup").value("food"))
        .andExpect(jsonPath("$.total").value(4027))
        .andExpect(jsonPath("$.limit").value(30))
        .andExpect(jsonPath("$.offset").value(0));

    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    PoiStore.Criteria c = captor.getValue();
    assertThat(c.bbox()).isEqualTo(new Bbox(127.017, 37.489, 127.037, 37.507));
    assertThat(c.lat()).isEqualTo(37.498);
    assertThat(c.categoryGroup()).isEqualTo(PoiCategoryGroup.FOOD);
    assertThat(c.sort()).isEqualTo(PoiStore.Sort.DISTANCE);
    assertThat(c.limit()).isEqualTo(30);
  }

  @Test
  @DisplayName("뷰포트만 — 기준점이 없으면 기본 정렬은 이름순")
  void viewportWithoutOriginDefaultsToAlphabetical() throws Exception {
    givenPois(0);

    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507"))
        .andExpect(status().isOk());

    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    assertThat(captor.getValue().sort()).isEqualTo(PoiStore.Sort.ALPHABETICAL);
    assertThat(captor.getValue().lat()).isNull();
  }

  @Test
  @DisplayName("반경 + 중심 — bbox 없이도 영역 조건이다")
  void radiusWithOrigin() throws Exception {
    givenPois(0);

    mvc.perform(
            get("/pois")
                .param("lat", "37.498")
                .param("lng", "127.027")
                .param("radiusMeters", "300"))
        .andExpect(status().isOk());

    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    assertThat(captor.getValue().bbox()).isNull();
    assertThat(captor.getValue().radiusMeters()).isEqualTo(300);
  }

  @Test
  @DisplayName("영역 조건이 하나도 없으면 400 — /places 와 다른 POI 만의 규칙")
  void missingAreaIsRejected() throws Exception {
    mvc.perform(get("/pois"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_AREA_FILTER"));

    // 기준점만 있고 반경이 없는 것도 영역 조건이 아니다.
    mvc.perform(get("/pois").param("lat", "37.498").param("lng", "127.027"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_AREA_FILTER"));
  }

  @Test
  @DisplayName("bbox 와 radiusMeters 를 함께 보내면 400")
  void conflictingAreaIsRejected() throws Exception {
    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .param("lat", "37.498")
                .param("lng", "127.027")
                .param("radiusMeters", "300"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFLICTING_AREA_FILTER"));
  }

  @Test
  @DisplayName("lat 만 보내면 400 — 기준점은 짝이다")
  void incompleteOriginIsRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("lat", "37.498"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INCOMPLETE_ORIGIN"));
  }

  @Test
  @DisplayName("bbox 문법은 맞는데 최소가 최대보다 크면 400")
  void invalidBboxIsRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", "127.037,37.489,127.017,37.507"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BBOX"));
  }

  @Test
  @DisplayName("sort=distance 인데 기준점이 없으면 400")
  void distanceSortWithoutOriginIsRejected() throws Exception {
    mvc.perform(
            get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("sort", "distance"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SORT"));
  }

  @Test
  @DisplayName("모르는 sort 값은 400 — popularity 도 없다")
  void unknownSortIsRejected() throws Exception {
    mvc.perform(
            get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("sort", "popularity"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("모르는 categoryGroup 은 400 — 조용히 필터가 빠지면 안 된다")
  void unknownCategoryGroupIsRejected() throws Exception {
    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .param("categoryGroup", "foods"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("limit 이 상한(200)을 넘으면 400 — 생성된 인터페이스의 @Max")
  void limitAboveMaxIsRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("limit", "500"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName(
      "Accept-Language: en — name·address·category 는 한국어 그대로, 영어는"
          + " displayName·displayAddress·categoryLabel 로")
  void englishRequestReachesStore() throws Exception {
    PoiSummary starbucks =
        new PoiSummary(1L, "스타벅스 구리갈매역점", "카페", PoiCategoryGroup.FOOD, 37.63, 127.11)
            .displayName("Starbucks Gurigalmaeyeok Branch")
            .categoryLabel("Cafe")
            .nameRoman("Seutabeokseu Gurigalmaeyeokjeom")
            .address("경기 구리시 갈매동")
            .displayAddress("7 Gyeongchun-ro 1440beon-gil, Guri-si, Gyeonggi-do");
    givenPois(Set.of(Lang.EN), 1, starbucks);

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "en"))
        .andExpect(jsonPath("$.items[0].name").value("스타벅스 구리갈매역점"))
        .andExpect(jsonPath("$.items[0].displayName").value("Starbucks Gurigalmaeyeok Branch"))
        .andExpect(jsonPath("$.items[0].nameRoman").value("Seutabeokseu Gurigalmaeyeokjeom"))
        .andExpect(jsonPath("$.items[0].category").value("카페"))
        .andExpect(jsonPath("$.items[0].categoryLabel").value("Cafe"))
        .andExpect(jsonPath("$.items[0].address").value("경기 구리시 갈매동"))
        .andExpect(
            jsonPath("$.items[0].displayAddress")
                .value("7 Gyeongchun-ro 1440beon-gil, Guri-si, Gyeonggi-do"))
        .andExpect(jsonPath("$.items[0].localName").doesNotExist())
        .andExpect(jsonPath("$.items[0].localAddress").doesNotExist());

    assertThat(requestedLang()).isEqualTo(Lang.EN);
  }

  @Test
  @DisplayName("en 요청인데 확실한 영어 이름·영문 주소가 없으면 display 칸은 비고, categoryLabel 은 있다")
  void englishRequestWithoutTranslations() throws Exception {
    PoiSummary local =
        new PoiSummary(2L, "행복분식", "분식", PoiCategoryGroup.FOOD, 37.5, 127.0)
            .categoryLabel("Snack bar")
            .nameRoman("Haengbokbunsik")
            .address("서울 강남구 역삼동");
    givenPois(Set.of(Lang.EN), 1, local);

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("행복분식"))
        .andExpect(jsonPath("$.items[0].displayName").doesNotExist())
        .andExpect(jsonPath("$.items[0].nameRoman").value("Haengbokbunsik"))
        .andExpect(jsonPath("$.items[0].address").value("서울 강남구 역삼동"))
        .andExpect(jsonPath("$.items[0].displayAddress").doesNotExist())
        .andExpect(jsonPath("$.items[0].categoryLabel").value("Snack bar"));
  }

  @Test
  @DisplayName("Accept-Language 가 없으면 en 을 요청한다 — 명세의 기본값")
  void missingHeaderRequestsEnglish() throws Exception {
    givenPois(Set.of(Lang.EN), 0);

    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "en"));

    assertThat(requestedLang()).isEqualTo(Lang.EN);
  }

  @Test
  @DisplayName("ko 요청 — 스토어에 ko 가 가고 Content-Language: ko, display 칸은 없고 categoryLabel 은 한국어")
  void koreanRequest() throws Exception {
    givenPois(Set.of(Lang.KO), 1, poi(1, "서초강산스토리"));

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"))
        .andExpect(jsonPath("$.items[0].name").value("서초강산스토리"))
        .andExpect(jsonPath("$.items[0].displayName").doesNotExist())
        .andExpect(jsonPath("$.items[0].displayAddress").doesNotExist())
        .andExpect(jsonPath("$.items[0].categoryLabel").value("한식"));

    assertThat(requestedLang()).isEqualTo(Lang.KO);
  }

  @Test
  @DisplayName("ja 요청 — 스토어에 ja 가 가고, 분류 이름이 영어로만 나왔으면 Content-Language: en")
  void japaneseFallsBackToEnglish() throws Exception {
    givenPois(Set.of(Lang.EN), 1, poi(1, "서초강산스토리"));

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .header("Accept-Language", "ja"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "en"));

    assertThat(requestedLang()).isEqualTo(Lang.JA);
  }

  @Test
  @DisplayName("en 요청인데 분류 이름이 전부 한국어로 나왔으면 Content-Language: ko")
  void englishRequestAllKoreanLabels() throws Exception {
    givenPois(Set.of(Lang.KO), 1, poi(1, "서초강산스토리"));

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"));
  }

  @Test
  @DisplayName("en 요청에 영어·한국어 분류 이름이 섞이면 Content-Language: en")
  void englishRequestMixedLabels() throws Exception {
    givenPois(Set.of(Lang.EN, Lang.KO), 2, poi(1, "가"), poi(2, "나"));

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "en"));
  }

  @Test
  @DisplayName("상세 — 200, 기준점이 스토어로 전달된다")
  void detailFound() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "모슬포호텔", "호텔", PoiCategoryGroup.STAY, 33.2177, 126.2506, List.of())
            .categoryLabel("호텔")
            .tel("064-794-3355");
    when(store.findDetail(eq(7L), eq(Lang.KO), anyDouble(), anyDouble()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.KO)));

    mvc.perform(
            get("/pois/7")
                .param("lat", "33.2")
                .param("lng", "126.25")
                .header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"))
        .andExpect(jsonPath("$.name").value("모슬포호텔"))
        .andExpect(jsonPath("$.displayName").doesNotExist())
        .andExpect(jsonPath("$.categoryLabel").value("호텔"))
        .andExpect(jsonPath("$.tel").value("064-794-3355"))
        .andExpect(jsonPath("$.images").isArray())
        .andExpect(jsonPath("$.images.length()").value(0));

    verify(store).findDetail(eq(7L), eq(Lang.KO), eq(33.2), eq(126.25));
  }

  @Test
  @DisplayName("상세는 별점 요약·사진첩 앞 20 장·사진 수를 싣는다 — 리뷰가 없으면 average 는 null")
  void detailCarriesRatingAndGallery() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "모슬포호텔", "호텔", PoiCategoryGroup.STAY, 33.2177, 126.2506, List.of());
    when(store.findDetail(eq(7L), any(), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.KO)));
    when(reviews.gallery(ReviewStore.Target.POI, 7L, 20, 0))
        .thenReturn(
            new ReviewViews.Gallery(
                List.of(
                    new Photo(URI.create("https://img.example/p.jpg"), PhotoSource.OFFICIAL)
                        .credit("한국관광공사")),
                1));

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rating.count").value(0))
        .andExpect(jsonPath("$.rating.average").doesNotExist())
        .andExpect(jsonPath("$.rating.distribution.length()").value(5))
        .andExpect(jsonPath("$.photos.length()").value(1))
        .andExpect(jsonPath("$.photos[0].source").value("official"))
        .andExpect(jsonPath("$.photos[0].credit").value("한국관광공사"))
        .andExpect(jsonPath("$.photoCount").value(1));
  }

  @Test
  @DisplayName("상세 영어 — 요청 언어가 스토어로 가고, 사진이 순서대로 credit 과 함께 실린다")
  void detailInEnglishWithImages() throws Exception {
    PoiDetail detail =
        new PoiDetail(
                7L,
                "모슬포호텔",
                "호텔",
                PoiCategoryGroup.STAY,
                33.2177,
                126.2506,
                List.of(
                    new PoiImage(URI.create("https://img.example/a.jpg")).credit("한국관광공사"),
                    new PoiImage(URI.create("https://img.example/b.jpg"))))
            .displayName("Mosulpo Hotel")
            .categoryLabel("Hotel")
            .nameRoman("Moseulpohotel")
            .address("제주 서귀포시 대정읍")
            .displayAddress("Daejeong-eup, Seogwipo-si, Jeju-do");
    when(store.findDetail(eq(7L), eq(Lang.EN), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.EN)));

    mvc.perform(get("/pois/7").header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "en"))
        .andExpect(jsonPath("$.name").value("모슬포호텔"))
        .andExpect(jsonPath("$.displayName").value("Mosulpo Hotel"))
        .andExpect(jsonPath("$.nameRoman").value("Moseulpohotel"))
        .andExpect(jsonPath("$.category").value("호텔"))
        .andExpect(jsonPath("$.categoryLabel").value("Hotel"))
        .andExpect(jsonPath("$.address").value("제주 서귀포시 대정읍"))
        .andExpect(jsonPath("$.displayAddress").value("Daejeong-eup, Seogwipo-si, Jeju-do"))
        .andExpect(jsonPath("$.localName").doesNotExist())
        .andExpect(jsonPath("$.localAddress").doesNotExist())
        .andExpect(jsonPath("$.images.length()").value(2))
        .andExpect(jsonPath("$.images[0].url").value("https://img.example/a.jpg"))
        .andExpect(jsonPath("$.images[0].credit").value("한국관광공사"))
        .andExpect(jsonPath("$.images[1].url").value("https://img.example/b.jpg"));
  }

  @Test
  @DisplayName("상세 ja — 스토어에 ja 가 가고, 분류 이름이 영어로 나왔으면 Content-Language: en")
  void detailJapaneseFallsBackToEnglish() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "모슬포호텔", "호텔", PoiCategoryGroup.STAY, 33.2177, 126.2506, List.of())
            .displayName("Mosulpo Hotel")
            .categoryLabel("Hotel");
    when(store.findDetail(eq(7L), eq(Lang.JA), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.EN)));

    mvc.perform(get("/pois/7").header("Accept-Language", "ja"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "en"))
        .andExpect(jsonPath("$.name").value("모슬포호텔"))
        .andExpect(jsonPath("$.displayName").value("Mosulpo Hotel"));
  }

  @Test
  @DisplayName("상세 en 인데 분류가 사전에 없어 한국어로 나왔으면 Content-Language: ko")
  void detailEnglishWithKoreanLabel() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "모슬포호텔", "호텔", PoiCategoryGroup.STAY, 33.2177, 126.2506, List.of())
            .categoryLabel("호텔");
    when(store.findDetail(eq(7L), eq(Lang.EN), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.KO)));

    mvc.perform(get("/pois/7").header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"));
  }

  @Test
  @DisplayName("카드 단건 — 200, 못 찾아도 200")
  void cardFoundOrNot() throws Exception {
    when(cards.card(7L))
        .thenReturn(Optional.of(new PoiCard(7L).found(true).name("모슬포호텔").reviewCount(12)));
    mvc.perform(get("/pois/7/card"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(true))
        .andExpect(jsonPath("$.name").value("모슬포호텔"))
        .andExpect(jsonPath("$.reviewCount").value(12));

    when(cards.card(8L)).thenReturn(Optional.of(new PoiCard(8L).found(false).why("일치하는 장소가 없다")));
    mvc.perform(get("/pois/8/card"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(false))
        .andExpect(jsonPath("$.why").value("일치하는 장소가 없다"));
  }

  @Test
  @DisplayName("카드 단건 — POI 가 없으면 404 POI_NOT_FOUND")
  void cardMissingPoi() throws Exception {
    when(cards.card(anyLong())).thenReturn(Optional.empty());

    mvc.perform(get("/pois/999/card"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }

  @Test
  @DisplayName("카드 여럿 — ids 가 쉼표로 쪼개져 순서대로 전달되고, retryAfterSeconds 가 실린다")
  void cardsBatch() throws Exception {
    when(cards.cards(List.of(1L, 2L, 3L)))
        .thenReturn(
            new PoiCardBatch(
                    List.of(
                        new PoiCard(1L).found(true),
                        new PoiCard(2L).pending(true),
                        new PoiCard(3L).pending(true)))
                .retryAfterSeconds(7));

    mvc.perform(get("/pois/cards").param("ids", "1,2,3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[1].pending").value(true))
        .andExpect(jsonPath("$.retryAfterSeconds").value(7));
  }

  @Test
  @DisplayName("카드 여럿 — ids 가 없거나 51 개면 400")
  void cardsBatchValidation() throws Exception {
    mvc.perform(get("/pois/cards")).andExpect(status().isBadRequest());

    String fiftyOne = String.join(",", java.util.Collections.nCopies(51, "1"));
    mvc.perform(get("/pois/cards").param("ids", fiftyOne))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("없는 id 는 404 POI_NOT_FOUND")
  void detailMissing() throws Exception {
    when(store.findDetail(anyLong(), any(Lang.class), any(), any())).thenReturn(Optional.empty());

    mvc.perform(get("/pois/999").header("Accept-Language", "en"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }

  @Test
  @DisplayName("상세 — 네이버 장소 화면을 알면 naverPlaceUrl 로 싣는다 (ADR 0021)")
  void detailCarriesNaverPlaceUrl() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "모슬포호텔", "호텔", PoiCategoryGroup.STAY, 33.2177, 126.2506, List.of());
    when(store.findDetail(eq(7L), any(), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.KO)));
    when(naverLinks.placeUrl(any()))
        .thenReturn(Optional.of(URI.create("https://map.naver.com/p/entry/place/11679241")));

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.naverPlaceUrl").value("https://map.naver.com/p/entry/place/11679241"));
  }

  @Test
  @DisplayName("상세 — 네이버 장소 화면을 모르면 naverPlaceUrl 은 비고 상세는 그대로 200")
  void detailWithoutNaverPlaceUrl() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "모슬포호텔", "호텔", PoiCategoryGroup.STAY, 33.2177, 126.2506, List.of());
    when(store.findDetail(eq(7L), any(), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.KO)));
    when(naverLinks.placeUrl(any())).thenReturn(Optional.empty());

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("모슬포호텔"))
        .andExpect(jsonPath("$.naverPlaceUrl").doesNotExist());
  }

  // ───────────── 촬영지와 같은 곳 (계약 1.7.0 PoiSummary.placeId, MZ2AZ-371) ─────────────

  @Test
  @DisplayName("목록 — 같은 곳인 촬영지가 있으면 placeId 를 싣고, 없으면 칸이 없다. 연결된 편의시설도 목록에서 빠지지 않는다")
  void listCarriesPlaceIdOnlyWhenLinked() throws Exception {
    givenPois(2, poi(1, "카페 몽테드").placeId(45L), poi(2, "동네김밥"));

    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].placeId").value(45))
        .andExpect(jsonPath("$.items[1].placeId").doesNotExist())
        .andExpect(jsonPath("$.total").value(2));
  }

  @Test
  @DisplayName("상세 — 같은 곳인 편의시설이면 placeId 를 싣고, 별점·사진첩·사진 수는 그 촬영지의 것이다")
  void linkedDetailUsesPlaceRatingAndGallery() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "카페 몽테드", "카페", PoiCategoryGroup.FOOD, 37.28, 127.01, List.of());
    detail.placeId(45L);
    when(store.findDetail(eq(7L), any(), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.KO)));
    when(reviews.summary(ReviewStore.Target.PLACE, 45L))
        .thenReturn(new RatingSummary(3, List.of(0, 0, 1, 1, 1)).average(4.0));
    when(reviews.gallery(ReviewStore.Target.PLACE, 45L, 20, 0))
        .thenReturn(
            new ReviewViews.Gallery(
                List.of(
                    new Photo(URI.create("https://img.example/place.jpg"), PhotoSource.OFFICIAL)),
                9));

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.placeId").value(45))
        .andExpect(jsonPath("$.rating.count").value(3))
        .andExpect(jsonPath("$.rating.average").value(4.0))
        .andExpect(jsonPath("$.photos.length()").value(1))
        .andExpect(jsonPath("$.photos[0].url").value("https://img.example/place.jpg"))
        .andExpect(jsonPath("$.photoCount").value(9));
  }

  @Test
  @DisplayName("상세 — 연결되지 않은 편의시설은 placeId 칸이 없고 별점·사진첩은 편의시설 자신의 것이다")
  void unlinkedDetailUsesOwnRating() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "동네김밥", "분식", PoiCategoryGroup.FOOD, 37.28, 127.01, List.of());
    when(store.findDetail(eq(7L), any(), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(detail, Lang.KO)));
    when(reviews.summary(ReviewStore.Target.POI, 7L))
        .thenReturn(new RatingSummary(1, List.of(0, 0, 0, 0, 1)).average(5.0));

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.placeId").doesNotExist())
        .andExpect(jsonPath("$.rating.count").value(1));
  }
}
