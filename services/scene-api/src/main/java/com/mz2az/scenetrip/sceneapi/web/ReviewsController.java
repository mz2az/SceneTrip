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
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 촬영지·편의시설 리뷰(계약 tag {@code reviews}, 계획 {@code docs/project/plans/review.md}).
 *
 * <p>읽기는 누구나, 쓰기는 가입 사용자만({@code SIGN_IN_REQUIRED}). 촬영지와 편의시설은 같은 모양의 창구가 두 벌이고, 여기서 {@link
 * Target} 으로 하나가 된다.
 *
 * <p><b>촬영지와 같은 곳인 편의시설은 촬영지의 리뷰를 쓴다</b>(MZ2AZ-371, {@code place-poi-link.md} §5) — {@link
 * #resolve} 가 대상을 그 촬영지로 바꿔 끼운다. 읽기·쓰기·지우기·사진첩이 모두 같은 길을 탄다.
 */
@RestController
class ReviewsController implements ReviewsApi {

  private static final int DEFAULT_LIMIT = 20;

  private final ReviewStore store;
  private final ReviewViews views;
  private final PhotoStorage storage;
  private final UploadStore uploads;
  private final CurrentAccount accounts;

  ReviewsController(
      ReviewStore store,
      ReviewViews views,
      PhotoStorage storage,
      UploadStore uploads,
      CurrentAccount accounts) {
    this.store = store;
    this.views = views;
    this.storage = storage;
    this.uploads = uploads;
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

  private ReviewList list(
      Target asked, long askedId, ReviewSort sort, Integer limit, Integer offset) {
    Ref at = resolve(asked, askedId);
    Target target = at.target();
    long id = at.id();
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

  private Review mine(Target asked, long askedId) {
    UUID user = accounts.requireMember();
    Ref at = resolve(asked, askedId);
    return store
        .mine(at.target(), at.id(), user)
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

  private Review put(Target asked, long askedId, ReviewInput in) {
    UUID user = accounts.requireMember();
    Ref at = resolve(asked, askedId);
    // 앞뒤 공백만인 글은 없는 것으로(계약). 길이·별점 범위·사진 수는 계약의 제약이 이미 막았다.
    String text = in.getBody() == null || in.getBody().isBlank() ? null : in.getBody().strip();
    List<String> keys = in.getPhotoKeys() == null ? List.of() : in.getPhotoKeys();

    // 막 올린 사진(uploads/tmp/)은 붙이기 전에 reviews/ 로 옮긴다 — 임시 자리는 버킷 수명 규칙이 하루 뒤
    // 지운다. 옮기기는 DB 트랜잭션 밖이다(저장소 호출을 트랜잭션 안에서 기다리지 않는다). 저장하지 못하면
    // 옮긴 것을 되돌린다(moveBack).
    if (new HashSet<>(keys).size() != keys.size()) {
      // 옮기기 전에 막는다 — 같은 임시 키가 둘이면 첫째만 옮겨지고 둘째에서 실패해 옮긴 것이 버려진다.
      throw ApiException.badRequest("REVIEW_PHOTO_INVALID", "같은 사진이 두 번 있습니다");
    }
    Set<String> fresh = uploads.unattached(user, keys);
    List<String> finalKeys = new ArrayList<>(keys.size());
    Map<String, String> moved = new LinkedHashMap<>(); // 옮긴 자리 → 원래 임시 키
    for (String key : keys) {
      if (!fresh.contains(key)) {
        finalKeys.add(key); // 이미 이 리뷰에 붙어 있던 키이거나 잘못된 키 — 저장소가 가린다(아래)
        continue;
      }
      String to = "reviews/" + key.substring(key.lastIndexOf('/') + 1);
      try {
        storage.move(key, to);
      } catch (PhotoStorage.MissingPhotoException e) {
        moveBack(moved);
        throw ApiException.badRequest("REVIEW_PHOTO_INVALID", "받은 주소로 올린 사진이 없습니다");
      }
      finalKeys.add(to);
      moved.put(to, key);
    }
    try {
      ReviewStore.Row saved =
          store.put(at.target(), at.id(), user, in.getRating(), text, finalKeys, moved.keySet());
      uploads.consume(fresh);
      return views.review(saved);
    } catch (ReviewStore.PhotoKeyRejectedException e) {
      moveBack(moved);
      throw ApiException.badRequest("REVIEW_PHOTO_INVALID", e.getMessage());
    }
  }

  /**
   * 저장하지 못했으면 옮긴 사진을 임시 자리로 되돌린다 — 앱이 같은 키로 다시 보낼 수 있게(업로드 기록도 남아 있다). {@code reviews/} 는 수명 규칙이 없어
   * 그대로 두면 아무도 가리키지 않는 파일이 영영 남는다. 되돌리기도 실패하면 그 하나는 남는다 — 드물어 감수한다.
   */
  private void moveBack(Map<String, String> moved) {
    moved.forEach(
        (to, from) -> {
          try {
            storage.move(to, from);
          } catch (RuntimeException ignored) {
            // 위 주석
          }
        });
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
  private ResponseEntity<Void> delete(Target asked, long askedId) {
    UUID user = accounts.requireMember();
    Ref at = resolve(asked, askedId);
    store.delete(at.target(), at.id(), user);
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

  private PhotoList photos(Target asked, long askedId, Integer limit, Integer offset) {
    Ref at = resolve(asked, askedId);
    ReviewViews.Gallery g = views.gallery(at.target(), at.id(), limit(limit), offset(offset));
    return new PhotoList(g.items(), g.total());
  }

  // ── 공통 ──────────────────────────────────────────────────────────────────

  /** 리뷰가 실제로 쌓이는 곳. */
  private record Ref(Target target, long id) {}

  /**
   * 물은 대상이 없으면 404(물은 쪽의 코드로). 촬영지와 같은 곳인 편의시설이면 그 촬영지로 바꿔 끼운다 — 계약 1.7.0 「{@code placeId} 가 있으면 그
   * 촬영지의 리뷰로 처리한다」.
   */
  private Ref resolve(Target target, long id) {
    requireTarget(target, id);
    if (target == Target.POI) {
      return store
          .linkedPlace(id)
          .map(place -> new Ref(Target.PLACE, place))
          .orElse(new Ref(target, id));
    }
    return new Ref(target, id);
  }

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
