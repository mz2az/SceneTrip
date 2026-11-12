package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.MarketItem;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import com.mz2az.scenetrip.sceneapi.cart.CartStores;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.course.TravelEstimator;
import com.mz2az.scenetrip.sceneapi.market.MarketStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 장바구니·코스·마켓의 작은 사진 칸(계약 1.29.0, MZ2AZ-561) — 계획 {@code photo-thumbnails.md} §2-4·§9-8.
 *
 * <p>{@code CartItem}·{@code CourseItem}·{@code MarketItem} 의 {@code imageThumbnailUrl} 은 대표 사진 줄({@code
 * imageUrl} 로 나가는 첫 사진)의 {@code place_image.thumbnail_url} 이다 — 뒤 사진의 것을 빌려 오지 않는다. {@code
 * CourseSummary}·{@code MarketCourseSummary.thumbnailUrl} 은 첫 장소의 대표 사진의 {@code COALESCE(thumbnail_url, url)}.
 *
 * <p>아무도 없는 좌표에 촬영지를 만들어 쓴다. 전부 되돌리는 트랜잭션 하나 안에서 돈다.
 */
@DisplayName("장바구니·코스·마켓 — imageThumbnailUrl 과 코스 카드 thumbnailUrl 의 COALESCE (실제 DB)")
class PlaceThumbnailListsIntegrationTest {

  private static final String A = "https://media.example/place/a.jpg";
  private static final String A_THUMB = "https://media.example/place/thumb-720/a.jpg";
  private static final String A2 = "https://media.example/place/a2.jpg";
  private static final String A2_THUMB = "https://media.example/place/thumb-720/a2.jpg";
  private static final String B = "https://media.example/place/b.jpg";
  private static final String B2_THUMB = "https://media.example/place/thumb-720/b2.jpg";

  private static JdbcClient jdbc;
  private static CartStore carts;
  private static CourseStore courses;
  private static MarketStore markets;
  private static UserStore users;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    TravelEstimator travel = DeployedTravel.estimator();
    DwellDefaults dwell = new DwellDefaults();
    dwell.putAll(Map.of());
    carts = CartStores.create(jdbc);
    courses = new CourseStore(jdbc, travel, dwell, IntegrationDatabase.transactions());
    markets = new MarketStore(jdbc, travel, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
  }

