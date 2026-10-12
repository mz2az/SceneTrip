package com.mz2az.scenetrip.sceneapi.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.ContentRef;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.MarketSort;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Scene;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.course.TravelEstimator;
import com.mz2az.scenetrip.sceneapi.favorite.FavoriteStore;
import com.mz2az.scenetrip.sceneapi.market.MarketStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStores;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 작품 제작사 페이지 {@code producerUrl} (MZ2AZ-393, 계약 1.12.0) 이 작품의 {@code posterUrl} 이 실리는 모든 응답에 함께
 * 실리는가.
 *
 * <p>명세: {@code content.producer_url} 이 있으면 그 주소 그대로, NULL 이면 null. 경로마다 제작사 주소가 있는 작품 하나와 없는 작품
 * 하나를 끼워 넣고 본다. 적재분은 전부 주소가 있어 「없음」 쪽을 볼 수 없으므로 픽스처를 쓴다. 트랜잭션째 되돌리므로 DB 에 남지 않는다.
 */
@DisplayName("producerUrl — 작품 포스터가 실리는 모든 응답에 제작사 페이지가 함께 실린다")
class ProducerUrlIntegrationTest {

  private static JdbcClient jdbc;
  private static ContentStore contents;
  private static PlaceStore places;
  private static FavoriteStore favorites;
  private static MarketStore market;
  private static CourseStore courses;
  private static UserStore users;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    TravelEstimator travel = DeployedTravel.estimator();
    DwellDefaults dwell = new DwellDefaults();
    dwell.putAll(Map.of());
    contents = ContentStores.create(jdbc);
    places = PlaceStores.create(jdbc);
    favorites = new FavoriteStore(jdbc);
    market = new MarketStore(jdbc, travel, IntegrationDatabase.transactions());
    courses = new CourseStore(jdbc, travel, dwell, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
  }

  @Test
  @DisplayName("작품 목록(ContentSummary) — 있으면 그 주소, 없으면 null")
  void contentList() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          ContentStore.Page page = contents.list(f.marker, null, null, Lang.KO, 10, 0);

