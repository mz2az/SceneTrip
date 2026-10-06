package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiImage;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 편의시설 응답의 언어 — 기존 칸은 한국어, 요청 언어는 새 칸으로만(계약 맨 위 「언어」, docs/project/plans/poi-i18n-image.md §13-4).
 *
 * <p>{@code name}·{@code address}·{@code category} 는 어느 언어로든 한국어 원본이다(이미 나가 있는 앱의 호환). 요청 언어의 값은
 * {@code displayName}·{@code displayAddress}·{@code categoryLabel} 로만 오고, 각각 요청 언어 → {@code en} 을
 * 따르며 없거나 한국어를 요청하면 null(분류 이름은 한국어)이다. 바다 위 한 점에 픽스처를 만들고 끝나면 되돌린다. 분류는 사전과 겹치지 않게 지어낸 이름을 쓴다.
 */
@DisplayName("PoiStore — 편의시설 응답의 언어와 사진")
class PoiI18nResponseIntegrationTest {
  private static final double LNG = 125.5;
  private static final double LAT = 32.5;
  private static final Bbox SPOT = new Bbox(125.4999, 32.4999, 125.5001, 32.5001);

  /** 사전에 en·ja 가 있다. */
  private static final String CAT_EN_JA = "시험분류가";

  /** 사전에 en 만 있다. */
  private static final String CAT_EN = "시험분류나";

  /** 사전에 없다. */
  private static final String CAT_NONE = "시험분류다";

