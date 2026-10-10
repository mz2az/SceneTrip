package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.PostsApi;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostAuthor;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCourse;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCourseDay;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCourseStop;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCreate;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostDetail;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostList;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostStopSource;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostSummary;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 커뮤니티 여행후기(계약 1.10.0 tag {@code posts}, 계획 {@code community-post.md}, MZ2AZ-352).
 *
 * <p>읽기는 누구나, 쓰기·지우기는 가입 사용자만. 사진은 리뷰와 같은 길이다 — 앱이 {@code POST /uploads} 로 받은 주소에 올린 키를 보내면, 붙이기 전에
 * {@code uploads/tmp/} 에서 {@code posts/} 로 옮긴다(임시 자리는 하루 뒤 지워진다). 저장하지 못하면 옮긴 것을 되돌린다.
 */
@RestController
class PostsController implements PostsApi {

  private static final int DEFAULT_LIMIT = 20;

  private final PostStore store;
  private final CourseStore courses;
  private final PhotoStorage storage;
  private final UploadStore uploads;
  private final UserStore users;
  private final CurrentAccount accounts;

  PostsController(
      PostStore store,
      CourseStore courses,
      PhotoStorage storage,
      UploadStore uploads,
      UserStore users,
      CurrentAccount accounts) {
    this.store = store;
    this.courses = courses;
    this.storage = storage;
    this.uploads = uploads;
    this.users = users;
    this.accounts = accounts;
  }

  // ── 쓰기 ──────────────────────────────────────────────────────────────────

  @Override
  public ResponseEntity<TripPostDetail> createPost(TripPostCreate in, Lang acceptLanguage) {
    UUID user = accounts.requireMember();
    String title = in.getTitle() == null ? "" : in.getTitle().strip();
    String body = in.getBody() == null ? "" : in.getBody().strip();
    if (title.isEmpty() || body.isEmpty()) {
      throw ApiException.badRequest("INVALID_PARAMETER", "제목과 본문은 비울 수 없습니다");
    }
    List<String> keys = in.getPhotoKeys() == null ? List.of() : in.getPhotoKeys();
    if (Set.copyOf(keys).size() != keys.size()) {
      throw ApiException.badRequest("POST_PHOTO_INVALID", "같은 사진이 두 번 있습니다");
    }

    // 옮기기는 DB 트랜잭션 밖이다 — 저장소 호출을 트랜잭션 안에서 기다리지 않는다(리뷰와 같다).
    Set<String> fresh = uploads.unattached(user, keys);
    List<String> finalKeys = new ArrayList<>(keys.size());
    Map<String, String> moved = new LinkedHashMap<>(); // 옮긴 자리 → 원래 임시 키
    for (String key : keys) {
      if (!fresh.contains(key)) {
        moveBack(moved);
        throw ApiException.badRequest("POST_PHOTO_INVALID", "올리지 않았거나 만료된 사진입니다");
      }
      String to = "posts/" + key.substring(key.lastIndexOf('/') + 1);
      try {
        storage.move(key, to);
      } catch (PhotoStorage.MissingPhotoException e) {
        moveBack(moved);
        throw ApiException.badRequest("POST_PHOTO_INVALID", "받은 주소로 올린 사진이 없습니다");
      }
      finalKeys.add(to);
      moved.put(to, key);
    }

    long postId;
    try {
      postId = store.create(user, title, body, finalKeys, moved.keySet(), in.getCourseId());
    } catch (PostStore.CourseRejectedException e) {
      moveBack(moved);
      throw ApiException.badRequest("POST_COURSE_INVALID", e.getMessage());
    } catch (PostStore.PhotoKeyRejectedException e) {
      moveBack(moved);
      throw ApiException.badRequest("POST_PHOTO_INVALID", e.getMessage());
    }
    uploads.consume(fresh);
    return ResponseEntity.status(HttpStatus.CREATED).body(detail(postId, user, acceptLanguage));
  }

  /** 저장하지 못했으면 옮긴 사진을 임시 자리로 되돌린다 — 앱이 같은 키로 다시 보낼 수 있게. 되돌리기도 실패하면 그 하나는 남는다. */
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
  public ResponseEntity<Void> deletePost(Long postId) {
    UUID user = accounts.requireMember();
    if (!store.delete(postId, user)) {
      throw notFound(postId);
    }
    return ResponseEntity.noContent().build();
  }

  // ── 읽기 ──────────────────────────────────────────────────────────────────

  @Override
  public ResponseEntity<TripPostList> listPosts(
      Lang acceptLanguage, Integer limit, Integer offset) {
    return ResponseEntity.ok(list(accounts.signedInOrNull(), null, limit, offset));
  }

