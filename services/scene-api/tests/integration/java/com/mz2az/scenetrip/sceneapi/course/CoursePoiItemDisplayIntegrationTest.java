package com.mz2az.scenetrip.sceneapi.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemSource;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PinCategory;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.PoiStores;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 코스의 편의시설 항목의 표시말(scene-api 1.9.0, docs/project/plans/course-poi-item.md §3-1) — 실제 DB.
 *
 * <p>명세(계약 {@code CourseItem}): {@code displayName}·{@code nameRoman}·{@code categoryLabel}·{@code
 * displayAddress} 는 <b>편의시설 항목만</b> 있고, 값·규칙은 {@code PoiSummary} 의 같은 칸과 같다 — 요청 언어 → {@code en},
 * 한국어를 요청하면 {@code displayName}·{@code displayAddress} 는 null 이고 {@code categoryLabel} 은 {@code
 * category} 와 같은 한국어, 사전에 없는 분류도 한국어, {@code nameRoman} 은 언어와 무관. {@code name}·{@code
 * category}·{@code address} 는 어느 언어로든 한국어 원본이다. 촬영지·핀 항목에는 네 칸이 없다.
 *
 * <p>픽스처는 바다 위(위도 34.95 · 경도 129.65)에 두고 전부 {@link IntegrationDatabase#rolledBack} 안에서 돌린다. 분류 이름은
 * 실행마다 꼬리표를 붙여 사전과 겹치지 않게 한다.
 */
@DisplayName("코스의 편의시설 항목 표시말 — 언어 규칙·편의시설 상세와 일치 (실제 DB)")
class CoursePoiItemDisplayIntegrationTest {

  private static final double LAT = 34.95;
  private static final double LNG = 129.65;
  private static final double METERS_PER_LNG_DEGREE = 91_300.0;

  private static JdbcClient jdbc;
  private static CourseStore store;
  private static PoiStore pois;
  private static UserStore users;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  /** 사전에 en·ja 가 있다. */
  private final String catEnJa = "시험표시분류가" + tag;

  /** 사전에 en 만 있다. */
  private final String catEn = "시험표시분류나" + tag;

  /** 사전에 없다. */
  private final String catNone = "시험표시분류다" + tag;

  private UUID user;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    dwell.setByPlaceType("시험촬영지=45");
    dwell.setByPoiGroup("food=60, sight=60, stay=30, transit=15");
    store =
        new CourseStore(
            jdbc, new TravelEstimator(4.0, 1.3), dwell, IntegrationDatabase.transactions());
    pois = PoiStores.create(jdbc);
    users = new UserStore(jdbc);
  }

  /** 픽스처 편의시설의 id. */
  private record Ids(long full, long addressOnly, long nameOnly, long jaOnly, long bare) {
    List<Long> all() {
      return List.of(full, addressOnly, nameOnly, jaOnly, bare);
    }
  }

  /**
   * <ul>
   *   <li>full — en 이름·주소, ja 이름(주소 없음), 분류는 en·ja 사전
   *   <li>addressOnly — en 행에 주소만, 분류는 en 사전만
   *   <li>nameOnly — en 행에 이름만, 분류는 사전에 없음
   *   <li>jaOnly — ja 행만(en 행 없음), 분류는 en 사전만
   *   <li>bare — 번역 행·로마자·주소 없음, 분류는 en 사전만
   * </ul>
   */
  private Ids seed() {
    dict(catEnJa, "en", "Display Cat Ga");
    dict(catEnJa, "ja", "表示分類ガ");
    dict(catEn, "en", "Display Cat Na");

    long full = poi("full", "가표시식당", "Gapyosi Sikdang", catEnJa, "부산 기장군 가동", 0);
    translate(full, "en", "Gapyosi Restaurant", "1 Ga-ro, Busan");
    translate(full, "ja", "ガピョシ食堂", null);

    long addressOnly = poi("addr", "나표시", "Napyosi", catEn, "부산 기장군 나동", 30);
    translate(addressOnly, "en", null, "2 Na-ro, Busan");

    long nameOnly = poi("name", "다표시", "Dapyosi", catNone, "부산 기장군 다동", 60);
    translate(nameOnly, "en", "Da Display", null);

    long jaOnly = poi("ja", "마표시", "Mapyosi", catEn, "부산 기장군 마동", 90);
    translate(jaOnly, "ja", "マピョシ", "釜山 マ洞");

    long bare = poi("bare", "바표시", null, catEn, null, 120);

    return new Ids(full, addressOnly, nameOnly, jaOnly, bare);
  }

  // ── 1. 요청 언어 → en ───────────────────────────────────────────────────────

  @Test
  @DisplayName("en — 네 칸이 en 값이고, 이름·주소 번역이 없으면 null, 사전에 없는 분류는 한국어")
  void englishValues() {
    rolledBack(
        () -> {
          Ids ids = seed();
          List<CourseItem> items = courseOf(ids.all(), Lang.EN);

          assertDisplay(
              items.get(0),
              "Gapyosi Restaurant",
              "Gapyosi Sikdang",
              "Display Cat Ga",
              "1 Ga-ro, Busan");
          assertDisplay(items.get(1), null, "Napyosi", "Display Cat Na", "2 Na-ro, Busan");
          assertDisplay(items.get(2), "Da Display", "Dapyosi", catNone, null);
          // ja 행만 있는 편의시설 — en 요청은 ja 로 가지 않는다.
          assertDisplay(items.get(3), null, "Mapyosi", "Display Cat Na", null);
          assertDisplay(items.get(4), null, null, "Display Cat Na", null);
          return null;
        });
  }

  @Test
  @DisplayName("ja — 요청 언어가 먼저, 칸마다 없으면 en 으로 (이름 ja · 주소 en · 분류 ja)")
  void japaneseFallsBackPerField() {
    rolledBack(
        () -> {
          Ids ids = seed();
          List<CourseItem> items = courseOf(ids.all(), Lang.JA);

          // full: 이름은 ja, ja 행에 주소가 없어 주소는 en, 분류는 ja 사전.
          assertDisplay(items.get(0), "ガピョシ食堂", "Gapyosi Sikdang", "表示分類ガ", "1 Ga-ro, Busan");
          // addressOnly: ja 행이 없어 en, 분류 사전도 en 만.
          assertDisplay(items.get(1), null, "Napyosi", "Display Cat Na", "2 Na-ro, Busan");
          assertDisplay(items.get(2), "Da Display", "Dapyosi", catNone, null);
          assertDisplay(items.get(3), "マピョシ", "Mapyosi", "Display Cat Na", "釜山 マ洞");
          assertDisplay(items.get(4), null, null, "Display Cat Na", null);
          return null;
        });
  }

  @Test
  @DisplayName("zh-Hant — 번역 행이 하나도 없는 언어는 전부 en 으로")
  void unsupportedTranslationFallsBackToEnglish() {
    rolledBack(
        () -> {
          Ids ids = seed();
          List<CourseItem> items = courseOf(ids.all(), Lang.ZH_HANT);

          assertDisplay(
              items.get(0),
              "Gapyosi Restaurant",
              "Gapyosi Sikdang",
              "Display Cat Ga",
              "1 Ga-ro, Busan");
          assertDisplay(items.get(1), null, "Napyosi", "Display Cat Na", "2 Na-ro, Busan");
          assertDisplay(items.get(2), "Da Display", "Dapyosi", catNone, null);
          assertDisplay(items.get(3), null, "Mapyosi", "Display Cat Na", null);
          assertDisplay(items.get(4), null, null, "Display Cat Na", null);
          return null;
        });
  }

  // ── 2. ko ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "ko — displayName·displayAddress 는 null, categoryLabel 은 category 와 같고, nameRoman 은 있다")
  void koreanHasNoDisplayValues() {
    rolledBack(
        () -> {
          Ids ids = seed();
          List<CourseItem> items = courseOf(ids.all(), Lang.KO);

          for (CourseItem item : items) {
            assertThat(item.getSource()).isEqualTo(CourseItemSource.POI);
            assertThat(item.getDisplayName()).as("displayName").isNull();
            assertThat(item.getDisplayAddress()).as("displayAddress").isNull();
            assertThat(item.getCategoryLabel()).as("categoryLabel").isEqualTo(item.getCategory());
          }
          assertThat(items)
              .extracting(CourseItem::getNameRoman)
              .containsExactly("Gapyosi Sikdang", "Napyosi", "Dapyosi", "Mapyosi", null);
          return null;
        });
  }

  // ── 3. 촬영지·핀에는 없고, 원본 칸은 한국어 ─────────────────────────────────────

  @Test
  @DisplayName("촬영지·핀 항목에는 네 칸이 없다 — 어느 언어로든")
  void placeAndPinCarryNoDisplayFields() {
    rolledBack(
        () -> {
          seed();
          long p = place("plc", 200);
          long course = course();
          store.replace(
              course,
              replace(
                  new CourseItemInput().placeId(p).dwellMinutes(60),
                  new CourseItemInput()
                      .dwellMinutes(60)
                      .customPin(
                          new CustomPinInput(
                              "시험 표시 숙소",
                              PinCategory.LODGING,
                              LAT,
                              LNG + 300 / METERS_PER_LNG_DEGREE))));

          for (Lang lang : Lang.values()) {
            List<CourseItem> items = items(course, lang);
            assertThat(items)
                .extracting(CourseItem::getSource)
                .containsExactly(CourseItemSource.PLACE, CourseItemSource.CUSTOM_PIN);
            for (CourseItem item : items) {
              String at = lang + " " + item.getSource();
              assertThat(item.getDisplayName()).as(at + " displayName").isNull();
              assertThat(item.getNameRoman()).as(at + " nameRoman").isNull();
              assertThat(item.getCategoryLabel()).as(at + " categoryLabel").isNull();
              assertThat(item.getDisplayAddress()).as(at + " displayAddress").isNull();
            }
          }
          return null;
        });
  }

  @Test
  @DisplayName("편의시설 항목의 name·category·address 는 어느 언어로든 한국어 원본이다")
  void originalsStayKorean() {
    rolledBack(
        () -> {
          Ids ids = seed();
          long course = course();
          store.replace(course, replace(poiItems(ids.all())));

          for (Lang lang : Lang.values()) {
            List<CourseItem> items = items(course, lang);
            assertThat(items)
                .as(lang + " name")
                .extracting(CourseItem::getName)
                .containsExactly("가표시식당", "나표시", "다표시", "마표시", "바표시");
            assertThat(items)
                .as(lang + " category")
                .extracting(CourseItem::getCategory)
                .containsExactly(catEnJa, catEn, catNone, catEn, catEn);
            assertThat(items)
                .as(lang + " address")
                .extracting(CourseItem::getAddress)
                .containsExactly("부산 기장군 가동", "부산 기장군 나동", "부산 기장군 다동", "부산 기장군 마동", null);
          }
          return null;
        });
  }

  @Test
  @DisplayName("촬영지·편의시설·핀이 섞인 코스에서도 편의시설 항목만 네 칸을 싣는다 (en)")
  void mixedCourseOnlyPoiCarriesDisplay() {
    rolledBack(
        () -> {
          Ids ids = seed();
          long p = place("mix", 300);
          long course = course();
          store.replace(
              course,
              replace(
                  new CourseItemInput().placeId(p).dwellMinutes(60),
                  new CourseItemInput().poiId(ids.full()).dwellMinutes(60),
                  new CourseItemInput()
                      .dwellMinutes(60)
                      .customPin(
                          new CustomPinInput(
                              "시험 표시 핀",
                              PinCategory.LODGING,
                              LAT,
                              LNG + 400 / METERS_PER_LNG_DEGREE))));

          List<CourseItem> items = items(course, Lang.EN);
          assertThat(items)
              .extracting(CourseItem::getSource)
              .containsExactly(
                  CourseItemSource.PLACE, CourseItemSource.POI, CourseItemSource.CUSTOM_PIN);
          assertThat(items.get(0).getCategoryLabel()).isNull();
          assertThat(items.get(0).getNameRoman()).isNull();
          assertDisplay(
              items.get(1),
              "Gapyosi Restaurant",
              "Gapyosi Sikdang",
              "Display Cat Ga",
              "1 Ga-ro, Busan");
          assertThat(items.get(2).getCategoryLabel()).isNull();
          assertThat(items.get(2).getDisplayName()).isNull();
          return null;
        });
  }

  // ── 4. 편의시설 상세와 같은 값 ─────────────────────────────────────────────────

  @Test
  @DisplayName("모든 언어·모든 픽스처에서 코스 항목의 네 칸과 원본 칸이 PoiStore 상세와 같다")
  void agreesWithPoiDetail() {
    rolledBack(
        () -> {
          Ids ids = seed();
          long course = course();
          store.replace(course, replace(poiItems(ids.all())));

          for (Lang lang : Lang.values()) {
            List<CourseItem> items = items(course, lang);
            assertThat(items).hasSize(ids.all().size());
            for (int i = 0; i < items.size(); i++) {
              long id = ids.all().get(i);
              CourseItem item = items.get(i);
              PoiDetail detail = pois.findDetail(id, lang, null, null).orElseThrow().poi();
              String at = lang + " poi#" + i;
              assertThat(item.getPoiId()).as(at + " poiId").isEqualTo(id);
              assertThat(item.getDisplayName())
                  .as(at + " displayName")
                  .isEqualTo(detail.getDisplayName());
              assertThat(item.getNameRoman())
                  .as(at + " nameRoman")
                  .isEqualTo(detail.getNameRoman());
              assertThat(item.getCategoryLabel())
                  .as(at + " categoryLabel")
                  .isEqualTo(detail.getCategoryLabel());
              assertThat(item.getDisplayAddress())
                  .as(at + " displayAddress")
                  .isEqualTo(detail.getDisplayAddress());
              assertThat(item.getName()).as(at + " name").isEqualTo(detail.getName());
              assertThat(item.getCategory()).as(at + " category").isEqualTo(detail.getCategory());
              assertThat(item.getAddress()).as(at + " address").isEqualTo(detail.getAddress());
            }
          }
          return null;
        });
  }

  // ── 거들기 ──────────────────────────────────────────────────────────────────

  private static void assertDisplay(
      CourseItem item, String displayName, String nameRoman, String categoryLabel, String address) {
    String at = "poi item " + item.getPoiId();
    assertThat(item.getSource()).as(at + " source").isEqualTo(CourseItemSource.POI);
    assertThat(item.getDisplayName()).as(at + " displayName").isEqualTo(displayName);
    assertThat(item.getNameRoman()).as(at + " nameRoman").isEqualTo(nameRoman);
    assertThat(item.getCategoryLabel()).as(at + " categoryLabel").isEqualTo(categoryLabel);
    assertThat(item.getDisplayAddress()).as(at + " displayAddress").isEqualTo(address);
  }

  private void rolledBack(Supplier<Void> work) {
    IntegrationDatabase.rolledBack(
        () -> {
          user = users.resolve(UUID.randomUUID());
          return work.get();
        });
  }

  private long course() {
    return store.create(user, new CourseCreate(1, CourseOrigin.SELF));
  }

  private List<CourseItem> courseOf(List<Long> poiIds, Lang lang) {
    long course = course();
    store.replace(course, replace(poiItems(poiIds)));
    return items(course, lang);
  }

  private static CourseItemInput[] poiItems(List<Long> ids) {
    return ids.stream()
        .map(id -> new CourseItemInput().poiId(id).dwellMinutes(60))
        .toArray(CourseItemInput[]::new);
  }

  private static CourseReplace replace(CourseItemInput... items) {
    return new CourseReplace("표시말 시험 코스", List.of(new CourseDayInput(List.of(items))));
  }

  private List<CourseItem> items(long courseId, Lang lang) {
    return store.find(user, courseId, lang).orElseThrow().getDays().stream()
        .flatMap(d -> d.getItems().stream())
        .toList();
  }

  private static void dict(String ko, String lang, String name) {
    jdbc.sql(
            "INSERT INTO poi_category_i18n (ko, lang, name) VALUES (:ko, CAST(:lang AS lang_code),"
                + " :name)")
        .param("ko", ko)
        .param("lang", lang)
        .param("name", name)
        .update();
  }

  private long poi(
      String key, String name, String roman, String category, String address, double dxMeters) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, name_roman, geom, category, category_group, address)
            VALUES (:s, :n, :r, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :c, 'food', :a)
            RETURNING id
            """)
        .param("s", "it-course-poi-display-" + tag + "-" + key)
        .param("n", name)
        .param("r", roman)
        .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
        .param("lat", LAT)
        .param("c", category)
        .param("a", address)
        .query(Long.class)
        .single();
  }

  private static void translate(long poiId, String lang, String name, String address) {
    jdbc.sql(
            "INSERT INTO poi_i18n (poi_id, lang, name, address) VALUES (:id, CAST(:lang AS"
                + " lang_code), :name, :addr)")
        .param("id", poiId)
        .param("lang", lang)
        .param("name", name)
        .param("addr", address)
        .update();
  }

  private long place(String key, double dxMeters) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key) VALUES ('시험촬영지',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :k) RETURNING id")
            .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
            .param("lat", LAT)
            .param("k", "it-course-poi-display-" + tag + "-" + key)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name, address) VALUES (:p, 'ko', '시험 표시"
                + " 촬영지', '부산')")
        .param("p", id)
        .update();
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name, address) VALUES (:p, 'en', 'Test"
                + " Display Place', 'Busan')")
        .param("p", id)
        .update();
    return id;
  }
}
