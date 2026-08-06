package com.mz2az.scenetrip.sceneapi.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link SuggestionStore} 의 SQL 을 진짜 PostgreSQL 에 태운다.
 *
 * <p>이 질의는 {@code search_term} 머티리얼라이즈드 뷰에 의존한다. 적재 후 갱신을 빠뜨리면 뷰가 비어 자동완성이 아무것도 돌려주지 않는데, 오류가 아니라 빈
 * 목록이라 화면에서는 "그런 검색어가 없나 보다" 로 보인다. 여기서 잡는다.
 */
@DisplayName("SuggestionStore — 실제 DB 질의")
class SuggestionStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static SuggestionStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new SuggestionStore(jdbc);
  }

  @Test
  @DisplayName("search_term 이 갱신되어 있다")
  void searchTermIndexIsPopulated() {
    long terms = jdbc.sql("SELECT count(*) FROM search_term").query(Long.class).single();

    assertThat(terms)
        .as("search_term 이 비어 있으면 자동완성이 조용히 0 건이 된다 — `just db-refresh-search`")
        .isPositive();
  }

  @Test
  @DisplayName("장소 이름의 앞부분으로 제안이 걸린다")
  void matchesByPrefix() {
    String name = IntegrationDatabase.anyPlaceNameWithContent(jdbc);
    String prefix = name.substring(0, Math.min(2, name.length()));

    SuggestionStore.Result result = store.suggest(prefix, Lang.KO, 10);

    assertThat(result.items()).isNotEmpty();
    assertThat(result.items()).allSatisfy(s -> assertThat(s.getName()).isNotBlank());
  }

  @Test
  @DisplayName("걸리는 것이 없으면 빈 목록 — 예외가 아니다")
  void unmatchedQueryIsEmpty() {
    SuggestionStore.Result result = store.suggest("존재하지않는제안어zzz", Lang.KO, 10);

    assertThat(result.items()).isEmpty();
  }

  @Test
  @DisplayName("limit 이 지켜진다")
  void respectsLimit() {
    String name = IntegrationDatabase.anyPlaceNameWithContent(jdbc);

    SuggestionStore.Result result = store.suggest(name.substring(0, 1), Lang.KO, 3);

    assertThat(result.items()).hasSizeLessThanOrEqualTo(3);
  }
}
