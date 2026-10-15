package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.Photo;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoSource;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiInfo;
import com.mz2az.scenetrip.sceneapi.api.model.PoiInfoText;
import com.mz2az.scenetrip.sceneapi.poi.PoiStores;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinksForTests;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 편의시설 상세 {@code GET /v1/pois/{poiId}} 의 관광공사 안내({@code info})와 사진의 썸네일·라이선스(계약 1.21.0, MZ2AZ-376).
 *
 * <p>진짜 PoiStore·ReviewStore 로 컨트롤러를 만들고, {@code poi}·{@code poi_detail}·{@code poi_image}·리뷰 행을 직접
 * 넣어 응답을 본다. 넣은 POI 는 끝나면 지운다(상세·사진·리뷰는 CASCADE). 명세만 보고 쓴 시험.
 */
@DisplayName("편의시설 상세 — 관광공사 안내(칸마다 폴백)·사진 썸네일·라이선스 (실제 DB)")
class PoiDetailInfoIntegrationTest {

  private static JdbcClient jdbc;

  private final PoisController pois =
      new PoisController(
          PoiStores.create(jdbc),
          null,
          new ReviewViews(
              new ReviewStore(jdbc, IntegrationDatabase.transactions()), new SignedPhotos()),
          NaverLinksForTests.disabled(jdbc));

  private final List<Long> createdPois = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  @AfterEach
  void cleanUp() {
    for (long id : createdPois) {
      jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", id).update();
    }
  }

  // ───────────── info ─────────────

  @Test
  @DisplayName("상세 행이 하나도 없으면 info 는 null")
  void noDetailRowsMeansNoInfo() {
    long poi = poi();

    assertThat(get(poi, Lang.EN).getInfo()).isNull();
    assertThat(get(poi, Lang.KO).getInfo()).isNull();
  }

  @Test
  @DisplayName("en 요청 — 칸마다 따로: 개요는 en, 주차는 ko 로 폴백하고 lang 은 실제 언어, 둘 다 없으면 null")
  void perFieldFallbackToKo() {
    long poi = poi();
    detail(poi, "ko", "한국어 개요", null, null, "주차 가능", null, null, "https://ko.example.kr/");
    detail(poi, "en", "English overview", null, null, null, null, null, null);

    PoiInfo info = get(poi, Lang.EN).getInfo();

    assertThat(info).isNotNull();
    assertText(info.getOverview(), "English overview", Lang.EN);
    assertText(info.getParking(), "주차 가능", Lang.KO);
    assertThat(info.getUseTime()).isNull();
    assertThat(info.getRestDate()).isNull();
    assertThat(info.getFirstMenu()).isNull();
    assertThat(info.getUseFee()).isNull();
    assertThat(info.getHomepage()).isEqualTo(URI.create("https://ko.example.kr/"));
  }

  @Test
  @DisplayName("요청 언어에 홈페이지가 있으면 그것 — ko 보다 먼저")
  void homepagePrefersRequestedLanguage() {
    long poi = poi();
    detail(poi, "ko", "개요", null, null, null, null, null, "https://ko.example.kr/");
    detail(poi, "en", null, null, null, null, null, null, "https://en.example.com/");

    PoiInfo info = get(poi, Lang.EN).getInfo();

    assertThat(info.getHomepage()).isEqualTo(URI.create("https://en.example.com/"));
    assertText(info.getOverview(), "개요", Lang.KO);
  }

  @Test
  @DisplayName("ko 요청 — ko 줄이 먼저, ko 에 없는 칸은 en 으로(lang: en)")
  void koRequestFallsBackToEn() {
    long poi = poi();
    detail(poi, "ko", "한국어 개요", "09:00~18:00", "월요일", null, "비빔밥", null, null);
    detail(poi, "en", "English overview", "9am", null, "Free", null, "KRW 3,000", null);

    PoiInfo info = get(poi, Lang.KO).getInfo();

    assertText(info.getOverview(), "한국어 개요", Lang.KO);
    assertText(info.getUseTime(), "09:00~18:00", Lang.KO);
    assertText(info.getRestDate(), "월요일", Lang.KO);
    assertText(info.getFirstMenu(), "비빔밥", Lang.KO);
    assertText(info.getParking(), "Free", Lang.EN);
    assertText(info.getUseFee(), "KRW 3,000", Lang.EN);
  }

  @Test
  @DisplayName("ko 요청인데 개요가 en 에만 있으면 en 개요(lang: en)")
  void koRequestOverviewOnlyInEn() {
    long poi = poi();
    detail(poi, "ko", null, "09:00", null, null, null, null, null);
    detail(poi, "en", "English overview", null, null, null, null, null, null);

    PoiInfo info = get(poi, Lang.KO).getInfo();

    assertText(info.getOverview(), "English overview", Lang.EN);
    assertText(info.getUseTime(), "09:00", Lang.KO);
  }