          Map<Long, ContentSummary> byId = byId(page.items(), ContentSummary::getId);
          assertThat(byId).containsOnlyKeys(f.withProducer, f.withoutProducer);
          assertThat(byId.get(f.withProducer).getPosterUrl()).isEqualTo(URI.create(f.posterUrl));
          assertThat(byId.get(f.withProducer).getProducerUrl())
              .isEqualTo(URI.create(f.producerUrl));
          assertThat(byId.get(f.withoutProducer).getPosterUrl()).isEqualTo(URI.create(f.posterUrl));
          assertThat(byId.get(f.withoutProducer).getProducerUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("작품 상세(ContentDetail) — 있으면 그 주소, 없으면 null")
  void contentDetail() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          assertThat(
                  contents
                      .findDetail(f.withProducer, Lang.KO)
                      .orElseThrow()
                      .content()
                      .getProducerUrl())
              .isEqualTo(URI.create(f.producerUrl));
          assertThat(
                  contents
                      .findDetail(f.withoutProducer, Lang.KO)
                      .orElseThrow()
                      .content()
                      .getProducerUrl())
              .isNull();
          return null;
        });
  }

  @Test
  @DisplayName("찜한 작품 목록(ContentSummary) — 있으면 그 주소, 없으면 null")
  void favoritesList() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();
          UUID user = users.resolve(UUID.randomUUID());
          favorites.add(user, f.withProducer);
          favorites.add(user, f.withoutProducer);

          FavoriteStore.Page page = favorites.list(user, Lang.KO, 20, 0);

          Map<Long, ContentSummary> byId = byId(page.items(), ContentSummary::getId);
          assertThat(byId).containsOnlyKeys(f.withProducer, f.withoutProducer);
          assertThat(byId.get(f.withProducer).getProducerUrl())
              .isEqualTo(URI.create(f.producerUrl));
          assertThat(byId.get(f.withoutProducer).getProducerUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("장소 목록의 contents(ContentRef) — 있으면 그 주소, 없으면 null")
  void placeListContents() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          PlaceStore.Page page =
              places.list(
                  new PlaceStore.Criteria(
                      f.marker,
                      null,
                      null,
                      null,
                      null,
                      null,
                      PlaceStore.Sort.POPULARITY,
                      Lang.KO,
                      10,
                      0));

          assertThat(page.items()).extracting(PlaceSummary::getId).containsExactly(f.place);
          assertRefs(page.items().get(0).getContents(), f);
          return null;
        });
  }

  @Test
  @DisplayName("작품의 촬영지 목록(contentId 로 거른 장소 목록)의 contents — 있으면 그 주소, 없으면 null")
  void placeListFilteredByContent() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          for (long contentId : new long[] {f.withProducer, f.withoutProducer}) {
            PlaceStore.Page page =
                places.list(
                    new PlaceStore.Criteria(
                        null,
                        contentId,
                        null,
                        null,
                        null,
                        null,
                        PlaceStore.Sort.POPULARITY,
                        Lang.KO,
                        50,
                        0));
            PlaceSummary mine =
                page.items().stream()
                    .filter(p -> p.getId() == f.place)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("픽스처 장소가 목록에 없다"));
            assertThat(mine.getContents()).isNotEmpty();
            for (ContentRef ref : mine.getContents()) {
              if (ref.getContentId() == f.withProducer) {
                assertThat(ref.getProducerUrl()).isEqualTo(URI.create(f.producerUrl));
              } else if (ref.getContentId() == f.withoutProducer) {
                assertThat(ref.getProducerUrl()).isNull();
              }
            }
          }
          return null;
        });
  }

  @Test
  @DisplayName("장소 상세의 contents(ContentRef) — 있으면 그 주소, 없으면 null")
  void placeDetailContents() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          PlaceDetail detail =
              places.findDetail(f.place, Lang.KO, null, null).orElseThrow().place();

          // 상세의 contents 는 main 에서부터 채워지지 않는다(작품은 scenes 쪽으로 실린다) — 포스터도 없으니 producerUrl 위반은
          // 아니다. 비어 있으면 초록으로 숨기지 않고 건너뜀으로 드러낸다. 채워지기 시작하면 이 단언이 producerUrl 을 지킨다.
          Assumptions.assumeFalse(
              detail.getContents().isEmpty(), "장소 상세의 contents 가 비어 있다 — 작품은 scenes 로만 실린다");
          assertRefs(detail.getContents(), f);
          return null;
        });
  }

  @Test
  @DisplayName("장소 상세의 scenes(Scene) — 있으면 그 주소, 없으면 null")
  void placeDetailScenes() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          List<Scene> scenes =
              places.findDetail(f.place, Lang.KO, null, null).orElseThrow().place().getScenes();

          Map<Long, Scene> byId = byId(scenes, Scene::getContentId);
          assertThat(byId).containsOnlyKeys(f.withProducer, f.withoutProducer);
          assertThat(byId.get(f.withProducer).getPosterUrl()).isEqualTo(URI.create(f.posterUrl));
          assertThat(byId.get(f.withProducer).getProducerUrl())
              .isEqualTo(URI.create(f.producerUrl));
          assertThat(byId.get(f.withoutProducer).getProducerUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("마켓 코스 상세의 contents(ContentRef) — 있으면 그 주소, 없으면 null")
  void marketDetailContents() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();
          UUID author = users.resolve(UUID.randomUUID());
          UUID reader = users.resolve(UUID.randomUUID());
          long postId = market.publish(author, courseAt(author, f.place), "설명").orElseThrow();

          MarketCourseDetail post = market.find(reader, postId, Lang.KO).orElseThrow();

          assertRefs(post.getContents(), f);
          return null;
        });
  }

  @Test
  @DisplayName("마켓 코스 목록의 contents(ContentRef) — 있으면 그 주소, 없으면 null")
  void marketListContents() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();
          UUID author = users.resolve(UUID.randomUUID());
          UUID reader = users.resolve(UUID.randomUUID());
          long postId = market.publish(author, courseAt(author, f.place), "설명").orElseThrow();

          MarketStore.Page page =
              // q 는 검색 색인(search_term)을 보는데 픽스처 작품은 색인 갱신 전이라 걸리지 않는다 — 전체 목록에서 고른다.
              market.withContents(market.list(reader, null, MarketSort.SAVES, 10_000, 0), Lang.KO);

          MarketCourseSummary mine =
              page.items().stream()
                  .filter(p -> p.getId() == postId)
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("올린 코스가 목록에 없다"));
          assertRefs(mine.getContents(), f);
          return null;
        });
  }

  // ───────────── 거들기 ─────────────

  /** ContentRef 목록에 두 픽스처 작품이 모두 있고, 주소가 있는 쪽은 그 주소, 없는 쪽은 null 인지. */
  private static void assertRefs(List<ContentRef> refs, Fixture f) {
    Map<Long, ContentRef> byId = byId(refs, ContentRef::getContentId);
    assertThat(byId).containsOnlyKeys(f.withProducer, f.withoutProducer);
    assertThat(byId.get(f.withProducer).getPosterUrl()).isEqualTo(URI.create(f.posterUrl));
    assertThat(byId.get(f.withProducer).getProducerUrl()).isEqualTo(URI.create(f.producerUrl));
    assertThat(byId.get(f.withoutProducer).getPosterUrl()).isEqualTo(URI.create(f.posterUrl));
    assertThat(byId.get(f.withoutProducer).getProducerUrl()).isNull();
  }

  private static <T> Map<Long, T> byId(List<T> items, Function<T, Long> id) {
    return items.stream().collect(Collectors.toMap(id, Function.identity()));
  }

  private static long courseAt(UUID owner, long placeId) {
    long id = courses.create(owner, new CourseCreate(1, CourseOrigin.SELF).title("제작사 코스"));
    courses.replace(
        id,
        new CourseReplace(
            "제작사 코스",
            List.of(
                new CourseDayInput(
                    List.of(new CourseItemInput().placeId(placeId).dwellMinutes(60))))));
    return id;
  }

  /**
   * 장소 하나에 작품 둘 — 제작사 주소가 있는 것(http:// 그대로 — 계약이 http 주소가 있다고 말한다)과 NULL 인 것. 둘 다 포스터가 있다.
   *
   * <p>표식은 매번 새로 만든다. 장소 설명·작품 설명·작품 제목에 달아 {@code q} 로 이 픽스처만 고른다.
   */
  private record Fixture(
      String marker,
      String titleMarker,
      String posterUrl,
      String producerUrl,
      long withProducer,
      long withoutProducer,
      long place) {

    static Fixture insert() {
      String token = UUID.randomUUID().toString().replace("-", "");
      String marker = "zzproducer" + token;
      String titleMarker = "제작사픽스처" + token;
      String posterUrl = "https://example.invalid/poster-" + token + ".jpg";
      String producerUrl = "http://producer.example.invalid/works/" + token;

      long withProducer = content(posterUrl, producerUrl);
      i18n(withProducer, titleMarker + " 있음", marker);
      long withoutProducer = content(posterUrl, null);
      i18n(withoutProducer, titleMarker + " 없음", marker);

      long place =
          jdbc.sql(
                  "INSERT INTO place (geom)"
                      + " VALUES (ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography)"
                      + " RETURNING id")
              .query(Long.class)
              .single();
      jdbc.sql(
              "INSERT INTO place_i18n (place_id, lang, name, description)"
                  + " VALUES (:id, 'ko', '제작사 픽스처 장소', :description)")
          .param("id", place)
          .param("description", marker)
          .update();
      for (long contentId : new long[] {withProducer, withoutProducer}) {
        jdbc.sql("INSERT INTO place_content (place_id, content_id) VALUES (:p, :c)")
            .param("p", place)
            .param("c", contentId)
            .update();
      }

      return new Fixture(
          marker, titleMarker, posterUrl, producerUrl, withProducer, withoutProducer, place);
    }

    private static long content(String posterUrl, String producerUrl) {
      return jdbc.sql(
              "INSERT INTO content (category, poster_url, producer_url)"
                  + " VALUES ('drama', :poster, :producer) RETURNING id")
          .param("poster", posterUrl)
          .param("producer", producerUrl)
          .query(Long.class)
          .single();
    }

    private static void i18n(long contentId, String title, String description) {
      jdbc.sql(
              "INSERT INTO content_i18n (content_id, lang, title, description)"
                  + " VALUES (:id, 'ko', :title, :description)")
          .param("id", contentId)
          .param("title", title)
          .param("description", description)
          .update();
    }
  }
}
