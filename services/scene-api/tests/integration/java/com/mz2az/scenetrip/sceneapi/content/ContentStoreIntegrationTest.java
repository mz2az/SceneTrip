package com.mz2az.scenetrip.sceneapi.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link ContentStore} 의 SQL 을 진짜 PostgreSQL 에 태운다. */
@DisplayName("ContentStore — 실제 DB 질의")
class ContentStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static ContentStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new ContentStore(jdbc);
  }

  @Test
  @DisplayName("조건 없는 목록 — q 가 NULL 인 타입 추론 자리")
  void listsByPopularity() {
    ContentStore.Page page = store.list(null, null, null, Lang.KO, 10, 0);

    assertThat(page.items()).isNotEmpty();
    assertThat(page.total()).isGreaterThanOrEqualTo(page.items().size());
  }

  @Test
  @DisplayName("분류 필터")
  void filtersByCategory() {
    ContentStore.Page page = store.list(null, null, ContentCategory.DRAMA, Lang.KO, 20, 0);

    assertThat(page.items())
        .allSatisfy(c -> assertThat(c.getCategory()).isEqualTo(ContentCategory.DRAMA));
  }

  @Test
  @DisplayName("검색어와 분류를 함께 주면 교집합")
  void combinesQueryAndCategory() {
    String q = IntegrationDatabase.anyContentTitleWithPlace(jdbc);

    ContentStore.Page all = store.list(q, null, null, Lang.KO, 50, 0);
    ContentStore.Page drama = store.list(q, null, ContentCategory.DRAMA, Lang.KO, 50, 0);

    assertThat(drama.total()).isLessThanOrEqualTo(all.total());
  }

  @Test
  @DisplayName("인물 지목(personId) 질의")
  void filtersByPersonId() {
    List<Long> personIds =
        jdbc.sql("SELECT person_id FROM content_cast ORDER BY person_id LIMIT 1")
            .query(Long.class)
            .list();
    if (personIds.isEmpty()) {
      throw new IllegalStateException("content_cast 가 비어 있습니다 — `just seed` 를 먼저 실행하세요");
    }

    ContentStore.Page page = store.list(null, personIds.get(0), null, Lang.KO, 20, 0);

    assertThat(page.items()).isNotEmpty();
  }

  @Test
  @DisplayName("상세 조회 — 줄거리·별칭·출연진")
  void findsDetail() {
    long id = IntegrationDatabase.anyContentId(jdbc);

    ContentStore.Detail detail = store.findDetail(id, Lang.KO).orElseThrow();

    assertThat(detail.content().getId()).isEqualTo(id);
    assertThat(detail.content().getTitle()).isNotBlank();
  }

  @Test
  @DisplayName("없는 id 는 빈 값")
  void missingDetailIsEmpty() {
    assertThat(store.findDetail(-1L, Lang.KO)).isEmpty();
  }

  @Test
  @DisplayName("offset 페이지네이션이 겹치지 않는다")
  void pagesDoNotOverlap() {
    List<Long> first = ids(store.list(null, null, null, Lang.KO, 2, 0));
    List<Long> second = ids(store.list(null, null, null, Lang.KO, 2, 2));

    assertThat(first).doesNotContainAnyElementsOf(second);
  }

  /**
   * 응답 언어의 폴백 사슬 — 요청한 언어, 그다음 en, 그다음 ko.
   *
   * <p>적재 데이터에 기대지 않고 픽스처 작품 셋을 끼워 넣는다. 「en 은 있고 요청 언어는 없는 행」 과 「ko 만 있는 행」 이 한 목록에 함께 있어야 행 단위 폴백과
   * {@code shownLangs} 가 둘 다 보이는데, 적재분이 그 모양을 계속 유지한다는 보장이 없다. 트랜잭션째 되돌리므로 DB 에는 남지 않는다.
   *
   * <p>세 작품은 설명에 같은 표식을 달아 {@code q} 로 그 셋만 골라낸다 — 작품 설명 분기는 언어를 가리지 않는다.
   */
  @Test
  @DisplayName("목록 — ja 요청에 en 이 있으면 en, ko 만 있으면 ko, ja 가 있으면 ja")
  void listFallsBackThroughEnglishThenKorean() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          ContentStore.Page ja = store.list(f.marker, null, null, Lang.JA, 10, 0);
          assertThat(titles(ja))
              .containsEntry(f.withEnglish, "Fallback Fixture EN")
              .containsEntry(f.koreanOnly, "폴백 픽스처 한국어만")
              .containsEntry(f.withJapanese, "フォールバック")
              .hasSize(3);
          assertThat(ja.shownLangs()).containsExactlyInAnyOrder(Lang.JA, Lang.EN, Lang.KO);

          ContentStore.Page ko = store.list(f.marker, null, null, Lang.KO, 10, 0);
          assertThat(titles(ko))
              .containsEntry(f.withEnglish, "폴백 픽스처 영어도")
              .containsEntry(f.koreanOnly, "폴백 픽스처 한국어만")
              .containsEntry(f.withJapanese, "폴백 픽스처 일본어도");
          assertThat(ko.shownLangs()).containsExactly(Lang.KO);

          ContentStore.Page en = store.list(f.marker, null, null, Lang.EN, 10, 0);
          assertThat(titles(en))
              .containsEntry(f.withEnglish, "Fallback Fixture EN")
              .containsEntry(f.koreanOnly, "폴백 픽스처 한국어만")
              .containsEntry(f.withJapanese, "Fallback Fixture JA-EN");
          assertThat(en.shownLangs()).containsExactlyInAnyOrder(Lang.EN, Lang.KO);
          return null;
        });
  }

  @Test
  @DisplayName("목록 — en 도 요청 언어도 없이 ko 로만 채워지면 shownLangs 는 ko 하나")
  void listAllKoreanReportsKoreanOnly() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          // 한국어만 있는 작품 하나로 좁힌다. 헤더가 ko 가 되어야 하는 유일한 경우다.
          ContentStore.Page page = store.list(f.koreanOnlyMarker, null, null, Lang.JA, 10, 0);

          assertThat(titles(page)).containsOnlyKeys(f.koreanOnly);
          assertThat(page.shownLangs()).containsExactly(Lang.KO);
          return null;
        });
  }

  @Test
  @DisplayName("상세 — shownLang 이 실제로 고른 행의 언어다")
  void detailReportsShownLang() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          ContentStore.Detail english = store.findDetail(f.withEnglish, Lang.JA).orElseThrow();
          assertThat(english.content().getTitle()).isEqualTo("Fallback Fixture EN");
          assertThat(english.shownLang()).isEqualTo(Lang.EN);

          ContentStore.Detail korean = store.findDetail(f.koreanOnly, Lang.JA).orElseThrow();
          assertThat(korean.content().getTitle()).isEqualTo("폴백 픽스처 한국어만");
          assertThat(korean.shownLang()).isEqualTo(Lang.KO);

          ContentStore.Detail japanese = store.findDetail(f.withJapanese, Lang.JA).orElseThrow();
          assertThat(japanese.content().getTitle()).isEqualTo("フォールバック");
          assertThat(japanese.shownLang()).isEqualTo(Lang.JA);

          ContentStore.Detail asKorean = store.findDetail(f.withEnglish, Lang.KO).orElseThrow();
          assertThat(asKorean.content().getTitle()).isEqualTo("폴백 픽스처 영어도");
          assertThat(asKorean.shownLang()).isEqualTo(Lang.KO);

          ContentStore.Detail asEnglish = store.findDetail(f.withEnglish, Lang.EN).orElseThrow();
          assertThat(asEnglish.content().getTitle()).isEqualTo("Fallback Fixture EN");
          assertThat(asEnglish.shownLang()).isEqualTo(Lang.EN);
          return null;
        });
  }

  /**
   * 폴백 시험용 작품 셋 — ko+en, ko 만, ko+en+ja.
   *
   * <p>표식은 매번 새로 만든다. 적재 데이터의 어떤 설명과도 겹치지 않아야 {@code q} 가 이 셋만 고른다.
   */
  private record Fixture(
      String marker,
      String koreanOnlyMarker,
      long withEnglish,
      long koreanOnly,
      long withJapanese) {

    static Fixture insert() {
      String marker = "zzfallback" + UUID.randomUUID().toString().replace("-", "");
      String koreanOnlyMarker = marker + "kodesc";
      long withEnglish = content();
      i18n(withEnglish, "ko", "폴백 픽스처 영어도", marker);
      i18n(withEnglish, "en", "Fallback Fixture EN", marker);

      long koreanOnly = content();
      i18n(koreanOnly, "ko", "폴백 픽스처 한국어만", koreanOnlyMarker);

      long withJapanese = content();
      i18n(withJapanese, "ko", "폴백 픽스처 일본어도", marker);
      i18n(withJapanese, "en", "Fallback Fixture JA-EN", marker);
      i18n(withJapanese, "ja", "フォールバック", marker);

      return new Fixture(marker, koreanOnlyMarker, withEnglish, koreanOnly, withJapanese);
    }

    private static long content() {
      return jdbc.sql("INSERT INTO content (category) VALUES ('drama') RETURNING id")
          .query(Long.class)
          .single();
    }

    private static void i18n(long contentId, String lang, String title, String description) {
      jdbc.sql(
              "INSERT INTO content_i18n (content_id, lang, title, description)"
                  + " VALUES (:id, :lang, :title, :description)")
          .param("id", contentId)
          .param("lang", lang)
          .param("title", title)
          .param("description", description)
          .update();
    }
  }

  private static Map<Long, String> titles(ContentStore.Page page) {
    return page.items().stream()
        .collect(Collectors.toMap(ContentSummary::getId, ContentSummary::getTitle));
  }

  private static List<Long> ids(ContentStore.Page page) {
    return page.items().stream().map(ContentSummary::getId).toList();
  }
}