  @Test
  @DisplayName("장바구니 — 대표 사진에 작은 사진이 있으면 그것, 없으면 null(뒤 사진 것을 빌리지 않음). imageUrl 은 원본")
  void cartItems() {
    IntegrationDatabase.rolledBack(
        () -> {
          Places p = places();
          UUID user = users.resolve(UUID.randomUUID());
          carts.add(user, p.a(), null, Lang.KO);
          carts.add(user, p.b(), null, Lang.KO);

          Map<Long, CartItem> byPlace =
              carts.list(user, Lang.KO).items().stream()
                  .collect(Collectors.toMap(CartItem::getPlaceId, Function.identity()));

          assertThat(byPlace.get(p.a()).getImageUrl()).isEqualTo(URI.create(A));
          assertThat(byPlace.get(p.a()).getImageThumbnailUrl()).isEqualTo(URI.create(A_THUMB));
          assertThat(byPlace.get(p.b()).getImageUrl()).isEqualTo(URI.create(B));
          assertThat(byPlace.get(p.b()).getImageThumbnailUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("코스 — 항목의 imageThumbnailUrl 은 대표 사진 줄의 것, 카드 thumbnailUrl 은 첫 장소 사진의 작은 것")
  void courseItemsAndSummary() {
    IntegrationDatabase.rolledBack(
        () -> {
          Places p = places();
          UUID user = users.resolve(UUID.randomUUID());
          long courseId = courseWith(user, p.a(), p.b());

          CourseDetail detail = courses.find(user, courseId, Lang.KO).orElseThrow();
          List<CourseItem> items = detail.getDays().get(0).getItems();
          assertThat(items.get(0).getImageUrl()).isEqualTo(URI.create(A));
          assertThat(items.get(0).getImageThumbnailUrl()).isEqualTo(URI.create(A_THUMB));
          assertThat(items.get(1).getImageUrl()).isEqualTo(URI.create(B));
          assertThat(items.get(1).getImageThumbnailUrl()).isNull();

          assertThat(detail.getThumbnailUrl()).isEqualTo(URI.create(A_THUMB));
          assertThat(summaryOf(user, courseId).getThumbnailUrl()).isEqualTo(URI.create(A_THUMB));
          return null;
        });
  }

  @Test
  @DisplayName("코스 카드 — 첫 장소 사진에 작은 것이 없으면 원본(COALESCE), 뒤 사진의 작은 것을 쓰지 않는다")
  void courseSummaryFallsBackToOriginal() {
    IntegrationDatabase.rolledBack(
        () -> {
          Places p = places();
          UUID user = users.resolve(UUID.randomUUID());
          long courseId = courseWith(user, p.b(), p.a());

          assertThat(summaryOf(user, courseId).getThumbnailUrl()).isEqualTo(URI.create(B));
          return null;
        });
  }

  @Test
  @DisplayName("마켓 — 항목의 imageThumbnailUrl, 카드 thumbnailUrl 은 COALESCE")
  void marketItemsAndSummary() {
    IntegrationDatabase.rolledBack(
        () -> {
          Places p = places();
          UUID author = users.resolve(UUID.randomUUID());
          long postId =
              markets.publish(author, courseWith(author, p.a(), p.b()), "설명").orElseThrow();
          long fallbackPost =
              markets.publish(author, courseWith(author, p.b(), p.a()), "설명").orElseThrow();

          MarketCourseDetail post = markets.find(author, postId, Lang.KO).orElseThrow();
          List<MarketItem> items = post.getDays().get(0).getItems();
          assertThat(items.get(0).getImageUrl()).isEqualTo(URI.create(A));
          assertThat(items.get(0).getImageThumbnailUrl()).isEqualTo(URI.create(A_THUMB));
          assertThat(items.get(1).getImageUrl()).isEqualTo(URI.create(B));
          assertThat(items.get(1).getImageThumbnailUrl()).isNull();
          assertThat(post.getThumbnailUrl()).isEqualTo(URI.create(A_THUMB));

          assertThat(markets.find(author, fallbackPost, Lang.KO).orElseThrow().getThumbnailUrl())
              .isEqualTo(URI.create(B));
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  /** A: 대표 사진에 작은 사진, 둘째 사진에도. B: 대표 사진엔 없고 둘째 사진에만. */
  private record Places(long a, long b) {}

  private static Places places() {
    long a = place(0.0001);
    image(a, A, A_THUMB, 0);
    image(a, A2, A2_THUMB, 1);
    long b = place(0.0002);
    image(b, B, null, 0);
    image(b, "https://media.example/place/b2.jpg", B2_THUMB, 1);
    return new Places(a, b);
  }

  private long courseWith(UUID user, long... placeIds) {
    long id = courses.create(user, new CourseCreate(1, CourseOrigin.SELF).title("작은 사진 코스"));
    List<CourseItemInput> items =
        java.util.Arrays.stream(placeIds)
            .mapToObj(pid -> new CourseItemInput().placeId(pid).dwellMinutes(60))
            .toList();
    courses.replace(id, new CourseReplace("작은 사진 코스", List.of(new CourseDayInput(items))));
    return id;
  }

  private static CourseSummary summaryOf(UUID user, long courseId) {
    return courses.list(user).stream()
        .filter(s -> s.getId() == courseId)
        .findFirst()
        .orElseThrow();
  }

  private static long place(double offset) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) RETURNING id")
            .param("lng", 10.5612 + offset)
            .param("lat", -60.5612)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, CAST('ko' AS lang_code),"
                + " :n)")
        .param("p", id)
        .param("n", "작은 사진 촬영지 " + id)
        .update();
    return id;
  }

  private static void image(long placeId, String url, String thumbnailUrl, int order) {
    jdbc.sql(
            "INSERT INTO place_image (place_id, url, sort_order, thumbnail_url)"
                + " VALUES (:p, :u, :o, :t)")
        .param("p", placeId)
        .param("u", url)
        .param("o", order)
        .param("t", thumbnailUrl)
        .update();
  }
}