  @Override
  public ResponseEntity<TripPostList> listMyPosts(
      Lang acceptLanguage, Integer limit, Integer offset) {
    UUID user = accounts.requireSignedIn();
    return ResponseEntity.ok(list(user, user, limit, offset));
  }

  @Override
  public ResponseEntity<TripPostDetail> getPost(Long postId, Lang acceptLanguage) {
    return ResponseEntity.ok(detail(postId, accounts.signedInOrNull(), acceptLanguage));
  }

  // ── 담기 ──────────────────────────────────────────────────────────────────

  @Override
  public ResponseEntity<CourseDetail> savePostCourse(
      UUID xInstallId, Long postId, Lang acceptLanguage) {
    UUID user = accounts.resolve(xInstallId);
    if (!users.isRegistered(user)) {
      throw ApiException.signInRequired("SIGN_IN_REQUIRED", "이 동작은 가입한 사용자만 할 수 있습니다");
    }
    long courseId = store.save(user, postId).orElseThrow(() -> notFound(postId));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            courses
                .find(user, courseId, lang(acceptLanguage))
                .orElseThrow(() -> new IllegalStateException("방금 만든 코스를 찾지 못했습니다")));
  }

  // ── 안쪽 ──────────────────────────────────────────────────────────────────

  private TripPostList list(UUID viewer, UUID author, Integer limit, Integer offset) {
    PostStore.Page<PostStore.Summary> page =
        store.list(
            viewer, author, limit == null ? DEFAULT_LIMIT : limit, offset == null ? 0 : offset);
    return new TripPostList(page.items().stream().map(this::summary).toList(), page.total());
  }

  private TripPostSummary summary(PostStore.Summary s) {
    TripPostSummary out =
        new TripPostSummary(s.id(), s.title(), s.excerpt(), s.createdAt(), s.photoCount(), s.mine())
            .author(s.nickname() == null ? null : new TripPostAuthor(s.nickname()))
            .coverPhotoUrl(s.coverKey() == null ? null : storage.viewUrl(s.coverKey()));
    if (s.courseTitle() != null) {
      out.course(new TripPostCourseSummary(s.courseTitle(), s.courseDayCount(), s.placeCount()));
    }
    return out;
  }

  private TripPostDetail detail(long postId, UUID viewer, Lang acceptLanguage) {
    PostStore.Detail d = store.find(postId, viewer).orElseThrow(() -> notFound(postId));
    PostStore.Summary s = d.head();
    TripPostDetail out =
        new TripPostDetail(
                s.id(),
                s.title(),
                d.body(),
                s.createdAt(),
                d.photoKeys().stream().map(storage::viewUrl).toList(),
                s.mine())
            .author(s.nickname() == null ? null : new TripPostAuthor(s.nickname()));
    if (s.courseTitle() != null) {
      out.course(course(postId, s.courseTitle(), s.courseDayCount(), lang(acceptLanguage)));
    }
    return out;
  }

  /** 사본의 장소를 일차로 묶는다. 직접 찍은 핀만 있던 일차는 비어 있다(계약). */
  private TripPostCourse course(long postId, String title, int dayCount, Lang lang) {
    List<TripPostCourseDay> days = new ArrayList<>(dayCount);
    for (int day = 1; day <= dayCount; day++) {
      days.add(new TripPostCourseDay(day, new ArrayList<>()));
    }
    for (PostStore.Stop stop : store.stops(postId, lang.getValue())) {
      // 일수를 넘는 일차는 API 로 만들어지지 않는다(코스 저장이 막는다). 그래도 있으면 마지막 일차에 붙여 감추지 않는다.
      int slot = Math.min(Math.max(stop.dayNo(), 1), dayCount) - 1;
      days.get(slot).getStops().add(stop(stop));
    }
    return new TripPostCourse(title, days);
  }

  private static TripPostCourseStop stop(PostStore.Stop s) {
    boolean poi = s.placeId() == null;
    return new TripPostCourseStop(
            poi ? TripPostStopSource.POI : TripPostStopSource.PLACE,
            s.name(),
            s.latitude(),
            s.longitude(),
            s.dwellMinutes())
        .placeId(s.placeId())
        .poiId(s.poiId())
        .address(s.address())
        .category(s.category())
        .imageUrl(s.imageUrl() == null ? null : URI.create(s.imageUrl()))
        .displayName(s.displayName())
        .nameRoman(s.nameRoman())
        .displayAddress(s.displayAddress())
        .categoryLabel(s.categoryLabel());
  }

  private static Lang lang(Lang acceptLanguage) {
    return acceptLanguage == null ? Lang.EN : acceptLanguage;
  }

  private static ApiException notFound(long postId) {
    return ApiException.notFound("POST_NOT_FOUND", "여행후기 " + postId + " 이(가) 없습니다");
  }
}
