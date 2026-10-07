package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.model.Photo;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoSource;
import com.mz2az.scenetrip.sceneapi.api.model.RatingSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Review;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewAuthor;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewPhoto;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 리뷰 표의 줄을 계약의 모양으로 — 리뷰 창구와 촬영지·편의시설 상세가 함께 쓴다.
 *
 * <p>리뷰 사진의 주소는 여기서 만든다. 저장소 키를 한 시간짜리 서명된 주소로 바꾸는 일이라(계약: 앱은 저장하지 않는다) 표에는 키만 있다.
 */
@Component
class ReviewViews {

  /** 상세에 싣는 사진첩의 앞부분(계약 {@code photos} — 앞 20 장). */
  static final int DETAIL_PHOTOS = 20;

  private final ReviewStore reviews;
  private final PhotoStorage storage;

  ReviewViews(ReviewStore reviews, PhotoStorage storage) {
    this.reviews = reviews;
    this.storage = storage;
  }

  RatingSummary summary(ReviewStore.Target target, long id) {
    ReviewStore.Summary s = reviews.summary(target, id);
    return new RatingSummary(s.count(), s.distribution()).average(s.average());
  }

  /** 사진첩의 한 장(우리 사진 또는 리뷰 사진)과 전체 수. */
  record Gallery(List<Photo> items, int total) {}

  Gallery gallery(ReviewStore.Target target, long id, int limit, int offset) {
    ReviewStore.Page<ReviewStore.Photo> page = reviews.photos(target, id, limit, offset);
    return new Gallery(page.items().stream().map(this::photo).toList(), page.total());
  }

  Review review(ReviewStore.Row r) {
    return new Review(
            r.id(), r.rating(), photos(r.photoKeys()), r.createdAt(), r.updatedAt(), r.mine())
        .body(r.body())
        .author(r.nickname() == null ? null : new ReviewAuthor(r.nickname()));
  }

  List<ReviewPhoto> photos(List<String> keys) {
    return keys.stream().map(k -> new ReviewPhoto(k, storage.viewUrl(k))).toList();
  }

  private Photo photo(ReviewStore.Photo p) {
    if (p.storageKey() != null) {
      return new Photo(storage.viewUrl(p.storageKey()), PhotoSource.REVIEW).reviewId(p.reviewId());
    }
    return new Photo(URI.create(p.url()), PhotoSource.OFFICIAL).credit(p.credit());
  }
}