  @Test
  @DisplayName("ja 요청인데 ja 줄이 없으면 칸마다 en → ko — 홈페이지도 en 먼저")
  void jaFallsBackToEnThenKo() {
    long poi = poi();
    detail(poi, "ko", "한국어 개요", null, null, "주차 가능", null, null, "https://ko.example.kr/");
    detail(poi, "en", "English overview", "9am", null, null, null, null, "https://en.example.com/");

    PoiInfo info = get(poi, Lang.JA).getInfo();

    assertText(info.getOverview(), "English overview", Lang.EN);
    assertText(info.getUseTime(), "9am", Lang.EN);
    assertText(info.getParking(), "주차 가능", Lang.KO);
    assertThat(info.getRestDate()).isNull();
    assertThat(info.getHomepage()).isEqualTo(URI.create("https://en.example.com/"));
  }

  @Test
  @DisplayName("ja 요청에 ja 줄이 있으면 그것이 먼저 — 없는 칸만 en → ko")
  void jaRowWinsWhenPresent() {
    long poi = poi();
    detail(poi, "ko", "한국어 개요", null, "월요일", null, null, null, null);
    detail(poi, "en", "English overview", "9am", null, null, null, null, null);
    detail(poi, "ja", "日本語の概要", null, null, null, null, null, null);

    PoiInfo info = get(poi, Lang.JA).getInfo();

    assertText(info.getOverview(), "日本語の概要", Lang.JA);
    assertText(info.getUseTime(), "9am", Lang.EN);
    assertText(info.getRestDate(), "월요일", Lang.KO);
  }

  @Test
  @DisplayName("en 줄만 있고 ja 요청이면 칸마다 en 값(lang: en) — info 는 null 이 아니다")
  void enOnlyRowServesJaRequest() {
    long poi = poi();
    detail(poi, "en", "English overview", "9am", null, null, null, null, "https://en.example.com/");

    PoiInfo info = get(poi, Lang.JA).getInfo();

    assertThat(info).isNotNull();
    assertText(info.getOverview(), "English overview", Lang.EN);
    assertText(info.getUseTime(), "9am", Lang.EN);
    assertThat(info.getParking()).isNull();
    assertThat(info.getHomepage()).isEqualTo(URI.create("https://en.example.com/"));
  }

  @Test
  @DisplayName("주소로 읽히지 않는 홈페이지는 null — 나머지 칸은 그대로")
  void unparsableHomepageIsNull() {
    long poi = poi();
    detail(poi, "ko", "개요", null, null, null, null, null, "http://bad host/a b^c");

    PoiInfo info = get(poi, Lang.KO).getInfo();

    assertThat(info.getHomepage()).isNull();
    assertText(info.getOverview(), "개요", Lang.KO);
  }

