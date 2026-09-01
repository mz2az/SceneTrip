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

  /**
   * 부분 일치 갈래가 살아 있는가.
   *
   * <p>질의가 앞글자 갈래와 부분 일치 갈래로 나뉘어 있어, 뒤쪽을 잃어도 대부분의 검색은 멀쩡히 동작한다. 앞글자로는 어떤 표기에도 걸리지 않는 말을 넣어야 그 갈래만
   * 단독으로 확인된다.
   */
  @Test
  @DisplayName("이름 가운데에만 있는 말로도 걸린다")
  void matchesInsideTheName() {
    String mid = IntegrationDatabase.anyMidOnlyTerm(jdbc);

    SuggestionStore.Result result = store.suggest(mid, Lang.KO, 10);

    assertThat(result.items()).as("'%s' — 앞글자로는 어떤 표기에도 걸리지 않는 말이다", mid).isNotEmpty();
  }

  /**
   * 앞글자 조회가 인덱스를 타는가.
   *
   * <p><b>결과 단언으로는 잡을 수 없는 회귀다.</b> 인덱스를 놓쳐도 제안 목록은 글자 하나 달라지지 않고 느려지기만 한다. 그래서 계획을 직접 본다.
   *
   * <p>깨지는 경우는 여럿이고 전부 조용하다 — 앞글자 조건이 {@code WHERE} 밖으로 밀려나거나, 패턴 앞에 {@code %} 가 붙거나, 정규화를 CTE 로 묶어
   * 상수 접기가 깨지거나.
   *
   * <p>{@code enable_seqscan} 을 끄는 이유: 적재 데이터가 작으면 순차 스캔이 실제로 더 싸서 플래너가 그쪽을 고른다. 그 상태에서는 인덱스를 쓸 수
   * <b>있는지</b>를 확인할 수 없다. 여기서 보려는 것은 "지금 인덱스를 쓰느냐" 가 아니라 "질의가 인덱스를 쓸 수 있는 모양이냐" 다.
   */
  @Test
  @DisplayName("앞글자 조회가 search_term_prefix_idx 를 탄다")
  void prefixBranchCanUseIndex() {
    String plan = explainSuggest("강남", Lang.KO, 10);

    assertThat(plan)
        .as("앞글자 갈래가 인덱스를 못 타는 모양이 됐다. 계획:%n%s", plan)
        .contains("search_term_prefix_idx");
  }

  /** {@code EXPLAIN} 을 순차 스캔이 꺼진 트랜잭션 안에서 돌린다. {@code SET LOCAL} 이라 끝나면 저절로 돌아온다. */
  private static String explainSuggest(String q, Lang lang, int limit) {
    return IntegrationDatabase.transactions()
        .execute(
            status -> {
              jdbc.sql("SET LOCAL enable_seqscan = off").update();
              return String.join(
                  "\n",
                  jdbc.sql("EXPLAIN " + SuggestionStore.SUGGEST_SQL)
                      .param("q", q)
                      .param("lang", lang.getValue())
                      .param("limit", limit)
                      .query(String.class)
                      .list());
            });
  }
}
