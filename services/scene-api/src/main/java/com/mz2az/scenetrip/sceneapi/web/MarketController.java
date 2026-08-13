package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.MarketApi;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseList;
import com.mz2az.scenetrip.sceneapi.api.model.MarketSort;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.market.MarketStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 코스마켓 — 올리기·담기·내리기·좋아요.
 *
 * <p><b>둘러보기는 누구나, 남기는 것은 가입 사용자만.</b> 목록과 상세는 계정 없이 열리고 올리기·담기·좋아요는 {@code 401} 이다. 비회원이 코스를 올린 뒤
 * 앱을 지우면 그 코스를 아무도 내릴 수 없기 때문이다 — 신고가 들어와도 본인이 처리하지 못한다.
 *
 * <p><b>지금은 그 셋을 아무도 통과하지 못한다.</b> 가입시키는 경로가 없다(로그인 스토리 8/23 주차). 계약에 자리를 잡아 둔 상태이고 프론트도 그 화면을 아직
 * 만들지 않는다. 저장소 쪽 로직은 전부 구현돼 있어 통합 테스트가 실제 DB 로 확인한다 — 로그인이 붙는 순간 더 할 일이 없도록.
 */
@RestController
class MarketController implements MarketApi {

  private final MarketStore store;
  private final CourseStore courses;
  private final UserStore users;

  MarketController(MarketStore store, CourseStore courses, UserStore users) {
    this.store = store;
    this.courses = courses;
    this.users = users;
  }

  @Override
  public ResponseEntity<MarketCourseList> listMarketCourses(
      UUID xDeviceId,
      Lang acceptLanguage,
      String q,
      MarketSort sort,
      Integer limit,
      Integer offset) {

    // 공백만 들어온 q 는 없는 것으로 본다. 그대로 두면 모든 코스에 걸리는 조건이 된다.
    String query = (q == null || q.isBlank()) ? null : q.strip();

    MarketStore.Page page =
        store.withContents(
            store.list(users.resolve(xDeviceId), query, sort, limit, offset), acceptLanguage);

    return ResponseEntity.ok(new MarketCourseList(page.items(), page.total(), limit, offset));
  }

  @Override
  public ResponseEntity<MarketCourseDetail> createMarketCourse(
      UUID xDeviceId, MarketCourseCreate marketCourseCreate, Lang acceptLanguage) {

    UUID user = requireRegistered(xDeviceId);
    long courseId = marketCourseCreate.getCourseId();

    if (!courses.exists(user, courseId)) {
      throw ApiException.notFound("COURSE_NOT_FOUND", "코스 " + courseId + " 이(가) 없습니다");
    }

    long postId =
        store
            .publish(user, courseId, marketCourseCreate.getDescription())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "COURSE_ALREADY_PUBLISHED",
                        "코스 " + courseId + " 은(는) 이미 올라가 있습니다. 내린 뒤에 다시 올리세요"));

    return ResponseEntity.status(HttpStatus.CREATED).body(read(user, postId, acceptLanguage));
  }

  @Override
  public ResponseEntity<MarketCourseDetail> getMarketCourse(
      UUID xDeviceId, Long marketCourseId, Lang acceptLanguage) {

    return ResponseEntity.ok(read(users.resolve(xDeviceId), marketCourseId, acceptLanguage));
  }

  /**
   * 내리기.
   *
   * <p>없는 것과 남의 것을 가른다. 남의 것을 {@code 404} 로 뭉뚱그리지 않는 이유는, 마켓의 코스는 <b>이미 누구에게나 보이는 것</b>이라 존재를 숨길
   * 이유가 없어서다. 내 코스가 {@code COURSE_NOT_FOUND} 로 존재를 숨기는 것과 갈리는 지점이다.
   */
  @Override
  public ResponseEntity<Void> deleteMarketCourse(UUID xDeviceId, Long marketCourseId) {
    UUID user = requireRegistered(xDeviceId);

    if (!store.isLive(marketCourseId)) {
      throw notFound(marketCourseId);
    }
    if (!store.isAuthor(user, marketCourseId)) {
      throw ApiException.forbidden("NOT_MARKET_COURSE_AUTHOR", "남이 올린 코스는 내릴 수 없습니다");
    }

    store.unpublish(user, marketCourseId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<CourseDetail> saveMarketCourse(
      UUID xDeviceId, Long marketCourseId, Lang acceptLanguage) {

    UUID user = requireRegistered(xDeviceId);
    requireLive(marketCourseId);

    long courseId = store.save(user, marketCourseId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            courses
                .find(user, courseId, acceptLanguage)
                .orElseThrow(() -> new IllegalStateException("방금 만든 코스를 찾지 못했습니다")));
  }

  @Override
  public ResponseEntity<Void> likeMarketCourse(UUID xDeviceId, Long marketCourseId) {
    return toggleLike(xDeviceId, marketCourseId, true);
  }

  @Override
  public ResponseEntity<Void> unlikeMarketCourse(UUID xDeviceId, Long marketCourseId) {
    return toggleLike(xDeviceId, marketCourseId, false);
  }

  // ───────────── 안쪽 ─────────────

  private ResponseEntity<Void> toggleLike(UUID xDeviceId, long postId, boolean liked) {
    UUID user = requireRegistered(xDeviceId);
    requireLive(postId);

    store.like(user, postId, liked);
    return ResponseEntity.noContent().build();
  }

  /**
   * 가입 사용자만 통과시킨다.
   *
   * <p><b>지금은 아무도 통과하지 못한다</b> — 가입시키는 경로가 아직 없다. 계약이 약속한 그대로이고, 프론트는 이 동작들의 화면을 아직 만들지 않는다.
   */
  private UUID requireRegistered(UUID xDeviceId) {
    UUID user = users.resolve(xDeviceId);
    if (!users.isRegistered(user)) {
      throw ApiException.signInRequired("SIGN_IN_REQUIRED", "이 동작은 가입한 사용자만 할 수 있습니다");
    }
    return user;
  }

  private void requireLive(long postId) {
    if (!store.isLive(postId)) {
      throw notFound(postId);
    }
  }

  private MarketCourseDetail read(UUID user, long postId, Lang lang) {
    return store.find(user, postId, lang).orElseThrow(() -> notFound(postId));
  }

  private static ApiException notFound(long postId) {
    return ApiException.notFound("MARKET_COURSE_NOT_FOUND", "올라온 코스 " + postId + " 이(가) 없습니다");
  }
}