  @Test
  @DisplayName("닫힌 편의시설은 상세 행이 있어도 404 POI_NOT_FOUND")
  void closedPoiIsNotFound() {
    long poi = poi();
    detail(poi, "ko", "개요", null, null, null, null, null, null);
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", poi).update();

    assertThatThrownBy(() -> get(poi, Lang.KO))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(e.getCode()).isEqualTo("POI_NOT_FOUND");
            });
  }

  // ───────────── photos ─────────────

  @Test
  @DisplayName("우리 사진은 썸네일·라이선스를 저장된 그대로(없으면 null), 리뷰 사진은 둘 다 null — 순서·출처 표기는 그대로")
  void photosCarryThumbnailAndLicense() {
    long poi = poi();
    image(poi, "https://img.test/b.jpg", 20, "한국관광공사", null, null);
    image(poi, "https://img.test/a.jpg", 10, "한국관광공사", "https://img.test/a_s.jpg", "kogl_type3");
    image(poi, "https://img.test/c.jpg", 30, null, "https://img.test/c_s.jpg", "kogl_type1");
    String key = "it-poi-info/" + UUID.randomUUID() + ".jpg";
    reviewPhoto(poi, key);

    List<Photo> photos = get(poi, Lang.KO).getPhotos();

    assertThat(photos).hasSize(4);
    Photo a = photos.get(0);
    assertThat(a.getSource()).isEqualTo(PhotoSource.OFFICIAL);
    assertThat(a.getUrl()).isEqualTo(URI.create("https://img.test/a.jpg"));
    assertThat(a.getCredit()).isEqualTo("한국관광공사");
    assertThat(a.getThumbnailUrl()).isEqualTo(URI.create("https://img.test/a_s.jpg"));
    assertThat(a.getLicense().getValue()).isEqualTo("kogl_type3");

    Photo b = photos.get(1);
    assertThat(b.getUrl()).isEqualTo(URI.create("https://img.test/b.jpg"));
    assertThat(b.getThumbnailUrl()).isNull();
    assertThat(b.getLicense()).isNull();

    Photo c = photos.get(2);
    assertThat(c.getUrl()).isEqualTo(URI.create("https://img.test/c.jpg"));
    assertThat(c.getCredit()).isNull();
    assertThat(c.getThumbnailUrl()).isEqualTo(URI.create("https://img.test/c_s.jpg"));
    assertThat(c.getLicense().getValue()).isEqualTo("kogl_type1");

    Photo review = photos.get(3);
    assertThat(review.getSource()).isEqualTo(PhotoSource.REVIEW);
    assertThat(review.getUrl()).isEqualTo(URI.create("https://signed.example/" + key));
    assertThat(review.getThumbnailUrl()).isNull();
    assertThat(review.getLicense()).isNull();
  }

  // ───────────── 도우미 ─────────────

  private PoiDetail get(long poi, Lang lang) {
    return pois.getPoi(poi, lang, null, null).getBody();
  }

  private static void assertText(PoiInfoText field, String text, Lang lang) {
    assertThat(field).isNotNull();
    assertThat(field.getText()).isEqualTo(text);
    assertThat(field.getLang()).isEqualTo(lang);
  }

  private long poi() {
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group)
                VALUES (:s, '시험 관광지', ST_SetSRID(ST_MakePoint(126.25, 33.21), 4326)::geography, '관광지', 'sight')
                RETURNING id
                """)
            .param("s", "it-poi-info-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    createdPois.add(id);
    return id;
  }

  private static void detail(
      long poi,
      String lang,
      String overview,
      String useTime,
      String restDate,
      String parking,
      String firstMenu,
      String useFee,
      String homepage) {
    jdbc.sql(
            """
            INSERT INTO poi_detail (poi_id, lang, overview, use_time, rest_date, parking, first_menu,
                                    use_fee, homepage, tour_content_id)
            VALUES (:p, CAST(:l AS lang_code), :o, :ut, :rd, :pk, :fm, :uf, :hp, :c)
            """)
        .param("p", poi)
        .param("l", lang)
        .param("o", overview)
        .param("ut", useTime)
        .param("rd", restDate)
        .param("pk", parking)
        .param("fm", firstMenu)
        .param("uf", useFee)
        .param("hp", homepage)
        .param("c", "it-" + lang)
        .update();
  }

  private static void image(
      long poi, String url, int sort, String credit, String thumbnail, String license) {
    jdbc.sql(
            """
            INSERT INTO poi_image (poi_id, url, sort_order, source, credit, thumbnail_url, license, tour_content_id)
            VALUES (:p, :u, :o, 'tour_api', :c, :t, :l, 'it-ko')
            """)
        .param("p", poi)
        .param("u", url)
        .param("o", sort)
        .param("c", credit)
        .param("t", thumbnail)
        .param("l", license)
        .update();
  }

  /** 작성자가 탈퇴해 비어 있는(user_id NULL) 리뷰 하나와 그 사진 한 장. */
  private static void reviewPhoto(long poi, String key) {
    long review =
        jdbc.sql("INSERT INTO review (poi_id, user_id, rating) VALUES (:p, NULL, 5) RETURNING id")
            .param("p", poi)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO review_image (review_id, storage_key, sort_order) VALUES (:r, :k, 0)")
        .param("r", review)
        .param("k", key)
        .update();
  }

  /** 사진첩이 리뷰 사진 주소를 서명할 때 부르는 저장소 — 키를 붙인 가짜 주소. */
  private static final class SignedPhotos implements PhotoStorage {
    @Override
    public boolean available() {
      return true;
    }

    @Override
    public URI viewUrl(String storageKey) {
      return URI.create("https://signed.example/" + storageKey);
    }

    @Override
    public PresignedUpload presignUpload(
        String storageKey, String contentType, long bytes, Duration expiresIn) {
      return new PresignedUpload(
          URI.create("https://bucket.example/" + storageKey),
          Map.of("Content-Type", contentType),
          Instant.now().plus(expiresIn));
    }

    @Override
    public void move(String fromKey, String toKey) {}

    @Override
    public void listPages(String prefix, Consumer<List<StoredObject>> onPage) {}

    @Override
    public void delete(String storageKey) {}
  }
}