  private static JdbcClient jdbc;
  private static PoiStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new PoiStore(jdbc);
  }

  /** 픽스처 POI 의 id. */
  private record Ids(
      long full, long addressOnly, long nameOnly, long jaOnly, long bare, long closed) {}

  /** 열린 픽스처 POI 수 — closed 를 뺀다. */
  private static final int OPEN = 5;

  /**
   * <ul>
   *   <li>full — en 이름·주소·도로, ja 이름(주소 없음), 사진 둘(넣는 순서와 정렬 순서가 반대), 분류는 en·ja 사전
   *   <li>addressOnly — en 행에 주소만(이름 번역 없음), 분류는 en 사전만
   *   <li>nameOnly — en 행에 이름만(주소 번역 없음), 분류는 사전에 없음
   *   <li>jaOnly — ja 행에 이름·주소만(en 행 없음) — 폴백은 ja 로 가지 않는다
   *   <li>bare — 번역 행 없음, 한국어 주소도 로마자도 없음
   *   <li>closed — full 과 같은 번역이 있지만 폐업
   * </ul>
   */
  private static Ids seed() {
    dict(CAT_EN_JA, "en", "Test Category Ga");
    dict(CAT_EN_JA, "ja", "テスト分類ガ");
    dict(CAT_EN, "en", "Test Category Na");

    long full = poi("i18n-full", "가시험식당", "Gasiheom Sikdang", CAT_EN_JA, "제주 가동", "시험로", false);
    translate(full, "en", "Gasiheom Restaurant", "1 Ga-ro, Jeju", "Siheom-ro");
    translate(full, "ja", "ガシホム食堂", null, null);
    image(full, "https://img.example/second.jpg", 20, null);
    image(full, "https://img.example/first.jpg", 10, "한국관광공사");

    long addressOnly = poi("i18n-addr", "나시험", "Nasiheom", CAT_EN, "제주 나동", null, false);
    translate(addressOnly, "en", null, "2 Na-ro, Jeju", null);

    long nameOnly = poi("i18n-name", "다시험", "Dasiheom", CAT_NONE, "제주 다동", null, false);
    translate(nameOnly, "en", "Da Test", null, null);

    long jaOnly = poi("i18n-ja", "마시험", "Masiheom", CAT_EN, "제주 마동", null, false);
    translate(jaOnly, "ja", "マシホム", "済州 マ洞", null);

    long bare = poi("i18n-bare", "업소명없음", null, CAT_EN, null, null, false);

    long closed = poi("i18n-closed", "라시험", "Rasiheom", CAT_EN_JA, "제주 라동", null, true);
    translate(closed, "en", "Ra Test", "4 Ra-ro, Jeju", null);

    return new Ids(full, addressOnly, nameOnly, jaOnly, bare, closed);
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

  private static long poi(
      String sourceId,
      String name,
      String roman,
      String category,
      String address,
      String road,
      boolean closed) {
    return jdbc.sql(
            "INSERT INTO poi (source_id, name, name_roman, geom, category, category_group, address,"
                + " road, region, city, closed_at) VALUES (:s, :name, :roman,"
                + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :cat, 'food', :addr,"
                + " :road, '제주', '서귀포시', "
                + (closed ? "now()" : "NULL")
                + ") RETURNING id")
        .param("s", "poi-i18n-response-test-" + sourceId)
        .param("name", name)
        .param("roman", roman)
        .param("lng", LNG)
        .param("lat", LAT)
        .param("cat", category)
        .param("addr", address)
        .param("road", road)
        .query(Long.class)
        .single();
  }

  private static void translate(long poiId, String lang, String name, String address, String road) {
    jdbc.sql(
            "INSERT INTO poi_i18n (poi_id, lang, name, address, road) VALUES (:id, CAST(:lang AS"
                + " lang_code), :name, :addr, :road)")
        .param("id", poiId)
        .param("lang", lang)
        .param("name", name)
        .param("addr", address)
        .param("road", road)
        .update();
  }

  private static void image(long poiId, String url, int sortOrder, String credit) {
    jdbc.sql(
            "INSERT INTO poi_image (poi_id, url, sort_order, source, credit) VALUES (:id, :url,"
                + " :ord, 'test', :credit)")
        .param("id", poiId)
        .param("url", url)
        .param("ord", sortOrder)
        .param("credit", credit)
        .update();
  }

  private record Listed(Ids ids, Map<Long, PoiSummary> byId, PoiStore.Page page) {}

  private static Listed list(Lang lang) {
    return IntegrationDatabase.rolledBack(
        () -> {
          Ids ids = seed();
          PoiStore.Page page =
              store.list(
                  new PoiStore.Criteria(
                      SPOT, null, null, null, null, PoiStore.Sort.ALPHABETICAL, 50, 0, lang));
          return new Listed(
              ids,
              page.items().stream()
                  .collect(Collectors.toMap(PoiSummary::getId, Function.identity())),
              page);
        });
  }

  private static <T> T withFixtures(Function<Ids, T> work) {
    return IntegrationDatabase.rolledBack(() -> work.apply(seed()));
  }

  /** 픽스처마다 한국어 원본 — 기존 칸은 언어와 상관없이 이 값이어야 한다. */
  private record Original(String name, String address, String category) {}

  private static Map<Long, Original> originals(Ids ids) {
    return Map.of(
        ids.full(), new Original("가시험식당", "제주 가동", CAT_EN_JA),
        ids.addressOnly(), new Original("나시험", "제주 나동", CAT_EN),
        ids.nameOnly(), new Original("다시험", "제주 다동", CAT_NONE),
        ids.jaOnly(), new Original("마시험", "제주 마동", CAT_EN),
        ids.bare(), new Original("업소명없음", null, CAT_EN));
  }

  // ───────────── 호환: 기존 칸은 언제나 한국어 ─────────────

  @Test
  @DisplayName("목록 — name·address·category 는 어느 언어로든 한국어 원본이다(옛 앱 호환)")
  void listExistingFieldsStayKoreanInEveryLanguage() {
    for (Lang lang : Lang.values()) {
      Listed l = list(lang);
      Map<Long, Original> want = originals(l.ids());
      assertThat(l.byId()).as("items for %s", lang).hasSize(OPEN);
      want.forEach(
          (id, o) -> {
            PoiSummary p = l.byId().get(id);
            assertThat(p.getName()).as("name of %s in %s", id, lang).isEqualTo(o.name());
            assertThat(p.getAddress()).as("address of %s in %s", id, lang).isEqualTo(o.address());
            assertThat(p.getCategory())
                .as("category of %s in %s", id, lang)
                .isEqualTo(o.category());
          });
    }
  }

  @Test
  @DisplayName("상세 — name·address·category 는 어느 언어로든 한국어 원본이다(옛 앱 호환)")
  void detailExistingFieldsStayKoreanInEveryLanguage() {
    for (Lang lang : Lang.values()) {
      Map<Long, Original> want = new HashMap<>();
      Map<Long, PoiDetail> got =
          withFixtures(
              ids -> {
                want.putAll(originals(ids));
                Map<Long, PoiDetail> m = new HashMap<>();
                for (long id : originals(ids).keySet()) {
                  m.put(id, store.findDetail(id, lang, null, null).orElseThrow().poi());
                }
                return m;
              });
      want.forEach(
          (id, o) -> {
            PoiDetail p = got.get(id);
            assertThat(p.getName()).as("name of %s in %s", id, lang).isEqualTo(o.name());
            assertThat(p.getAddress()).as("address of %s in %s", id, lang).isEqualTo(o.address());
            assertThat(p.getCategory())
                .as("category of %s in %s", id, lang)
                .isEqualTo(o.category());
          });
    }
  }

  // ───────────── 목록 ─────────────

  @Test
  @DisplayName("en — displayName·displayAddress·categoryLabel 이 영어, 기존 칸은 한국어")
  void englishFullyTranslated() {
    Listed l = list(Lang.EN);
    PoiSummary p = l.byId().get(l.ids().full());

    assertThat(p.getName()).isEqualTo("가시험식당");
    assertThat(p.getDisplayName()).isEqualTo("Gasiheom Restaurant");
    assertThat(p.getNameRoman()).isEqualTo("Gasiheom Sikdang");
    assertThat(p.getCategory()).isEqualTo(CAT_EN_JA);
    assertThat(p.getCategoryLabel()).isEqualTo("Test Category Ga");
    assertThat(p.getAddress()).isEqualTo("제주 가동");
    assertThat(p.getDisplayAddress()).isEqualTo("1 Ga-ro, Jeju");
  }

  @Test
  @DisplayName("en — 이름 번역이 없고 주소만 있으면 displayName 은 null, displayAddress 는 영어")
  void englishAddressOnly() {
    Listed l = list(Lang.EN);
    PoiSummary p = l.byId().get(l.ids().addressOnly());

    assertThat(p.getName()).isEqualTo("나시험");
    assertThat(p.getDisplayName()).isNull();
    assertThat(p.getNameRoman()).isEqualTo("Nasiheom");
    assertThat(p.getAddress()).isEqualTo("제주 나동");
    assertThat(p.getDisplayAddress()).isEqualTo("2 Na-ro, Jeju");
    assertThat(p.getCategoryLabel()).isEqualTo("Test Category Na");
  }

  @Test
  @DisplayName("en — 이름만 번역이 있으면 displayAddress 는 null, 사전에 없는 분류는 categoryLabel 이 한국어 원본")
  void englishNameOnlyUnknownCategory() {
    Listed l = list(Lang.EN);
    PoiSummary p = l.byId().get(l.ids().nameOnly());

    assertThat(p.getName()).isEqualTo("다시험");
    assertThat(p.getDisplayName()).isEqualTo("Da Test");
    assertThat(p.getAddress()).isEqualTo("제주 다동");
    assertThat(p.getDisplayAddress()).isNull();
    assertThat(p.getCategory()).isEqualTo(CAT_NONE);
    assertThat(p.getCategoryLabel()).isEqualTo(CAT_NONE);
  }

  @Test
  @DisplayName("en — 번역 행이 없으면 display 칸은 null, 주소·로마자가 없으면 null")
  void englishNoTranslationRow() {
    Listed l = list(Lang.EN);
    PoiSummary p = l.byId().get(l.ids().bare());

    assertThat(p.getName()).isEqualTo("업소명없음");
    assertThat(p.getDisplayName()).isNull();
    assertThat(p.getNameRoman()).isNull();
    assertThat(p.getAddress()).isNull();
    assertThat(p.getDisplayAddress()).isNull();
    assertThat(p.getCategoryLabel()).isEqualTo("Test Category Na");
  }

  @Test
  @DisplayName("en — ja 번역만 있으면 display 칸은 null(폴백은 요청 언어 → en 뿐)")
  void englishDoesNotFallBackToJapanese() {
    Listed l = list(Lang.EN);
    PoiSummary p = l.byId().get(l.ids().jaOnly());

    assertThat(p.getName()).isEqualTo("마시험");
    assertThat(p.getDisplayName()).isNull();
    assertThat(p.getDisplayAddress()).isNull();
  }

  @Test
  @DisplayName("en — shownLangs 는 분류 이름이 나온 언어들: 영어와(사전에 없는 분류의) 한국어")
  void englishShownLangs() {
    Listed l = list(Lang.EN);
    assertThat(l.page().shownLangs()).containsExactlyInAnyOrder(Lang.EN, Lang.KO);
  }

  @Test
  @DisplayName("ja — 새 칸마다 ja → en: 이름은 ja, 주소는 en, 분류 이름은 ja·en·ko 가 섞인다")
  void japanesePerFieldFallback() {
    Listed l = list(Lang.JA);
    PoiSummary full = l.byId().get(l.ids().full());
    assertThat(full.getDisplayName()).isEqualTo("ガシホム食堂");
    assertThat(full.getDisplayAddress()).isEqualTo("1 Ga-ro, Jeju");
    assertThat(full.getCategoryLabel()).isEqualTo("テスト分類ガ");
    assertThat(full.getName()).isEqualTo("가시험식당");
    assertThat(full.getAddress()).isEqualTo("제주 가동");
    assertThat(full.getCategory()).isEqualTo(CAT_EN_JA);

    PoiSummary addr = l.byId().get(l.ids().addressOnly());
    assertThat(addr.getDisplayName()).isNull();
    assertThat(addr.getDisplayAddress()).isEqualTo("2 Na-ro, Jeju");
    assertThat(addr.getCategoryLabel()).isEqualTo("Test Category Na");

    PoiSummary name = l.byId().get(l.ids().nameOnly());
    assertThat(name.getDisplayName()).isEqualTo("Da Test");
    assertThat(name.getDisplayAddress()).isNull();
    assertThat(name.getCategoryLabel()).isEqualTo(CAT_NONE);

    PoiSummary ja = l.byId().get(l.ids().jaOnly());
    assertThat(ja.getDisplayName()).isEqualTo("マシホム");
    assertThat(ja.getDisplayAddress()).isEqualTo("済州 マ洞");
    assertThat(ja.getName()).isEqualTo("마시험");

    assertThat(l.page().shownLangs()).containsExactlyInAnyOrder(Lang.JA, Lang.EN, Lang.KO);
  }

  @Test
  @DisplayName("zh-Hant — 번역이 없으면 새 칸이 en 으로 떨어지고, en 도 없으면 null")
  void traditionalChineseFallsBackToEnglish() {
    Listed l = list(Lang.ZH_HANT);
    PoiSummary full = l.byId().get(l.ids().full());
    assertThat(full.getDisplayName()).isEqualTo("Gasiheom Restaurant");
    assertThat(full.getDisplayAddress()).isEqualTo("1 Ga-ro, Jeju");
    assertThat(full.getCategoryLabel()).isEqualTo("Test Category Ga");
    assertThat(full.getName()).isEqualTo("가시험식당");
    assertThat(full.getAddress()).isEqualTo("제주 가동");

    PoiSummary ja = l.byId().get(l.ids().jaOnly());
    assertThat(ja.getDisplayName()).isNull();
    assertThat(ja.getDisplayAddress()).isNull();

    assertThat(l.page().shownLangs()).doesNotContain(Lang.ZH_HANT, Lang.JA).contains(Lang.EN);
  }

  @Test
  @DisplayName("ko — 번역을 보지 않는다: displayName·displayAddress 는 null, categoryLabel == category")
  void koreanIgnoresTranslations() {
    Listed l = list(Lang.KO);
    assertThat(l.byId()).hasSize(OPEN);
    for (PoiSummary p : l.byId().values()) {
      assertThat(p.getDisplayName()).as("displayName of %s", p.getId()).isNull();
      assertThat(p.getDisplayAddress()).as("displayAddress of %s", p.getId()).isNull();
      assertThat(p.getCategoryLabel()).isEqualTo(p.getCategory());
    }
    PoiSummary full = l.byId().get(l.ids().full());
    assertThat(full.getName()).isEqualTo("가시험식당");
    assertThat(full.getNameRoman()).isEqualTo("Gasiheom Sikdang");
    assertThat(l.page().shownLangs()).containsExactly(Lang.KO);
  }

  @Test
  @DisplayName("폐업 POI 는 번역이 있어도 목록·개수에 없다 — 어느 언어로든")
  void closedStillHidden() {
    for (Lang lang : Lang.values()) {
      Listed l = list(lang);
      assertThat(l.byId()).doesNotContainKey(l.ids().closed());
      assertThat(l.page().total()).as("total for %s", lang).isEqualTo(OPEN);
      assertThat(l.page().items()).extracting(PoiSummary::getName).doesNotContain("라시험");
      assertThat(l.page().items()).extracting(PoiSummary::getDisplayName).doesNotContain("Ra Test");
    }
  }

  @Test
  @DisplayName("8 인자 Criteria 는 한국어다 — display 칸이 비어 있다")
  void eightArgCriteriaIsKorean() {
    PoiStore.Page page =
        withFixtures(
            ids ->
                store.list(
                    new PoiStore.Criteria(
                        SPOT, null, null, null, null, PoiStore.Sort.ALPHABETICAL, 50, 0)));
    assertThat(page.items()).extracting(PoiSummary::getName).contains("가시험식당", "다시험");
    assertThat(page.items()).extracting(PoiSummary::getDisplayName).containsOnlyNulls();
    assertThat(page.items()).extracting(PoiSummary::getDisplayAddress).containsOnlyNulls();
    assertThat(page.shownLangs()).containsExactly(Lang.KO);
  }

  // ───────────── 상세 ─────────────

  @Test
  @DisplayName("상세 en — 새 칸은 영어, 기존 칸·road·region·city 는 한국어, 사진은 sort_order 순으로 credit 과 함께")
  void detailEnglish() {
    PoiStore.Detail d =
        withFixtures(ids -> store.findDetail(ids.full(), Lang.EN, LAT, LNG).orElseThrow());
    PoiDetail p = d.poi();

    assertThat(d.shownLang()).isEqualTo(Lang.EN);
    assertThat(p.getName()).isEqualTo("가시험식당");
    assertThat(p.getDisplayName()).isEqualTo("Gasiheom Restaurant");
    assertThat(p.getNameRoman()).isEqualTo("Gasiheom Sikdang");
    assertThat(p.getCategory()).isEqualTo(CAT_EN_JA);
    assertThat(p.getCategoryLabel()).isEqualTo("Test Category Ga");
    assertThat(p.getAddress()).isEqualTo("제주 가동");
    assertThat(p.getDisplayAddress()).isEqualTo("1 Ga-ro, Jeju");
    assertThat(p.getRoad()).isEqualTo("시험로");
    assertThat(p.getRegion()).isEqualTo("제주");
    assertThat(p.getCity()).isEqualTo("서귀포시");
    assertThat(p.getDistanceMeters()).isNotNull().isLessThanOrEqualTo(1);

    assertThat(p.getImages())
        .extracting(i -> i.getUrl().toString())
        .containsExactly("https://img.example/first.jpg", "https://img.example/second.jpg");
    assertThat(p.getImages()).extracting(PoiImage::getCredit).containsExactly("한국관광공사", null);
  }

  @Test
  @DisplayName("상세 ja — displayName·categoryLabel 은 ja, displayAddress 는 en, shownLang 은 ja")
  void detailJapanese() {
    PoiStore.Detail d =
        withFixtures(ids -> store.findDetail(ids.full(), Lang.JA, null, null).orElseThrow());
    assertThat(d.shownLang()).isEqualTo(Lang.JA);
    assertThat(d.poi().getName()).isEqualTo("가시험식당");
    assertThat(d.poi().getDisplayName()).isEqualTo("ガシホム食堂");
    assertThat(d.poi().getAddress()).isEqualTo("제주 가동");
    assertThat(d.poi().getDisplayAddress()).isEqualTo("1 Ga-ro, Jeju");
    assertThat(d.poi().getCategoryLabel()).isEqualTo("テスト分類ガ");
    assertThat(d.poi().getDistanceMeters()).isNull();
  }

  @Test
  @DisplayName("상세 ja — 분류 사전에 en 만 있으면 shownLang 은 en, 이름 번역이 없으면 displayName 은 null")
  void detailJapaneseEnglishOnlyCategory() {
    PoiStore.Detail d =
        withFixtures(ids -> store.findDetail(ids.addressOnly(), Lang.JA, null, null).orElseThrow());
    assertThat(d.shownLang()).isEqualTo(Lang.EN);
    assertThat(d.poi().getName()).isEqualTo("나시험");
    assertThat(d.poi().getDisplayName()).isNull();
    assertThat(d.poi().getAddress()).isEqualTo("제주 나동");
    assertThat(d.poi().getDisplayAddress()).isEqualTo("2 Na-ro, Jeju");
  }

  @Test
  @DisplayName("상세 zh-Hant — 새 칸이 en 으로 떨어진다")
  void detailTraditionalChineseFallsBackToEnglish() {
    PoiStore.Detail d =
        withFixtures(ids -> store.findDetail(ids.full(), Lang.ZH_HANT, null, null).orElseThrow());
    assertThat(d.shownLang()).isEqualTo(Lang.EN);
    assertThat(d.poi().getName()).isEqualTo("가시험식당");
    assertThat(d.poi().getDisplayName()).isEqualTo("Gasiheom Restaurant");
    assertThat(d.poi().getDisplayAddress()).isEqualTo("1 Ga-ro, Jeju");
    assertThat(d.poi().getCategoryLabel()).isEqualTo("Test Category Ga");
  }

  @Test
  @DisplayName("상세 en — 사전에 없는 분류면 shownLang 은 ko, displayName 은 그래도 영어")
  void detailEnglishUnknownCategory() {
    PoiStore.Detail d =
        withFixtures(ids -> store.findDetail(ids.nameOnly(), Lang.EN, null, null).orElseThrow());
    assertThat(d.shownLang()).isEqualTo(Lang.KO);
    assertThat(d.poi().getName()).isEqualTo("다시험");
    assertThat(d.poi().getDisplayName()).isEqualTo("Da Test");
    assertThat(d.poi().getCategoryLabel()).isEqualTo(CAT_NONE);
    assertThat(d.poi().getAddress()).isEqualTo("제주 다동");
    assertThat(d.poi().getDisplayAddress()).isNull();
  }

  @Test
  @DisplayName("상세 ko — 번역을 보지 않고(display 칸 null) 사진은 그대로, shownLang 은 ko")
  void detailKorean() {
    PoiStore.Detail d =
        withFixtures(ids -> store.findDetail(ids.full(), Lang.KO, null, null).orElseThrow());
    assertThat(d.shownLang()).isEqualTo(Lang.KO);
    assertThat(d.poi().getName()).isEqualTo("가시험식당");
    assertThat(d.poi().getDisplayName()).isNull();
    assertThat(d.poi().getAddress()).isEqualTo("제주 가동");
    assertThat(d.poi().getDisplayAddress()).isNull();
    assertThat(d.poi().getCategoryLabel()).isEqualTo(CAT_EN_JA);
    assertThat(d.poi().getNameRoman()).isEqualTo("Gasiheom Sikdang");
    assertThat(d.poi().getImages()).hasSize(2);
  }

  @Test
  @DisplayName("상세 — 사진이 없으면 빈 배열(null 아님)")
  void detailWithoutImages() {
    PoiStore.Detail d =
        withFixtures(ids -> store.findDetail(ids.bare(), Lang.EN, null, null).orElseThrow());
    assertThat(d.poi().getImages()).isNotNull().isEmpty();
    assertThat(d.poi().getDisplayName()).isNull();
    assertThat(d.poi().getDisplayAddress()).isNull();
  }

  @Test
  @DisplayName("상세 — 폐업 POI 는 어느 언어로도 없다")
  void detailClosedHidden() {
    for (Lang lang : Lang.values()) {
      Optional<PoiStore.Detail> d =
          withFixtures(ids -> store.findDetail(ids.closed(), lang, null, null));
      assertThat(d).as("closed detail in %s", lang).isEmpty();
    }
  }

  @Test
  @DisplayName("옛 3 인자 findDetail 은 한국어다")
  void legacyFindDetailIsKorean() {
    PoiDetail p = withFixtures(ids -> store.findDetail(ids.full(), null, null).orElseThrow());
    assertThat(p.getName()).isEqualTo("가시험식당");
    assertThat(p.getDisplayName()).isNull();
    assertThat(p.getAddress()).isEqualTo("제주 가동");
    assertThat(p.getDisplayAddress()).isNull();
    assertThat(p.getCategoryLabel()).isEqualTo(CAT_EN_JA);
  }

  // ───────────── 계약: /pois 응답에는 언제나 있다 ─────────────

  @Test
  @DisplayName("계약에서 categoryLabel 은 선택(GuidePlace 가 물려받으므로)이지만, 서버는 목록·상세의 모든 POI 에 어느 언어로든 채운다")
  void categoryLabelAlwaysFilled() {
    for (Lang lang : Lang.values()) {
      Listed l = list(lang);
      assertThat(l.page().items()).as("list items for %s", lang).hasSize(OPEN);
      for (PoiSummary p : l.page().items()) {
        assertThat(p.getCategoryLabel())
            .as("list categoryLabel of %s in %s", p.getId(), lang)
            .isNotBlank();
      }

      Map<Long, PoiDetail> details =
          withFixtures(
              ids -> {
                Map<Long, PoiDetail> m = new HashMap<>();
                for (long id : originals(ids).keySet()) {
                  m.put(id, store.findDetail(id, lang, null, null).orElseThrow().poi());
                }
                return m;
              });
      details.forEach(
          (id, p) ->
              assertThat(p.getCategoryLabel())
                  .as("detail categoryLabel of %s in %s", id, lang)
                  .isNotBlank());
    }
  }
}
