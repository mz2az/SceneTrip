package com.mz2az.scenetrip.sceneapi.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.EntityType;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.util.UUID;
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
   * 영어 표기로만 걸리는 작품이 ja 요청에서도 걸리는가 — 폴백 사슬의 en 칸.
   *
   * <p>예전 질의는 요청한 언어·{@code NULL}·{@code ko} 표기만 보았다. 그래서 일본어 화면에서 「Goblin」 을 치면 영어 제목이 있는데도 0
   * 건이었다. 입력은 같은 작품의 ja·ko·{@code NULL} 표기 어디에도 들어 있지 않은 영어 표기라, en 표기를 보지 않으면 이 작품은 절대 걸리지 않는다.
   *
   * <p>표시 이름도 사슬을 따라야 한다 — ja 제목이 없으니 en 제목이고, {@code shownLangs} 에 en 이 있어야 헤더가 en 이 된다.
   *
   * <p>작품은 적재 데이터에서 고르지 않고 <b>직접 만든다.</b> 예전에는 "en 제목은 있고 ja 제목은 없는 작품" 을 적재 데이터에서 찾았는데, 적재 CSV 가
   * 모든 작품에 ja 제목을 갖춘 판으로 바뀌자 후보가 사라져 테스트가 깨졌다 — 동작이 아니라 데이터 모양이 바뀐 것이었다. 그래서 ko·en 제목만 있는 작품을 트랜잭션
   * 안에 넣고 {@code search_term} 을 갱신한 뒤 보고, 끝나면 통째로 되돌린다.
   */
  @Test
  @DisplayName("영어로만 있는 표기가 ja 요청에서도 걸리고, 이름은 en 으로 나온다")
  void englishOnlyTermIsFoundForJapanese() {
    IntegrationDatabase.rolledBack(
        () -> {
          EnglishOnly term = insertEnglishOnlyContent();

          SuggestionStore.Result result = store.suggest(term.termNorm(), Lang.JA, 50);

          assertThat(result.items())
              .as("'%s' — 작품 %d 의 영어 표기다", term.termNorm(), term.contentId())
              .anySatisfy(
                  s -> {
                    assertThat(s.getType()).isEqualTo(EntityType.CONTENT);
                    assertThat(s.getId()).isEqualTo(term.contentId());
                    assertThat(s.getName()).isEqualTo(term.englishTitle());
                  });
          assertThat(result.shownLangs()).contains(Lang.EN);
          return null;
        });
  }

  private record EnglishOnly(String termNorm, long contentId, String englishTitle) {}

  /**
   * ko·en 제목만 있고 ja 제목은 없는 작품을 넣고, 그 영어 표기를 돌려준다. {@link IntegrationDatabase#rolledBack} 안에서만 부른다.
   *
   * <p>영어 제목에 무작위 꼬리를 붙여 실제 데이터의 어떤 표기와도 겹치지 않게 한다. 한국어 제목은 한글이라 정규화해도 영어 표기를 품지 않는다 — 그래서 이 작품은 en
   * 표기로만 걸린다.
   *
   * <p>{@code search_term} 은 머티리얼라이즈드 뷰라 행을 넣을 수 없다. 갱신해야 새 작품이 보인다. {@code CONCURRENTLY} 를 쓰지 않는
   * 이유: 트랜잭션 안에서 갱신하고 그대로 되돌려 공유 DB 의 뷰를 원래대로 두기 위해서다.
   */
  private static EnglishOnly insertEnglishOnlyContent() {
    String englishTitle =
        "Zqxfallback Lantern " + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    long contentId =
        jdbc.sql("INSERT INTO content (category) VALUES ('drama') RETURNING id")
            .query(Long.class)
            .single();
    insertTitle(contentId, "ko", "폴백 픽스처 영어만");
    insertTitle(contentId, "en", englishTitle);
    jdbc.sql("REFRESH MATERIALIZED VIEW search_term").update();

    String termNorm =
        jdbc.sql("SELECT search_normalize(:t)")
            .param("t", englishTitle)
            .query(String.class)
            .single();
    return new EnglishOnly(termNorm, contentId, englishTitle);
  }

  private static void insertTitle(long contentId, String lang, String title) {
    jdbc.sql(
            "INSERT INTO content_i18n (content_id, lang, title)"
                + " VALUES (:id, CAST(:lang AS lang_code), :title)")
        .param("id", contentId)
        .param("lang", lang)
        .param("title", title)
        .update();
  }

  /**
   * DB 가 한글을 글자로 인정하는가.
   *
   * <p>{@code pg_trgm} 은 값을 세 글자짜리 조각으로 쪼개 색인하는데, 무엇이 글자인지를 DB 로케일에 묻는다. {@code lc_ctype} 이 {@code
   * C} 면 "글자란 ASCII 뿐" 이라 한글이 조각으로 쪼개지지 않고, {@code search_term_trgm_idx} 가 한글 부분 일치를 하나도 받지 못한다.
   *
   * <p><b>이 고장은 아무 신호를 내지 않는다.</b> 저장도 조회도 멀쩡하고 결과도 정확하다. 인덱스 이름이 실행 계획에 찍히기까지 해서 잘 도는 것처럼 보이는데,
   * 실제로는 GIN 전체를 훑는다. 그래서 계획이 아니라 <b>조각이 만들어지는지</b>를 본다.
   *
   * <p>설정은 {@code platform/kubernetes/postgres/configmap.yaml} 의 {@code POSTGRES_INITDB_ARGS} 이고,
   * 볼륨을 처음 만들 때만 읽힌다. 고치려면 {@code just db-recreate} 다.
   */
  @Test
  @DisplayName("한글에서 trigram 이 만들어진다 — DB 로케일 확인")
  void koreanProducesTrigrams() {
    String trigrams = jdbc.sql("SELECT show_trgm('도깨비')::TEXT").query(String.class).single();

    assertThat(trigrams)
        .as("한글 trigram 이 비었다. lc_ctype 이 C 이면 이렇게 된다 — `just db-recreate`")
        .isNotEqualTo("{}");
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
