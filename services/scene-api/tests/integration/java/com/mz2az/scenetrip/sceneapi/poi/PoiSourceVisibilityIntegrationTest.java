package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 편의시설 상세({@code GET /v1/pois/{id}} 가 쓰는 {@link PoiStore#findDetail})의 관광공사 안내·사진은 그 관광공사 번호가 살아 있을
 * 때만 나간다 — poi-load-cleanup.md §3 (MZ2AZ-491, V38 poi_detail_visible·poi_image_visible).
 *
 * <p>숨기는 것은 번호 기록에 {@code active = false} 로 적힌 번호뿐이다 — 기록이 없는 번호, 관광공사 번호가 없는 사진은 늘 나간다. 행은 지우지
 * 않으므로 다시 살아나면 그대로 보인다. 국문 번호는 {@code tour-<번호>}, 영문은 {@code tour-en-<번호>}. 픽스처는 바다 위(위도 34.0 · 경도
 * 124.5)에 두고 트랜잭션은 끝나면 되돌린다. 명세만 보고 쓴 시험.
 */
@DisplayName("상세·사진 — 표출 중단된 관광공사 번호는 숨김, 다시 살아나면 보임 (실제 DB)")
class PoiSourceVisibilityIntegrationTest {

  private static JdbcClient jdbc;
  private static PoiStore store;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = PoiStores.create(jdbc);
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────

  private long poi() {
    String sourceId = "tour-it-vis-" + tag;
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group)
                VALUES (:s, '시험 표출', ST_SetSRID(ST_MakePoint(124.5, 34.0), 4326)::geography, '관광지', 'sight')
                RETURNING id
                """)
            .param("s", sourceId)
            .query(Long.class)
            .single();
    record(sourceId, id, "tour", true);
    return id;
  }

  private String no(String s) {
    return "vis" + tag + s;
  }

  private static void record(String sourceId, long poiId, String source, boolean active) {
    jdbc.sql("INSERT INTO poi_source (source_id, poi_id, source, active) VALUES (:s, :p, :src, :a)")
        .param("s", sourceId)
        .param("p", poiId)
        .param("src", source)
        .param("a", active)
        .update();
  }

  private static void setActive(String sourceId, boolean active) {
    int n =
        jdbc.sql("UPDATE poi_source SET active = :a WHERE source_id = :s")
            .param("a", active)
            .param("s", sourceId)
            .update();
    assertThat(n).isEqualTo(1);
  }

  private static void detail(long poiId, String lang, String overview, String contentId) {
    jdbc.sql(
            """
            INSERT INTO poi_detail (poi_id, lang, overview, tour_content_id)
            VALUES (:p, CAST(:l AS lang_code), :o, :c)
            """)
        .param("p", poiId)
        .param("l", lang)
        .param("o", overview)
        .param("c", contentId)
        .update();
  }

  private void image(long poiId, String name, int sort, String source, String contentId) {
    jdbc.sql(
            """
            INSERT INTO poi_image (poi_id, url, sort_order, source, tour_content_id)
            VALUES (:p, :u, :o, :s, :c)
            """)
        .param("p", poiId)
        .param("u", url(name))
        .param("o", sort)
        .param("s", source)
        .param("c", contentId)
        .update();
  }

  private String url(String name) {
    return "https://img.test/vis/" + tag + "/" + name + ".jpg";
  }

  private static PoiDetail get(long poiId, Lang lang) {
    return store.findDetail(poiId, lang, null, null).orElseThrow().poi();
  }

  private static List<String> photos(PoiDetail d) {
    return d.getImages().stream().map(PoiImage::getUrl).map(Object::toString).toList();
  }

  // ── 시험 ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("국문 번호를 멈추면 안내·그 번호 사진이 빠지고, 번호 없는 사진·다른 출처 사진은 남는다 — 다시 살리면 그대로 보인다")
  void inactiveNumberHidesAndReactivationRestores() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poi();
          String k = no("k");
          record("tour-" + k, p, "tour", true);
          detail(p, "ko", "살아 있는 개요", k);
          image(p, "tour", 10, "tour_api", k);
          image(p, "legacy", 20, "tour_api", null);
          image(p, "own", 30, "own", null);

          PoiDetail before = get(p, Lang.KO);
          assertThat(before.getInfo()).isNotNull();
          assertThat(before.getInfo().getOverview().getText()).isEqualTo("살아 있는 개요");
          assertThat(photos(before)).containsExactly(url("tour"), url("legacy"), url("own"));

          setActive("tour-" + k, false);
          PoiDetail hidden = get(p, Lang.KO);
          assertThat(hidden.getInfo()).isNull();
          assertThat(photos(hidden)).containsExactly(url("legacy"), url("own"));
          // 행은 지우지 않는다
          assertThat(
                  jdbc.sql("SELECT count(*) FROM poi_image WHERE poi_id = :p")
                      .param("p", p)
                      .query(Long.class)
                      .single())
              .isEqualTo(3L);

          setActive("tour-" + k, true);
          PoiDetail again = get(p, Lang.KO);
          assertThat(again.getInfo().getOverview().getText()).isEqualTo("살아 있는 개요");
          assertThat(photos(again)).containsExactly(url("tour"), url("legacy"), url("own"));
          return null;
        });
  }

  @Test
  @DisplayName("기록이 없는 관광공사 번호의 안내·사진은 막지 않는다")
  void unrecordedNumberIsShown() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poi();
          String k = no("free");
          detail(p, "ko", "기록 없는 개요", k);
          image(p, "free", 10, "tour_api", k);

          PoiDetail d = get(p, Lang.KO);

          assertThat(d.getInfo().getOverview().getText()).isEqualTo("기록 없는 개요");
          assertThat(photos(d)).containsExactly(url("free"));
          return null;
        });
  }

  @Test
  @DisplayName("영문 번호만 멈추면 영문 안내·영문 사진만 빠진다 — en 요청은 칸마다 ko 로 내려가고 국문 사진은 남는다")
  void inactiveEnglishNumberHidesOnlyEnglish() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poi();
          String k = no("k");
          String e = no("e");
          record("tour-" + k, p, "tour", true);
          record("tour-en-" + e, p, "tour_en", false);
          detail(p, "ko", "국문 개요", k);
          detail(p, "en", "English overview", e);
          image(p, "ko", 10, "tour_api", k);
          image(p, "en", 1010, "tour_api", e);

          PoiDetail en = get(p, Lang.EN);

          assertThat(en.getInfo()).isNotNull();
          assertThat(en.getInfo().getOverview().getText()).isEqualTo("국문 개요");
          assertThat(en.getInfo().getOverview().getLang()).isEqualTo(Lang.KO);
          assertThat(photos(en)).containsExactly(url("ko"));

          setActive("tour-en-" + e, true);
          PoiDetail back = get(p, Lang.EN);
          assertThat(back.getInfo().getOverview().getText()).isEqualTo("English overview");
          assertThat(photos(back)).containsExactly(url("ko"), url("en"));
          return null;
        });
  }

  @Test
  @DisplayName("국문·영문 번호 체계는 따로다 — 같은 숫자의 영문 번호가 멈춰도 국문 상세는 나간다")
  void englishNamespaceDoesNotHideKorean() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poi();
          String n = no("same");
          record("tour-" + n, p, "tour", true);
          record("tour-en-" + n, p, "tour_en", false);
          detail(p, "ko", "국문은 살아 있다", n);

          PoiDetail d = get(p, Lang.KO);

          assertThat(d.getInfo()).isNotNull();
          assertThat(d.getInfo().getOverview().getText()).isEqualTo("국문은 살아 있다");
          return null;
        });
  }
}
