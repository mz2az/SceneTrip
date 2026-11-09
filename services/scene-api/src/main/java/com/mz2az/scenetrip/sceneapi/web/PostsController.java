package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.PostsApi;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostAuthor;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlock;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostBoard;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostComment;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCommentCreate;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCommentList;
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
import com.mz2az.scenetrip.sceneapi.post.PostBody;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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

  /** 댓글 길이 상한(계약 {@code TripPostCommentCreate.body}) — 앞뒤 공백을 뗀 뒤 센다. */
  private static final int COMMENT_MAX = 500;

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
    List<TripPostBlock> blocks;
    try {
      blocks = PostBody.normalize(in.getBodyBlocks(), body, keys.size());
    } catch (PostBody.RejectedException e) {
      throw ApiException.badRequest("POST_BODY_INVALID", e.getMessage());
    }
    // 쓸 수 없는 말(MZ2AZ-438) — 제목·본문과 문단 블록의 글·사진 설명·출처까지.
    ApiException.requireAllowedText("제목", title);
    ApiException.requireAllowedText("본문", body);
    if (blocks != null) {
      for (TripPostBlock block : blocks) {
        ApiException.requireAllowedText("본문", block.getText());
        ApiException.requireAllowedText("사진 설명", block.getCaption());
        ApiException.requireAllowedText("사진 출처", block.getCredit());
      }
    }

    // 옮기기는 DB 트랜잭션 밖이다 — 저장소 호출을 트랜잭션 안에서 기다리지 않는다(리뷰와 같다). 그래서 옮기기 전에 업로드 기록부터
    // 차지한다(MZ2AZ-498) — 같은 요청이 동시에 두 번 와도 하나만 차지하고, 진 쪽은 아무 파일도 옮기지 않아 이긴 쪽 사진을 건드리지 않는다.
    UploadStore.Claim claim = uploads.claim(user, keys);
    Set<String> fresh = claim.keys();
    List<String> finalKeys = new ArrayList<>(keys.size());
    Map<String, String> moved = new LinkedHashMap<>(); // 옮긴 자리 → 원래 임시 키
    long postId;
    try {
      for (String key : keys) {
        if (!fresh.contains(key)) {
          throw ApiException.badRequest("POST_PHOTO_INVALID", "올리지 않았거나 만료된 사진입니다");
        }
        String to = "posts/" + key.substring(key.lastIndexOf('/') + 1);
        try {
          storage.move(key, to);
        } catch (PhotoStorage.MissingPhotoException e) {
          throw ApiException.badRequest("POST_PHOTO_INVALID", "받은 주소로 올린 사진이 없습니다");
        }
        finalKeys.add(to);
        moved.put(to, key);
      }
      try {
        postId =
            blocks == null
                ? store.create(user, title, body, finalKeys, moved.keySet(), in.getCourseId())
                : store.create(
                    user, title, body, finalKeys, moved.keySet(), in.getCourseId(), blocks);
      } catch (PostStore.CourseRejectedException e) {
        throw ApiException.badRequest("POST_COURSE_INVALID", e.getMessage());
      } catch (PostStore.PhotoKeyRejectedException e) {
        throw ApiException.badRequest("POST_PHOTO_INVALID", e.getMessage());
      } catch (PostStore.AuthorGoneException e) {
        // 쓰는 사이 탈퇴가 끝났다 — 토큰의 계정이 없으니 「탈퇴한 계정」 과 같은 401(계약 Unauthorized).
        throw ApiException.unauthorized("ACCESS_TOKEN_INVALID", e.getMessage());
      }
    } catch (RuntimeException e) {
      // 저장하지 못했다 — 내가 옮긴 사진만 되돌리고 차지한 기록을 돌려놓는다(앱이 같은 키로 다시 보낼 수 있게).
      moveBack(moved);
      uploads.release(claim);
      throw e;
    }
    // 자유게시판이면 쓴 뒤 게시판을 옮긴다(1.24.0) — 쓰기는 표의 기본값(여행후기)으로 만든다(PostStore.setBoard).
    if (in.getBoard() != null && !PostStore.REVIEW.equals(in.getBoard().getValue())) {
      store.setBoard(postId, in.getBoard().getValue());
    }
    TripPostDetail created;
    try {
      created = detail(postId, user, acceptLanguage);
    } catch (ApiException e) {
      if (e.getStatus() != HttpStatus.NOT_FOUND) {
        throw e;
      }
      // 저장은 끝났는데 다시 읽기 전에 사라졌다 — 그 사이 탈퇴가 끝나 CASCADE(V39)로 함께 지워진 것이다(MZ2AZ-500). 계정이 없으니 401.
      throw ApiException.unauthorized("ACCESS_TOKEN_INVALID", "계정이 없습니다(탈퇴)");
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
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
      Lang acceptLanguage,
      Integer limit,
      Integer offset,
      TripPostBoard board,
      String q,
      String cursor) {
    UUID viewer = accounts.signedInOrNull();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    int skip = offset == null ? 0 : offset;
    String term = searchTerm(q);
    PostStore.Cursor after = cursor == null ? null : decodeCursor(cursor);
    if (after != null && skip > 0) {
      throw ApiException.badRequest("INVALID_PARAMETER", "cursor 와 offset 은 함께 쓸 수 없습니다");
    }
    // 게시판·검색어·커서를 하나도 주지 않은 옛 요청은 옛 길 그대로(여행후기만) — 다음 쪽 커서만 덧붙인다.
    if (board == null && term == null && after == null) {
      PostStore.Page<PostStore.Summary> page = store.list(viewer, null, size, skip);
      boolean more = skip + page.items().size() < page.total();
      return ResponseEntity.ok(listOf(page.items(), page.total(), more));
    }
    PostStore.Slice slice =
        store.list(
            new PostStore.Query(
                viewer,
                null,
                board == null ? PostStore.REVIEW : board.getValue(),
                term,
                after,
                size,
                skip));
    return ResponseEntity.ok(listOf(slice.items(), slice.total(), slice.hasMore()));
  }

  // ── 좋아요·댓글 (1.24.0, MZ2AZ-538, 계획 community-social.md) ───────────────

  @Override
  public ResponseEntity<Void> likePost(Long postId) {
    // 토큰 없이 누르면 로그인 화면으로 안내한다(계약 SignInRequired) — 「토큰을 지워라」(ACCESS_TOKEN_INVALID)가 아니다.
    UUID user = accounts.requireMember();
    if (!store.like(postId, user)) {
      throw notFound(postId);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> unlikePost(Long postId) {
    // 토큰 없이 누르면 로그인 화면으로 안내한다(계약 SignInRequired) — 「토큰을 지워라」(ACCESS_TOKEN_INVALID)가 아니다.
    UUID user = accounts.requireMember();
    if (!store.unlike(postId, user)) {
      throw notFound(postId);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<TripPostCommentList> listPostComments(
      Long postId, Integer limit, Integer offset) {
    PostStore.Page<PostStore.Comment> page =
        store
            .comments(
                postId,
                accounts.signedInOrNull(),
                limit == null ? DEFAULT_LIMIT : limit,
                offset == null ? 0 : offset)
            .orElseThrow(() -> notFound(postId));
    return ResponseEntity.ok(
        new TripPostCommentList(
            page.items().stream().map(PostsController::comment).toList(), page.total()));
  }

  @Override
  public ResponseEntity<TripPostComment> createPostComment(Long postId, TripPostCommentCreate in) {
    UUID user = accounts.requireMember();
    String body = in.getBody() == null ? "" : in.getBody().strip();
    if (body.isEmpty()) {
      throw ApiException.badRequest("INVALID_PARAMETER", "댓글은 비울 수 없습니다");
    }
    if (body.codePointCount(0, body.length()) > COMMENT_MAX) {
      throw ApiException.badRequest("INVALID_PARAMETER", "댓글은 500자까지입니다");
    }
    ApiException.requireAllowedText("댓글", body);
    PostStore.Comment created;
    try {
      created = store.addComment(postId, user, body).orElseThrow(() -> notFound(postId));
    } catch (PostStore.AuthorGoneException e) {
      throw ApiException.unauthorized("ACCESS_TOKEN_INVALID", e.getMessage());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(comment(created));
  }

  @Override
  public ResponseEntity<Void> deletePostComment(Long postId, Long commentId) {
    UUID user = accounts.requireMember();
    if (!store.deleteComment(postId, commentId, user)) {
      throw ApiException.notFound("COMMENT_NOT_FOUND", "댓글 " + commentId + " 이(가) 없습니다");
    }
    return ResponseEntity.noContent().build();
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
    int skip = offset == null ? 0 : offset;
    PostStore.Page<PostStore.Summary> page =
        store.list(viewer, author, limit == null ? DEFAULT_LIMIT : limit, skip);
    return listOf(page.items(), page.total(), skip + page.items().size() < page.total());
  }

  /** 목록 응답 — 다음이 있으면 마지막 줄의 자리를 커서로 덧붙인다. */
  private TripPostList listOf(List<PostStore.Summary> items, int total, boolean more) {
    TripPostList out = new TripPostList(items.stream().map(this::summary).toList(), total);
    if (more && !items.isEmpty()) {
      PostStore.Summary last = items.get(items.size() - 1);
      out.nextCursor(encodeCursor(new PostStore.Cursor(last.createdAt(), last.id())));
    }
    return out;
  }

  /** 검색어 — 앞뒤 공백을 지우고 2자 이상. 없으면 null. */
  private static String searchTerm(String q) {
    if (q == null) {
      return null;
    }
    String trimmed = q.strip();
    if (trimmed.codePointCount(0, trimmed.length()) < 2) {
      throw ApiException.badRequest("INVALID_PARAMETER", "q 는 앞뒤 공백을 빼고 2자 이상이어야 합니다");
    }
    return trimmed;
  }

  /**
   * 커서 = 「v1:작성 시각(에포크 마이크로초):글 번호」 를 URL 안전 base64 로. 앱은 풀어 쓰지 않는다(계약) — 모양을 바꿔도 앞의 판 표시로 가린다. DB
   * 의 시각이 마이크로초라 그 정밀도로 담아야 같은 시각의 글이 겹치거나 빠지지 않는다.
   */
  static String encodeCursor(PostStore.Cursor c) {
    long micros = ChronoUnit.MICROS.between(Instant.EPOCH, c.createdAt().toInstant());
    String raw = "v1:" + micros + ":" + c.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static PostStore.Cursor decodeCursor(String cursor) {
    try {
      String raw =
          new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
      String[] parts = raw.split(":");
      if (parts.length != 3 || !"v1".equals(parts[0])) {
        throw new IllegalArgumentException(raw);
      }
      Instant at = Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS);
      return new PostStore.Cursor(
          OffsetDateTime.ofInstant(at, ZoneOffset.UTC), Long.parseLong(parts[2]));
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(
          "INVALID_PARAMETER", "cursor 가 올바르지 않습니다 — 앞 응답의 nextCursor 를 그대로 보내세요");
    }
  }

  private static TripPostComment comment(PostStore.Comment c) {
    return new TripPostComment(c.id(), c.body(), c.createdAt(), c.mine())
        .author(c.nickname() == null ? null : new TripPostAuthor(c.nickname()));
  }

  private TripPostSummary summary(PostStore.Summary s) {
    TripPostSummary out =
        new TripPostSummary(s.id(), s.title(), s.excerpt(), s.createdAt(), s.photoCount(), s.mine())
            .author(s.nickname() == null ? null : new TripPostAuthor(s.nickname()))
            .coverPhotoUrl(s.coverKey() == null ? null : storage.viewUrl(s.coverKey()));
    if (s.courseTitle() != null) {
      out.course(new TripPostCourseSummary(s.courseTitle(), s.courseDayCount(), s.placeCount()));
    }
    return out.board(TripPostBoard.fromValue(s.board()))
        .likeCount(s.likeCount())
        .commentCount(s.commentCount())
        .liked(s.liked());
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
            .bodyBlocks(d.bodyBlocks())
            .author(s.nickname() == null ? null : new TripPostAuthor(s.nickname()))
            .board(TripPostBoard.fromValue(s.board()))
            .likeCount(s.likeCount())
            .commentCount(s.commentCount())
            .liked(s.liked());
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
