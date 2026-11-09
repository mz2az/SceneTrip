package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceCity;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 도시별 촬영지 요약({@code GET /places/cities})과 {@code GET /places?city=} 를 진짜 PostgreSQL 에 태운다(계약
 * 1.26.0, MZ2AZ-545).
 *
 * <p><b>{@code cities} 시험은 적재된 촬영지를 트랜잭션 안에서 전부 숨긴 뒤</b> 픽스처만 넣고 센다 — 적재 데이터가 섞이면 수·순서를 정확히 볼 수 없다.
 * 끝나면 트랜잭션째 되돌리므로 숨김도 남지 않는다. 목록({@code list}) 시험은 숨기지 않고, 설명에 단 표식({@code q})으로 픽스처만 고른다.
 */
@DisplayName("PlaceStore — 도시 요약·도시 조건 (실제 DB)")
class PlaceCitiesIntegrationTest {

  private static JdbcClient jdbc;
  private static PlaceStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new PlaceStore(jdbc);
  }

  // ───────────── cities ─────────────

  @Test
  @DisplayName("수·순서 — 고정 다섯(있는 것만) 이 순서, 다음 촬영지 수 내림차순, 같으면 열쇠 가나다순")
  void countsAndOrder() {
    IntegrationDatabase.rolledBack(
        () -> {
          hideAllExisting();
          repeat(7, () -> place("경기도 수원시 팔달구 정조로", 37.28, 127.01));
          place("제주특별자치도 제주시 애월읍", 33.46, 126.33);
          repeat(2, () -> place("부산광역시 해운대구 우동", 35.16, 129.16));
          place("서울특별시 종로구 낙산성곽서1길 12", 37.58, 127.01);
          repeat(2, () -> place("경상북도 포항시 북구", 36.04, 129.36));
          repeat(2, () -> place("경기도 파주시 문산읍", 37.86, 126.78));
          place("강원특별자치도 강릉시 주문진읍", 37.89, 128.82);

          List<PlaceCity> cities = store.cities(Lang.KO);

          // 경주·인천은 0곳이라 빠진다. 수원(7곳)이 고정 다섯보다 많아도 그 뒤다.
          // 파주·포항은 2곳으로 같아 열쇠 가나다순(String 비교 — 한글 음절 부호 순서가 가나다순이다).
          assertThat(cities)
              .extracting(PlaceCity::getKey)
              .containsExactly("서울", "부산", "제주", "수원", "파주", "포항", "강릉");
          assertThat(counts(cities))
              .containsExactlyInAnyOrderEntriesOf(
                  Map.of("서울", 1, "부산", 2, "제주", 1, "수원", 7, "파주", 2, "포항", 2, "강릉", 1));
          return null;
        });
  }

  @Test
  @DisplayName("특별시·광역시는 구가 달라도 한 도시, 경기 광주는 광주광역시와 다른 도시")
  void groupsByAddressRules() {
    IntegrationDatabase.rolledBack(
        () -> {
          hideAllExisting();
          place("서울특별시 종로구 낙산성곽서1길 12", 37.58, 127.01);
          place("서울특별시 강남구 역삼동", 37.50, 127.04);
          place("광주광역시 동구 문화전당로", 35.15, 126.92);
          place("경기도 광주시 퇴촌면", 37.46, 127.31);

          Map<String, Integer> counts = counts(store.cities(Lang.KO));

          assertThat(counts)
              .containsExactlyInAnyOrderEntriesOf(Map.of("서울", 2, "광주", 1, "경기 광주", 1));
          return null;
        });
  }

  @Test
  @DisplayName("숨긴 촬영지는 세지 않는다 — 숨긴 것뿐인 도시는 빠진다")
  void hiddenPlacesAreNotCounted() {
    IntegrationDatabase.rolledBack(
        () -> {
          hideAllExisting();
          place("경상북도 경주시 첨성로", 35.83, 129.22);
          long hiddenGyeongju = place("경상북도 경주시 불국로", 35.79, 129.33);
          long hiddenOnly = place("충청남도 부여군 규암면", 36.28, 126.91);
          hide(hiddenGyeongju);
          hide(hiddenOnly);

          Map<String, Integer> counts = counts(store.cities(Lang.KO));

          assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("경주", 1));
          return null;
        });
  }

  @Test
  @DisplayName("한국어 주소로 도시를 정할 수 없는 촬영지는 세지 않는다 — 주소 없음·모르는 모양·ko 행 없음")
  void placesWithoutCityAreNotCounted() {
    IntegrationDatabase.rolledBack(
        () -> {
          hideAllExisting();
          place("서울특별시 종로구 낙산성곽서1길 12", 37.58, 127.01);
          place("Tokyo, Japan", 35.68, 139.76);
          place(null, 37.57, 126.98);
          // ko 행 없이 영어 주소만 — 도시는 한국어 주소로만 정한다.
          long englishOnly = bare(37.2, 127.0);
          i18n(
              englishOnly,
              "en",
              "English Only Fixture",
              "1 Jeongjo-ro, Paldal-gu, Suwon-si, Gyeonggi-do",
              null);

          List<PlaceCity> cities = store.cities(Lang.KO);

          assertThat(counts(cities)).containsExactlyInAnyOrderEntriesOf(Map.of("서울", 1));
          return null;
        });
  }

  @Test
  @DisplayName("영어 이름 — 광역은 표, 시·군은 그 도시 촬영지 영어 주소의 -si·-gun 을 뗀다, 영어 주소가 없으면 한국어 이름")
  void englishNames() {
    IntegrationDatabase.rolledBack(
        () -> {
          hideAllExisting();
          place("서울특별시 종로구 낙산성곽서1길 12", "12 Naksan-gil, Jongno-gu, Seoul", 37.58, 127.01, null);
          place(
              "경상북도 경주시 첨성로",
              "1 Cheomseong-ro, Gyeongju-si, Gyeongsangbuk-do",
              35.83,
              129.22,
              null);
          place("충청남도 부여군 규암면", "5 Gyuam, Buyeo-gun, Chungcheongnam-do", 36.28, 126.91, null);
          place(
              "경기도 광주시 퇴촌면",
              "1 Toechon-ro, Toechon-myeon, Gwangju-si, Gyeonggi-do",
              37.46,
              127.31,
              null);
          // 수원 — 영어 행이 없다.
          place("경기도 수원시 팔달구 정조로", 37.28, 127.01);

          Map<String, String> en = names(store.cities(Lang.EN));
          assertThat(en)
              .containsExactlyInAnyOrderEntriesOf(
                  Map.of(
                      "서울", "Seoul",
                      "경주", "Gyeongju",
                      "부여", "Buyeo",
                      "경기 광주", "Gwangju (Gyeonggi)",
                      "수원", "수원"));

          Map<String, String> ko = names(store.cities(Lang.KO));
          assertThat(ko)
              .as("한국어 요청은 한국어 이름 — 열쇠와 같다")
              .containsExactlyInAnyOrderEntriesOf(
                  Map.of("서울", "서울", "경주", "경주", "부여", "부여", "경기 광주", "경기 광주", "수원", "수원"));
          return null;
        });
  }

  @Test
  @DisplayName("가운데는 좌표 평균, bbox 는 [minLng, minLat, maxLng, maxLat]")
  void centerAndBbox() {
    IntegrationDatabase.rolledBack(
        () -> {
          hideAllExisting();
          place("서울특별시 종로구", 37.5, 126.9);
          place("서울특별시 강남구", 37.6, 127.1);
          place("서울특별시 마포구", 37.55, 127.0);

          PlaceCity seoul = only(store.cities(Lang.KO), "서울");

          assertThat(seoul.getPlaceCount()).isEqualTo(3);
          assertThat(seoul.getCenter().getLatitude()).isCloseTo(37.55, within(1e-6));
          assertThat(seoul.getCenter().getLongitude()).isCloseTo(127.0, within(1e-6));
          assertThat(seoul.getBbox()).hasSize(4);
          assertThat(seoul.getBbox().get(0)).isCloseTo(126.9, within(1e-6));
          assertThat(seoul.getBbox().get(1)).isCloseTo(37.5, within(1e-6));
          assertThat(seoul.getBbox().get(2)).isCloseTo(127.1, within(1e-6));
          assertThat(seoul.getBbox().get(3)).isCloseTo(37.6, within(1e-6));
          return null;
        });
  }

  @Test
  @DisplayName("적재 데이터 그대로도 질의가 성립한다 — 모든 도시가 1곳 이상, 고정 다섯이 앞")
  void runsOnSeededData() {
    List<PlaceCity> cities = store.cities(Lang.EN);

    assertThat(cities).isNotEmpty();
    assertThat(cities).allSatisfy(c -> assertThat(c.getPlaceCount()).isPositive());
    List<String> keys = cities.stream().map(PlaceCity::getKey).toList();
    List<String> fixedPresent = CityRules.FIXED_CITIES.stream().filter(keys::contains).toList();
    assertThat(keys.subList(0, fixedPresent.size())).containsExactlyElementsOf(fixedPresent);
  }

  // ───────────── list(city) ─────────────

  @Test
  @DisplayName("list — city 와 q 를 함께 쓰면 그 도시의 표식 촬영지만")
  void listByCityAndQuery() {
    IntegrationDatabase.rolledBack(
        () -> {
          String marker = marker();
          long seoulA = place("서울특별시 종로구", null, 37.58, 127.01, marker);
          long seoulB = place("서울특별시 강남구", null, 37.50, 127.04, marker);
          place("부산광역시 해운대구 우동", null, 35.16, 129.16, marker);

          PlaceStore.Page page = store.list(criteria(marker, null, "서울"));

          assertThat(ids(page)).containsExactlyInAnyOrder(seoulA, seoulB);
          assertThat(page.total()).isEqualTo(2);
          return null;
        });
  }

  @Test
  @DisplayName("list — city 와 bbox 를 함께 쓰면 둘 다 맞는 것만")
  void listByCityAndBbox() {
    IntegrationDatabase.rolledBack(
        () -> {
          String marker = marker();
          long inside = place("서울특별시 종로구", null, 37.58, 127.01, marker);
          place("서울특별시 강남구", null, 37.40, 127.20, marker);
          // bbox 안이지만 다른 도시(경기 고양) — 도시 조건이 걸러야 한다.
          place("경기도 고양시 덕양구", null, 37.59, 127.00, marker);

          Bbox box = new Bbox(126.95, 37.55, 127.05, 37.62);
          PlaceStore.Page page = store.list(criteria(marker, box, "서울"));

          assertThat(ids(page)).containsExactly(inside);
          return null;
        });
  }

  @Test
  @DisplayName("list — 「광주」 와 「경기 광주」 는 다른 도시다")
  void listDistinguishesGwangjus() {
    IntegrationDatabase.rolledBack(
        () -> {
          String marker = marker();
          long metro = place("광주광역시 동구 문화전당로", null, 35.15, 126.92, marker);
          long gyeonggi = place("경기도 광주시 퇴촌면", null, 37.46, 127.31, marker);

          assertThat(ids(store.list(criteria(marker, null, "광주")))).containsExactly(metro);
          assertThat(ids(store.list(criteria(marker, null, "경기 광주")))).containsExactly(gyeonggi);
          return null;
        });
  }

  @Test
  @DisplayName("list — 모르는 도시는 빈 목록")
  void unknownCityIsEmpty() {
    IntegrationDatabase.rolledBack(
        () -> {
          String marker = marker();
          place("서울특별시 종로구", null, 37.58, 127.01, marker);

          PlaceStore.Page withMarker = store.list(criteria(marker, null, "없는도시"));
          assertThat(withMarker.items()).isEmpty();
          assertThat(withMarker.total()).isZero();

          PlaceStore.Page alone = store.list(criteria(null, null, "없는도시"));
          assertThat(alone.items()).isEmpty();
          assertThat(alone.total()).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("list — city 만 주면 그 도시 촬영지만, 수는 cities 의 placeCount 와 같다")
  void listByCityMatchesCityCount() {
    PlaceCity first = store.cities(Lang.KO).get(0);

    PlaceStore.Page page = store.list(criteria(null, null, first.getKey()));

    assertThat(page.total()).isEqualTo(first.getPlaceCount().intValue());
  }

  @Test
  @DisplayName("list — city 로 조회하면 sceneDescription 은 contents 첫 작품의 장면 설명")
  void listByCitySceneDescriptionIsFirstContents() {
    IntegrationDatabase.rolledBack(
        () -> {
          String marker = marker();
          long seoul = place("서울특별시 종로구", null, 37.58, 127.01, marker);
          // 인기도가 높은 작품이 contents 앞에 온다 — 일부러 덜 인기 있는 작품을 먼저 넣는다.
          long minor = content("도시 장면 둘째 " + marker, 10);
          long major = content("도시 장면 첫째 " + marker, 90);
          link(seoul, minor, "둘째 작품의 장면");
          link(seoul, major, "첫 작품의 장면");

          PlaceSummary item = onlyItem(store.list(criteria(marker, null, "서울")), seoul);

          assertThat(item.getContents()).isNotEmpty();
          assertThat(item.getContents().get(0).getContentId()).isEqualTo(major);
          assertThat(item.getSceneDescription()).isEqualTo("첫 작품의 장면");
          return null;
        });
  }

  @Test
  @DisplayName("list — city 도 contentId 도 없으면 sceneDescription 은 null")
  void listWithoutCityHasNoSceneDescription() {
    IntegrationDatabase.rolledBack(
        () -> {
          String marker = marker();
          long seoul = place("서울특별시 종로구", null, 37.58, 127.01, marker);
          long major = content("도시 장면 첫째 " + marker, 90);
          link(seoul, major, "첫 작품의 장면");

          PlaceSummary item = onlyItem(store.list(criteria(marker, null, null)), seoul);

          assertThat(item.getContents()).isNotEmpty();
          assertThat(item.getSceneDescription()).isNull();
          return null;
        });
  }

  // ───────────── 픽스처 ─────────────

  /** 이 트랜잭션 안에서만 적재된 촬영지를 전부 숨긴다 — 되돌리면 원래대로다. */
  private static void hideAllExisting() {
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE hidden_at IS NULL").update();
  }

  private static void hide(long id) {
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", id).update();
  }

  private static long place(String koAddress, double lat, double lng) {
    return place(koAddress, null, lat, lng, null);
  }

  /** 촬영지 하나 — ko 행(주소), en 주소가 있으면 en 행도. 표식은 설명에 단다(q 로 고르게). */
  private static long place(
      String koAddress, String enAddress, double lat, double lng, String marker) {
    long id = bare(lat, lng);
    i18n(id, "ko", "도시 픽스처 " + id, koAddress, marker);
    if (enAddress != null) {
      i18n(id, "en", "City Fixture " + id, enAddress, marker);
    }
    return id;
  }

  private static long bare(double lat, double lng) {
    return jdbc.sql(
            "INSERT INTO place (geom)"
                + " VALUES (ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)"
                + " RETURNING id")
        .param("lng", lng)
        .param("lat", lat)
        .query(Long.class)
        .single();
  }

  private static void i18n(
      long placeId, String lang, String name, String address, String description) {
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name, address, description)"
                + " VALUES (:id, :lang, :name, :address, :description)")
        .param("id", placeId)
        .param("lang", lang)
        .param("name", name)
        .param("address", address)
        .param("description", description)
        .update();
  }

  private static void repeat(int times, Runnable insert) {
    for (int i = 0; i < times; i++) {
      insert.run();
    }
  }

  private static String marker() {
    return "zzcity" + UUID.randomUUID().toString().replace("-", "");
  }

  private static PlaceStore.Criteria criteria(String q, Bbox bbox, String city) {
    return new PlaceStore.Criteria(
        q, null, bbox, null, null, null, PlaceStore.Sort.POPULARITY, Lang.KO, 50, 0, city);
  }

  private static Map<String, Integer> counts(List<PlaceCity> cities) {
    return cities.stream().collect(Collectors.toMap(PlaceCity::getKey, PlaceCity::getPlaceCount));
  }

  private static Map<String, String> names(List<PlaceCity> cities) {
    return cities.stream().collect(Collectors.toMap(PlaceCity::getKey, PlaceCity::getName));
  }

  private static PlaceCity only(List<PlaceCity> cities, String key) {
    Optional<PlaceCity> found = cities.stream().filter(c -> key.equals(c.getKey())).findFirst();
    return found.orElseThrow(() -> new AssertionError("도시가 없다: " + key));
  }

  /** 작품 하나 — ko 제목, 인기도(contents 순서를 정한다). */
  private static long content(String title, int popularity) {
    long id =
        jdbc.sql(
                "INSERT INTO content (category, popularity_score) VALUES ('drama', :s) RETURNING"
                    + " id")
            .param("s", popularity)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO content_i18n (content_id, lang, title)"
                + " VALUES (:id, CAST('ko' AS lang_code), :title)")
        .param("id", id)
        .param("title", title)
        .update();
    return id;
  }

  /** 촬영지 × 작품 연결과 그 ko 장면 설명. */
  private static void link(long placeId, long contentId, String sceneDescription) {
    long pcId =
        jdbc.sql("INSERT INTO place_content (place_id, content_id) VALUES (:p, :c) RETURNING id")
            .param("p", placeId)
            .param("c", contentId)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO place_content_i18n (place_content_id, lang, relation_description)"
                + " VALUES (:id, CAST('ko' AS lang_code), :d)")
        .param("id", pcId)
        .param("d", sceneDescription)
        .update();
  }

  private static PlaceSummary onlyItem(PlaceStore.Page page, long placeId) {
    assertThat(ids(page)).containsExactly(placeId);
    return page.items().get(0);
  }

  private static List<Long> ids(PlaceStore.Page page) {
    return page.items().stream().map(PlaceSummary::getId).toList();
  }
}
