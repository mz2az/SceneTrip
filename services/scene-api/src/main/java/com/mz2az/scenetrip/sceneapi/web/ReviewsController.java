package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.ReviewsApi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MyReview;
import com.mz2az.scenetrip.sceneapi.api.model.MyReviewList;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoList;
import com.mz2az.scenetrip.sceneapi.api.model.Review;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewInput;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewList;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewSort;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewTarget;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewTargetType;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 촬영지·편의시설 리뷰(계약 tag {@code reviews}, 계획 {@code docs/project/plans/review.md}).
 *
 * <p>읽기는 누구나, 쓰기는 가입 사용자만({@code SIGN_IN_REQUIRED}). 촬영지와 편의시설은 같은 모양의 창구가 두 벌이고, 여기서 {@link
 * Target} 으로 하나가 된다.
 */
@RestController
class ReviewsController implements ReviewsApi {

  private static final int DEFAULT_LIMIT = 20;

  private final ReviewStore store;
  private final ReviewViews views;
  private final PhotoStorage storage;
  private final CurrentAccount accounts;

  ReviewsController(
      ReviewStore store, ReviewViews views, PhotoStorage storage, CurrentAccount accounts) {
    this.store = store;
    this.views = views;
    this.storage = storage;
    this.accounts = accounts;
  }

  // ── 목록 ──────────────────────────────────────────────────────────────────

  @Override
  public ResponseEntity<ReviewList> listPlaceReviews(
      Long placeId, ReviewSort sort, Integer limit, Integer offset) {
    return ResponseEntity.ok(list(Target.PLACE, placeId, sort, limit, offset));
  }

  @Override
  public ResponseEntity<ReviewList> listPoiReviews(
      Long poiId, ReviewSort sort, Integer limit, Integer offset) {
    return ResponseEntity.ok(list(Target.POI, poiId, sort, limit, offset));
  }

  private ReviewList list(Target target, long id, ReviewSort sort, Integer limit, Integer offset) {
    requireTarget(target, id);
    UUID viewer = accounts.signedInOrNull();
    ReviewStore.Page<ReviewStore.Row> page =
        store.list(target, id, sort(sort), limit(limit), offset(offset), viewer);
    return new ReviewList(
        page.items().stream().map(views::review).toList(), page.total(), views.summary(target, id));
  }

  // ── 내 리뷰 ────────────────────────────────────────────────────────────────

  @Override
  public ResponseEntity<Review> getMyPlaceReview(Long placeId) {
    return ResponseEntity.ok(mine(Target.PLACE, placeId));
  }

  @Override
  public ResponseEntity<Review> getMyPoiReview(Long poiId) {
    return ResponseEntity.ok(mine(Target.POI, poiId));
  }

  private Review mine(Target target, long id) {
    UUID user = accounts.requireMember();
    requireTarget(target, id);
    return store
        .mine(target, id, user)
        .map(views::review)
        .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "이곳에 쓴 리뷰가 없습니다"));
  }

  @Override
  public ResponseEntity<Review> putMyPlaceReview(Long placeId, ReviewInput body) {
    return ResponseEntity.ok(put(Target.PLACE, placeId, body));
  }

  @Override
  public ResponseEntity<Review> putMyPoiReview(Long poiId, ReviewInput body) {
    return ResponseEntity.ok(put(Target.POI, poiId, body));
  }

  private Review put(Target target, long id, ReviewInput in) {
    UUID user = accounts.requireMember();
    requireTarget(target, id);
    // 앞뒤 공백만인 글은 없는 것으로(계약). 길이·별점 범위·사진 수는 계약의 제약이 이미 막았다.
    String text = in.getBody() == null || in.getBody().isBlank() ? null : in.getBody().strip();
    List<String> keys = in.getPhotoKeys() == null ? List.of() : in.getPhotoKeys();
    try {
      ReviewStore.Row saved =
          store.put(
              target, id, user, in.getRating(), text, keys, storage.unattachedUploads(user, keys));
      return views.review(saved);
    } catch (ReviewStore.PhotoKeyRejectedException e) {
      throw ApiException.badRequest("REVIEW_PHOTO_INVALID", e.getMessage());
    }
  }

  @Override
  public ResponseEntity<Void> deleteMyPlaceReview(Long placeId) {
    return delete(Target.PLACE, placeId);
  }

  @Override
  public ResponseEntity<Void> deleteMyPoiReview(Long poiId) {
    return delete(Target.POI, poiId);
  }

  /** 쓴 적이 없어도 204(계약). 대상이 없으면 404. */
  private ResponseEntity<Void> delete(Target target, long id) {
    UUID user = accounts.requireMember();
    requireTarget(target, id);
    store.delete(target, id, user);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<MyReviewList> listMyReviews(
      Lang acceptLanguage, Integer limit, Integer offset) {
    UUID user = accounts.requireSignedIn();
    String lang = acceptLanguage == null ? "en" : acceptLanguage.getValue();
    ReviewStore.Page<ReviewStore.MineRow> page =
        store.listMine(user, lang, limit(limit), offset(offset));
    return ResponseEntity.ok(
        new MyReviewList(page.items().stream().map(this::myReview).toList(), page.total()));
  }

  private MyReview myReview(ReviewStore.MineRow m) {
    Review r = views.review(m.review());
    return new MyReview(
            r.getId(),
            r.getRating(),
            r.getPhotos(),
            r.getCreatedAt(),
            r.getUpdatedAt(),
            r.getIsMine(),
            new ReviewTarget(
                m.target() == Target.PLACE ? ReviewTargetType.PLACE : ReviewTargetType.POI,
                m.targetId(),
                m.targetName()))
        .body(r.getBody())
        .author(r.getAuthor());
  }

  // ── 사진첩 ────────────────────────────────────────────────────────────────

  @Override
  public ResponseEntity<PhotoList> listPlacePhotos(Long placeId, Integer limit, Integer offset) {
    return ResponseEntity.ok(photos(Target.PLACE, placeId, limit, offset));
  }

  @Override
  public ResponseEntity<PhotoList> listPoiPhotos(Long poiId, Integer limit, Integer offset) {
    return ResponseEntity.ok(photos(Target.POI, poiId, limit, offset));
  }

  private PhotoList photos(Target target, long id, Integer limit, Integer offset) {
    requireTarget(target, id);
    ReviewViews.Gallery g = views.gallery(target, id, limit(limit), offset(offset));
    return new PhotoList(g.items(), g.total());
  }

  // ── 공통 ──────────────────────────────────────────────────────────────────

  private void requireTarget(Target target, long id) {
    if (!store.exists(target, id)) {
      throw target == Target.PLACE
          ? ApiException.notFound("PLACE_NOT_FOUND", "장소 " + id + " 이(가) 없습니다")
          : ApiException.notFound("POI_NOT_FOUND", "편의시설 " + id + " 이(가) 없습니다");
    }
  }

  private static ReviewStore.Sort sort(ReviewSort sort) {
    if (sort == null) {
      return ReviewStore.Sort.RECENT;
    }
    return switch (sort) {
      case RECENT -> ReviewStore.Sort.RECENT;
      case RATING_HIGH -> ReviewStore.Sort.RATING_HIGH;
      case RATING_LOW -> ReviewStore.Sort.RATING_LOW;
    };
  }

  private static int limit(Integer limit) {
    return limit == null ? DEFAULT_LIMIT : limit;
  }

  private static int offset(Integer offset) {
    return offset == null ? 0 : offset;
  }
}
