package com.mz2az.scenetrip.sceneapi.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.util.List;
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

  private static List<Long> ids(ContentStore.Page page) {
    return page.items().stream().map(ContentSummary::getId).toList();
  }
}
