package com.mz2az.scenetrip.sceneapi.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.EntityType;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.Suggestion;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 자동완성 장소 행의 아랫줄 재료 — {@code placeType}·{@code contentTitle} (계약 1.17.0, MZ2AZ-195).
 *
 * <p>명세만 보고 짰다. 장소 행에만 장소 종류 코드와 「그 장소에 나온 작품 중 작품 인기도({@code content.popularity_score})가 가장 높은 작품」
 * 의 이름이 붙고, 이름은 요청 언어 → en → ko 로 고른다. 숨긴 작품({@code content.hidden_at})은 고르지 않는다. 작품·인물 행은 둘 다 null
 * 이고 {@code subtitle} 은 그대로 주소다.
 *
 * <p>픽스처는 적재 데이터에서 고르지 않고 트랜잭션 안에 만든 뒤 통째로 되돌린다. 이름 앞에 무작위 접두어를 붙여 적재 데이터의 어떤 표기와도 겹치지 않게 하므로, 그
 * 접두어로 걸리는 것은 이 테스트가 넣은 것뿐이다.
 */
@DisplayName("SuggestionStore — 장소 행의 placeType·contentTitle")
class SuggestionPlaceLineIntegrationTest {

  private static JdbcClient jdbc;
  private static SuggestionStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new SuggestionStore(jdbc);
  }

  // ── 고르는 작품 ──────────────────────────────────────────────────────────────

  /**
   * 작품 인기도가 가장 높은 작품을 고른다.
   *
   * <p>낮은 인기도 작품을 먼저 넣어 id 가 작게 하고, 장소×작품 산정 점수({@code place_content.popularity_score})는 거꾸로 낮은 쪽에
   * 높게 준다. 그래서 「먼저 넣은 것」·「id 가 작은 것」·「장소×작품 점수가 높은 것」 중 어느 것을 골라도 틀린다.
   */
  @Test
  @DisplayName("작품 인기도가 가장 높은 작품 — id 순서·장소×작품 점수가 아니라")
  void picksHighestContentPopularity() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 이태원로 1");
          long low = insertContent(10, Map.of("ko", "낮은작품"));
          long mid = insertContent(40, Map.of("ko", "가운데작품"));
          long high = insertContent(90, Map.of("ko", "높은작품"));
          link(place, low, 100);
          link(place, mid, 50);
          link(place, high, 1);
          refresh();

          Suggestion row = placeRow(prefix, Lang.KO, place);

          assertThat(row.getContentTitle()).isEqualTo("높은작품");
          return null;
        });
  }

  /**
   * 작품 인기도가 같으면 contentId 가 작은 작품을 고른다.
   *
   * <p>id 가 큰 작품을 먼저 넣고(입력 순서·place_content 순서가 큰 쪽을 가리킨다), 요청 언어(ja) 제목도 id 가 큰 작품에만 준다. 장소×작품 점수도
   * 큰 쪽에 높게 준다. 그래서 「id 가 작은 것」 규칙만이 기대값을 낸다. 언어는 고른 작품의 제목 표기만 바꾼다 — ja 요청에서도 작은 id 작품의 en 제목이
   * 나온다.
   */
  @Test
  @DisplayName("작품 인기도가 같으면 contentId 가 작은 작품 — 입력 순서·언어와 무관")
  void tieBreaksBySmallerContentId() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 1");
          long base =
              jdbc.sql("SELECT COALESCE(max(id), 0) + 1000 FROM content")
                  .query(Long.class)
                  .single();
          long larger = base + 1;
          long smaller = base;
          insertContentWithId(larger, 70, Map.of("ko", "큰번호작품", "en", "Larger Id", "ja", "大きい番号"));
          link(place, larger, 100);
          insertContentWithId(smaller, 70, Map.of("ko", "작은번호작품", "en", "Smaller Id"));
          link(place, smaller, 1);
          refresh();

          assertThat(smaller).isLessThan(larger);
          assertThat(placeRow(prefix, Lang.JA, place).getContentTitle())
              .as("ja 제목은 큰 id 작품에만 있다 — 그래도 작은 id 작품을 고르고 en 으로 폴백한다")
              .isEqualTo("Smaller Id");
          assertThat(placeRow(prefix, Lang.KO, place).getContentTitle()).isEqualTo("작은번호작품");
          assertThat(placeRow(prefix, Lang.EN, place).getContentTitle()).isEqualTo("Smaller Id");
          return null;
        });
  }

  @Test
  @DisplayName("placeType 은 place.type 코드 그대로 — 표시말이 아니다")
  void placeTypeIsTheCode() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 이태원로 1");
          link(place, insertContent(50, Map.of("ko", "작품하나")), null);
          refresh();

          assertThat(placeRow(prefix, Lang.KO, place).getPlaceType()).isEqualTo("hotel");
          assertThat(placeRow(prefix, Lang.EN, place).getPlaceType())
              .as("언어가 바뀌어도 코드는 같다")
              .isEqualTo("hotel");
          return null;
        });
  }

  /** {@code PlaceSummary.type} 과 같은 값이라는 계약 — 장소 목록 질의가 내는 값과 견준다. */
  @Test
  @DisplayName("placeType 은 PlaceSummary.type 과 같은 값이다 — 적재 데이터")
  void placeTypeMatchesPlaceTableForSeededPlaces() {
    String name = IntegrationDatabase.anyPlaceNameWithContent(jdbc);

    List<Suggestion> places =
        store.suggest(name, Lang.KO, 20).items().stream()
            .filter(s -> s.getType() == EntityType.PLACE)
            .toList();

    assertThat(places).as("'%s' 로 장소 행이 하나는 나와야 한다", name).isNotEmpty();
    for (Suggestion s : places) {
      String type =
          jdbc.sql("SELECT type FROM place WHERE id = :id")
              .param("id", s.getId())
              .query(String.class)
              .optional()
              .orElse(null);
      assertThat(s.getPlaceType()).as("장소 %d", s.getId()).isEqualTo(type);
    }
  }

  @Test
  @DisplayName("place.type 이 비어 있으면 placeType 은 null")
  void nullTypeStaysNull() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 무명", null, "서울특별시 종로구 1");
          link(place, insertContent(50, Map.of("ko", "작품하나")), null);
          refresh();

          Suggestion row = placeRow(prefix, Lang.KO, place);

          assertThat(row.getPlaceType()).isNull();
          assertThat(row.getContentTitle()).isEqualTo("작품하나");
          return null;
        });
  }

  @Test
  @DisplayName("숨긴 작품은 인기도가 가장 높아도 고르지 않는다")
  void skipsHiddenContent() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 거리", "street", "서울특별시 중구 1");
          long hidden = insertContent(99, Map.of("ko", "숨긴작품", "en", "Hidden Work"));
          long visible = insertContent(20, Map.of("ko", "보이는작품", "en", "Visible Work"));
          hide(hidden);
          link(place, hidden, 100);
          link(place, visible, 1);
          refresh();

          assertThat(placeRow(prefix, Lang.KO, place).getContentTitle()).isEqualTo("보이는작품");
          assertThat(placeRow(prefix, Lang.EN, place).getContentTitle())
              .as("다른 언어에서도 숨긴 작품으로 새지 않는다")
              .isEqualTo("Visible Work");
          return null;
        });
  }

  @Test
  @DisplayName("이어진 작품이 전부 숨겨졌으면 contentTitle 은 null — 장소 행은 그대로 나온다")
  void allHiddenMeansNoTitle() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 카페", "cafe", "부산광역시 해운대구 1");
          long hidden = insertContent(99, Map.of("ko", "숨긴작품"));
          hide(hidden);
          link(place, hidden, null);
          refresh();

          assertThat(placeRows(prefix, Lang.KO, place))
              .singleElement()
              .satisfies(s -> assertThat(s.getContentTitle()).isNull());
          return null;
        });
  }

  @Test
  @DisplayName("이어진 작품이 없으면 contentTitle 은 null — 장소 행은 그대로 나온다")
  void noContentMeansNoTitle() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 공원", "park", "서울특별시 마포구 1");
          refresh();

          assertThat(placeRows(prefix, Lang.KO, place))
              .singleElement()
              .satisfies(
                  s -> {
                    assertThat(s.getContentTitle()).isNull();
                    assertThat(s.getPlaceType()).isEqualTo("park");
                  });
          return null;
        });
  }

  /** 장소마다 제 작품을 고르는가 — 전체 최고 작품 하나를 모든 장소에 붙이면 여기서 깨진다. */
  @Test
  @DisplayName("장소마다 자기 작품을 고른다")
  void eachPlaceGetsItsOwnContent() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long a = insertPlace(prefix + " 가호텔", "hotel", "서울특별시 강남구 1");
          long b = insertPlace(prefix + " 나식당", "restaurant", "서울특별시 서초구 1");
          long top = insertContent(95, Map.of("ko", "가작품"));
          long other = insertContent(30, Map.of("ko", "나작품"));
          link(a, top, null);
          link(b, other, null);
          refresh();

          assertThat(placeRow(prefix, Lang.KO, a).getContentTitle()).isEqualTo("가작품");
          assertThat(placeRow(prefix, Lang.KO, b).getContentTitle()).isEqualTo("나작품");
          assertThat(placeRow(prefix, Lang.KO, b).getPlaceType()).isEqualTo("restaurant");
          return null;
        });
  }

  // ── 언어 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("요청 언어의 제목이 있으면 그것 (ja)")
  void titleInRequestedLanguage() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 1");
          link(
              place,
              insertContent(80, Map.of("ko", "눈물의 여왕", "en", "Queen of Tears", "ja", "涙の女王")),
              null);
          refresh();

          assertThat(placeRow(prefix, Lang.JA, place).getContentTitle()).isEqualTo("涙の女王");
          assertThat(placeRow(prefix, Lang.EN, place).getContentTitle())
              .isEqualTo("Queen of Tears");
          assertThat(placeRow(prefix, Lang.KO, place).getContentTitle()).isEqualTo("눈물의 여왕");
          return null;
        });
  }

  @Test
  @DisplayName("요청 언어 제목이 없으면 en — ko 보다 먼저")
  void fallsBackToEnglishBeforeKorean() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 1");
          link(place, insertContent(80, Map.of("ko", "눈물의 여왕", "en", "Queen of Tears")), null);
          refresh();

          assertThat(placeRow(prefix, Lang.JA, place).getContentTitle())
              .isEqualTo("Queen of Tears");
          assertThat(placeRow(prefix, Lang.ZH_HANT, place).getContentTitle())
              .isEqualTo("Queen of Tears");
          return null;
        });
  }

  @Test
  @DisplayName("요청 언어·en 이 모두 없으면 ko")
  void fallsBackToKoreanLast() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 1");
          link(place, insertContent(80, Map.of("ko", "한국어만작품")), null);
          refresh();

          assertThat(placeRow(prefix, Lang.EN, place).getContentTitle()).isEqualTo("한국어만작품");
          assertThat(placeRow(prefix, Lang.JA, place).getContentTitle()).isEqualTo("한국어만작품");
          return null;
        });
  }

  /** 언어 폴백은 「고른 작품」 의 제목에만 적용된다. 요청 언어 제목이 있는 작품이라고 해서 인기도가 낮은데 뽑히면 안 된다. */
  @Test
  @DisplayName("요청 언어 제목이 없는 작품이 가장 인기여도 그 작품을 고르고 폴백한다")
  void languageDoesNotChangeWhichContent() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 1");
          long top = insertContent(90, Map.of("ko", "인기작", "en", "Popular Work"));
          long lowWithJa = insertContent(10, Map.of("ko", "비인기작", "ja", "不人気作"));
          link(place, top, null);
          link(place, lowWithJa, null);
          refresh();

          assertThat(placeRow(prefix, Lang.JA, place).getContentTitle()).isEqualTo("Popular Work");
          return null;
        });
  }

  // ── 행 모양 ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("작품·인물 행은 placeType·contentTitle 이 null")
  void contentAndPersonRowsHaveNoPlaceFields() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 1");
          long content = insertContent(90, Map.of("ko", prefix + " 드라마"));
          link(place, content, null);
          long person = insertPerson(prefix + " 배우");
          jdbc.sql(
                  "INSERT INTO content_cast (content_id, person_id, role_type)"
                      + " VALUES (:c, :p, 'actor')")
              .param("c", content)
              .param("p", person)
              .update();
          refresh();

          List<Suggestion> items = store.suggest(normalize(prefix), Lang.KO, 20).items();

          assertThat(items)
              .extracting(Suggestion::getType)
              .contains(EntityType.PLACE, EntityType.CONTENT, EntityType.PERSON);
          assertThat(items)
              .filteredOn(s -> s.getType() != EntityType.PLACE)
              .allSatisfy(
                  s -> {
                    assertThat(s.getPlaceType()).as("%s %d", s.getType(), s.getId()).isNull();
                    assertThat(s.getContentTitle()).as("%s %d", s.getType(), s.getId()).isNull();
                  });
          assertThat(items)
              .filteredOn(s -> s.getType() == EntityType.PLACE)
              .singleElement()
              .satisfies(s -> assertThat(s.getContentTitle()).isEqualTo(prefix + " 드라마"));
          return null;
        });
  }

  /** 적재 데이터 전반 — 장소가 아닌 행이 장소 칸을 들고 나오지 않는다. */
  @Test
  @DisplayName("적재 데이터: 작품·인물 행은 장소 칸이 null")
  void seededNonPlaceRowsHaveNoPlaceFields() {
    for (String q :
        List.of(
            IntegrationDatabase.anyContentTitleWithPlace(jdbc),
            IntegrationDatabase.anyPersonNameWithPlace(jdbc))) {
      List<Suggestion> items = store.suggest(q, Lang.KO, 20).items();
      assertThat(items).as("'%s'", q).isNotEmpty();
      assertThat(items)
          .filteredOn(s -> s.getType() != EntityType.PLACE)
          .isNotEmpty()
          .allSatisfy(
              s -> {
                assertThat(s.getPlaceType()).isNull();
                assertThat(s.getContentTitle()).isNull();
              });
    }
  }

  @Test
  @DisplayName("subtitle 은 그대로 주소 쪽이다 — 작품 이름으로 바뀌지 않는다")
  void subtitleStaysAddress() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 이태원로 1");
          link(place, insertContent(90, Map.of("ko", "눈물의 여왕")), null);
          refresh();

          Suggestion row = placeRow(prefix, Lang.KO, place);

          assertThat(row.getSubtitle()).isNotNull().contains("용산구").doesNotContain("눈물의 여왕");
          return null;
        });
  }

  /**
   * 작품이 여럿인 장소가 작품 수만큼 불어나지 않는가. 작품을 고르려고 {@code place_content} 를 잇다가 장소 행이 늘면, 같은 장소가 여러 번 나오고
   * limit 자리를 잡아먹는다.
   */
  @Test
  @DisplayName("작품이 여럿이어도 장소 행은 하나, limit 이 지켜진다")
  void manyContentsDoNotDuplicatePlaceRow() {
    IntegrationDatabase.rolledBack(
        () -> {
          String prefix = uniquePrefix();
          long place = insertPlace(prefix + " 호텔", "hotel", "서울특별시 용산구 1");
          for (int i = 0; i < 5; i++) {
            link(place, insertContent(10 + i, Map.of("ko", "작품" + i)), null);
          }
          refresh();

          List<Suggestion> items = store.suggest(normalize(prefix), Lang.KO, 20).items();
          Map<String, Long> counts =
              items.stream()
                  .collect(
                      Collectors.groupingBy(
                          s -> s.getType() + ":" + s.getId(), Collectors.counting()));
          assertThat(counts).allSatisfy((k, v) -> assertThat(v).as(k).isEqualTo(1L));
          assertThat(placeRow(prefix, Lang.KO, place).getContentTitle()).isEqualTo("작품4");

          assertThat(store.suggest(normalize(prefix), Lang.KO, 1).items()).hasSize(1);
          return null;
        });
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  /** 적재 데이터 어디에도 없을 앞글자. 한글이 아닌 무작위 꼬리라 정규화해도 그대로 남는다. */
  private static String uniquePrefix() {
    return "qzsugg" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private static String normalize(String s) {
    return jdbc.sql("SELECT search_normalize(:t)").param("t", s).query(String.class).single();
  }

  private static Suggestion placeRow(String prefix, Lang lang, long placeId) {
    List<Suggestion> rows = placeRows(prefix, lang, placeId);
    assertThat(rows).as("장소 %d 가 '%s'(%s) 제안에 한 번 나와야 한다", placeId, prefix, lang).hasSize(1);
    return rows.get(0);
  }

  private static List<Suggestion> placeRows(String prefix, Lang lang, long placeId) {
    return store.suggest(normalize(prefix), lang, 20).items().stream()
        .filter(s -> s.getType() == EntityType.PLACE && s.getId() == placeId)
        .toList();
  }

  private static long insertPlace(String koName, String type, String koAddress) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key, popularity_score) VALUES (:type,"
                    + " ST_SetSRID(ST_MakePoint(126.99, 37.53), 4326)::geography, :k, 50)"
                    + " RETURNING id")
            .param("type", type)
            .param("k", "suggest-fixture-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name, address)"
                + " VALUES (:id, 'ko', :name, :address)")
        .param("id", id)
        .param("name", koName)
        .param("address", koAddress)
        .update();
    return id;
  }

  private static long insertContent(int popularity, Map<String, String> titles) {
    long id =
        jdbc.sql(
                "INSERT INTO content (category, popularity_score) VALUES ('drama', :p) RETURNING"
                    + " id")
            .param("p", popularity)
            .query(Long.class)
            .single();
    insertTitles(id, titles);
    return id;
  }

  /** id 를 직접 정해 넣는다. 입력 순서와 id 순서를 거꾸로 만들 때 쓴다 — 시퀀스는 건드리지 않고, 트랜잭션과 함께 되돌린다. */
  private static void insertContentWithId(long id, int popularity, Map<String, String> titles) {
    jdbc.sql("INSERT INTO content (id, category, popularity_score) VALUES (:id, 'drama', :p)")
        .param("id", id)
        .param("p", popularity)
        .update();
    insertTitles(id, titles);
  }

  private static void insertTitles(long id, Map<String, String> titles) {
    titles.forEach(
        (lang, title) ->
            jdbc.sql(
                    "INSERT INTO content_i18n (content_id, lang, title)"
                        + " VALUES (:id, CAST(:lang AS lang_code), :title)")
                .param("id", id)
                .param("lang", lang)
                .param("title", title)
                .update());
  }

  private static long insertPerson(String koName) {
    long id = jdbc.sql("INSERT INTO person DEFAULT VALUES RETURNING id").query(Long.class).single();
    jdbc.sql("INSERT INTO person_i18n (person_id, lang, name) VALUES (:id, 'ko', :name)")
        .param("id", id)
        .param("name", koName)
        .update();
    return id;
  }

  private static void link(long placeId, long contentId, Integer placeContentScore) {
    jdbc.sql(
            "INSERT INTO place_content (place_id, content_id, popularity_score)"
                + " VALUES (:p, :c, :s)")
        .param("p", placeId)
        .param("c", contentId)
        .param("s", placeContentScore)
        .update();
  }

  private static void hide(long contentId) {
    jdbc.sql("UPDATE content SET hidden_at = now() WHERE id = :id").param("id", contentId).update();
  }

  /** {@code search_term} 은 머티리얼라이즈드 뷰다. 트랜잭션 안에서 갱신하고 함께 되돌린다. */
  private static void refresh() {
    jdbc.sql("REFRESH MATERIALIZED VIEW search_term").update();
  }
}
