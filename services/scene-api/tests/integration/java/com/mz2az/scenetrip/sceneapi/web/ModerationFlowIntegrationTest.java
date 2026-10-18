package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.BlockCreate;
import com.mz2az.scenetrip.sceneapi.api.model.BlockedUser;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.NicknameInput;
import com.mz2az.scenetrip.sceneapi.api.model.Photo;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoList;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceDetail;
import com.mz2az.scenetrip.sceneapi.api.model.ReportCreate;
import com.mz2az.scenetrip.sceneapi.api.model.ReportReason;
import com.mz2az.scenetrip.sceneapi.api.model.ReportTargetType;
import com.mz2az.scenetrip.sceneapi.api.model.Review;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewInput;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewList;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlock;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlockKind;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCreate;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostSummary;
import com.mz2az.scenetrip.sceneapi.api.model.UploadCreate;
import com.mz2az.scenetrip.sceneapi.api.model.UploadPurpose;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleClient;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.auth.TokenCipher;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.moderation.BlockStore;
import com.mz2az.scenetrip.sceneapi.moderation.ReportStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStores;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신고·금칙어 거르기·차단(MZ2AZ-438)을 <b>진짜 Store 와 진짜 PostgreSQL</b> 위에서 계약 1.19.0 에 비춰 본다 — tag {@code
 * moderation}, 리뷰 PUT·{@code POST /posts}·{@code PUT /me/nickname} 의 {@code TEXT_NOT_ALLOWED}, 목록
 * 창구들의 「로그인했으면 내가 차단한 사용자의 글은 빠진다」. 계획 {@code community-moderation.md}, 오류 코드 {@code
 * docs/api/errors.md}.
 *
 * <p>스프링 없이 컨트롤러를 손으로 조립하고 요청은 {@link MockHttpServletRequest} 의 헤더만 바꿔 쓴다 ({@code
 * ReviewFlowIntegrationTest} 와 같다). 사진 저장소(S3)만 가짜다. <b>되돌리는 트랜잭션 하나로 감싸지 않는다</b> — 차단·신고는 다음 요청이
 * 커밋된 줄을 봐야 뜻이 있으므로, 시험마다 제 촬영지·편의시설·계정·글을 커밋해 만들고 끝나면 지운다.
 */
@DisplayName("신고·금칙어·차단 흐름 (실제 DB)")
class ModerationFlowIntegrationTest {

  private static final byte[] SECRET = filled(32, (byte) 5);

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;
  private static AccountLinkStore links;

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private final CurrentAccount accounts = new CurrentAccount(request, tokens, users);
  private final FakeStorage storage = new FakeStorage();
  private final ReviewStore reviewStore = new ReviewStore(jdbc, transactions);
  private final ReviewViews views = new ReviewViews(reviewStore, storage, accounts);
  private final UploadStore uploadStore = new UploadStore(jdbc);
  private final ReviewsController reviews =
      new ReviewsController(reviewStore, views, storage, uploadStore, accounts);
  private final UploadsController uploadsApi =
      new UploadsController(storage, uploadStore, accounts);
  private final PlacesController places = new PlacesController(PlaceStores.create(jdbc), views);
  private final PostStore postStore = new PostStore(jdbc, transactions);
  private final PostsController posts =
      new PostsController(postStore, courses(), storage, uploadStore, users, accounts);
  private final ModerationController moderation =
      new ModerationController(
          new ReportStore(jdbc, transactions, 365), new BlockStore(jdbc, transactions), accounts);
  private final RefreshTokenStore refreshTokens =
      new RefreshTokenStore(jdbc, transactions, Duration.ofDays(60), Clock.systemUTC());
  private final AuthController auth =
      new AuthController(
          tokens,
          refreshTokens,
          users,
          accounts,
          null,
          new SignInService(users, links, refreshTokens, transactions),
          disabledApple());

  private final List<UUID> createdUsers = new ArrayList<>();
  private final List<Long> createdPlaces = new ArrayList<>();
  private final List<Long> createdPois = new ArrayList<>();
  private final List<Long> createdPosts = new ArrayList<>();
  private final List<Long> createdReviews = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    for (long id : createdReviews) {
      deleteReports("review", id);
    }
    for (long id : createdPosts) {
      deleteReports("post", id);
      jdbc.sql("DELETE FROM community_post WHERE id = :id").param("id", id).update();
    }
    for (long id : createdPlaces) {
      jdbc.sql("DELETE FROM place WHERE id = :id").param("id", id).update();
    }
    for (long id : createdPois) {
      jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", id).update();
    }
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM storage_deletion WHERE storage_key LIKE :k")
          .param("k", "%" + id + "%")
          .update();
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ═════════════ 금칙어 거르기 ═════════════

  @Test
  @DisplayName("리뷰 본문에 금칙어(「씨 발」 띄워 쓰기) — 400 TEXT_NOT_ALLOWED, message 에 그 말이 없고 아무것도 저장되지 않는다")
  void reviewWithBannedWordIsRejected() {
    long place = place();
    as(member());

    ApiException e =
        expect(
            () -> reviews.putMyPlaceReview(place, input(1, "음식이 씨 발 최악")),
            HttpStatus.BAD_REQUEST,
            "TEXT_NOT_ALLOWED");
    assertThat(e.getMessage()).doesNotContain("씨발").doesNotContain("씨 발");
    assertThat(reviewCount(place)).isZero();
  }

  @Test
  @DisplayName("이미 쓴 리뷰를 금칙어로 고치려 하면 400 이고 원래 리뷰가 그대로 남는다")
  void editingIntoBannedWordKeepsOriginal() {
    long place = place();
    as(member());
    Review before = reviews.putMyPlaceReview(place, input(5, "야경이 멋져요")).getBody();
    createdReviews.add(before.getId());

    expect(
        () -> reviews.putMyPlaceReview(place, input(1, "FUCK this place")),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");

    Review now = reviews.getMyPlaceReview(place).getBody();
    assertThat(now.getBody()).isEqualTo("야경이 멋져요");
    assertThat(now.getRating()).isEqualTo(5);
  }

  @Test
  @DisplayName("편의시설 리뷰도 거른다(s.h.i.t)")
  void poiReviewWithBannedWordIsRejected() {
    long poi = poi();
    as(member());
    expect(
        () -> reviews.putMyPoiReview(poi, input(1, "s.h.i.t service")),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");
  }

  @Test
  @DisplayName("예외 낱말·낱말 경계 — 「이번 여행의 시발점, 도착 시 발권」 리뷰는 200")
  void reviewWithAllowedWordsIsSaved() {
    long place = place();
    as(member());
    var response = reviews.putMyPlaceReview(place, input(4, "이번 여행의 시발점이었던 곳. 도착 시 발권하면 돼요"));
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    createdReviews.add(response.getBody().getId());
  }

  @Test
  @DisplayName("여행후기 제목·본문에 금칙어 — 400 TEXT_NOT_ALLOWED, 글이 생기지 않는다")
  void postTitleOrBodyWithBannedWordIsRejected() {
    UUID me = member();
    as(me);

    expect(
        () -> posts.createPost(new TripPostCreate("병신같은 여행", "본문", List.of()), null),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");
    expect(
        () -> posts.createPost(new TripPostCreate("제주 여행", "가이드가 개새끼였다", List.of()), null),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");
    assertThat(postsBy(me)).isZero();
  }

  @Test
  @DisplayName("여행후기 문단 블록 — 글 블록, 사진 설명(caption), 출처(credit) 어디에 있어도 400 TEXT_NOT_ALLOWED")
  void postBlocksWithBannedWordAreRejected() {
    UUID me = member();
    as(me);

    // 글 블록에만(본문은 블록을 이어 붙인 값이라 함께 걸린다).
    expect(
        () ->
            posts.createPost(
                new TripPostCreate("제목", "첫 문단\n\nfuck 둘째", List.of())
                    .bodyBlocks(
                        List.of(
                            new TripPostBlock(TripPostBlockKind.TEXT).text("첫 문단"),
                            new TripPostBlock(TripPostBlockKind.TEXT).text("fuck 둘째"))),
                null),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");

    // 사진 설명에만 — 제목·본문은 깨끗하다.
    expect(
        () ->
            posts.createPost(
                blogPost(new TripPostBlock(TripPostBlockKind.PHOTO).caption("씨발 풍경")), null),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");

    // 출처에만.
    expect(
        () ->
            posts.createPost(
                blogPost(new TripPostBlock(TripPostBlockKind.PHOTO).credit("by a.s.s.h.o.l.e")),
                null),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");

    assertThat(postsBy(me)).isZero();

    // 같은 모양에 깨끗한 설명이면 201 — 위 거절이 블록 모양 탓이 아니었다는 대조.
    var ok =
        posts.createPost(
            blogPost(new TripPostBlock(TripPostBlockKind.PHOTO).caption("시발점 풍경")), null);
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    createdPosts.add(ok.getBody().getId());
  }

  @Test
  @DisplayName("닉네임에 금칙어(「씨_발」 밑줄 끼우기 포함) — 400 TEXT_NOT_ALLOWED, 닉네임 그대로. 「시발점」 이 든 닉네임은 된다")
  void nicknameFilter() {
    UUID me = member();
    as(me);
    String before = users.profile(me).orElseThrow().nickname();
    String tail = digits();

    expect(
        () -> auth.setMyNickname(new NicknameInput("씨발" + tail)),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");
    expect(
        () -> auth.setMyNickname(new NicknameInput("씨_발" + tail)),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");
    expect(
        () -> auth.setMyNickname(new NicknameInput("Fuck_" + tail)),
        HttpStatus.BAD_REQUEST,
        "TEXT_NOT_ALLOWED");
    assertThat(users.profile(me).orElseThrow().nickname()).isEqualTo(before);

    String fine = "시발점" + tail;
    assertThat(auth.setMyNickname(new NicknameInput(fine)).getBody().getNickname()).isEqualTo(fine);
  }

  // ═════════════ 신고 ═════════════

  @Test
  @DisplayName("비회원이 설치 id 로 리뷰 신고 — 204, 줄 하나: 신고한 사람=그 설치의 계정, 이유·설명, 작성자, 본문 앞부분")
  void guestReportsReview() {
    long place = place();
    UUID author = member();
    as(author);
    long reviewId = review(place, 2, "광고 문의는 010-0000-0000");

    withoutToken();
    UUID install = UUID.randomUUID();
    var response =
        moderation.createReport(
            install,
            new ReportCreate(ReportTargetType.REVIEW, reviewId, ReportReason.PERSONAL_INFO)
                .detail("전화번호가 있어요"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    UUID guest = users.lookup(install).id();
    createdUsers.add(guest);
    List<Map<String, Object>> rows = reports("review", reviewId);
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(row.get("reporter_id")).isEqualTo(guest);
    assertThat(row.get("reason")).isEqualTo("personal_info");
    assertThat(row.get("detail")).isEqualTo("전화번호가 있어요");
    assertThat(row.get("author_id")).isEqualTo(author);
    assertThat((String) row.get("excerpt")).contains("광고 문의는");
  }

  @Test
  @DisplayName("같은 사람이 같은 글을 두 번 신고해도 204 이고 줄은 하나 — 다른 사람이 신고하면 둘")
  void duplicateReportCountsOnce() {
    long place = place();
    as(member());
    long reviewId = review(place, 1, "별로");

    UUID reporter = member();
    as(reporter);
    UUID install = UUID.randomUUID();
    ReportCreate in = new ReportCreate(ReportTargetType.REVIEW, reviewId, ReportReason.SPAM);
    assertThat(moderation.createReport(install, in).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            moderation
                .createReport(
                    install,
                    new ReportCreate(ReportTargetType.REVIEW, reviewId, ReportReason.ABUSE))
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(reports("review", reviewId)).hasSize(1);

    as(member());
    assertThat(moderation.createReport(UUID.randomUUID(), in).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(reports("review", reviewId)).hasSize(2);
  }

  @Test
  @DisplayName("토큰이 있으면 신고한 사람은 토큰의 계정이다(설치 id 가 아니다) — 여행후기 신고, 앞부분에 제목이 들고 200 자를 넘지 않는다")
  void memberReportsPostWithLongBody() {
    UUID author = member();
    as(author);
    long postId = post("부산 해운대 후기", "가".repeat(1200));

    UUID reporter = member();
    as(reporter);
    assertThat(
            moderation
                .createReport(
                    UUID.randomUUID(),
                    new ReportCreate(ReportTargetType.POST, postId, ReportReason.OTHER))
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);

    List<Map<String, Object>> rows = reports("post", postId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("reporter_id")).isEqualTo(reporter);
    assertThat(rows.get(0).get("author_id")).isEqualTo(author);
    String excerpt = (String) rows.get(0).get("excerpt");
    assertThat(excerpt).contains("부산 해운대 후기");
    assertThat(excerpt.length()).isLessThanOrEqualTo(200);
    assertThat(rows.get(0).get("detail")).isNull();
  }

  @Test
  @DisplayName("자기 리뷰·자기 후기 신고는 400 CANNOT_REPORT_OWN 이고 줄이 남지 않는다")
  void cannotReportOwn() {
    long place = place();
    UUID me = member();
    as(me);
    long reviewId = review(place, 3, "내 리뷰");
    long postId = post("내 후기", "내 본문");

    expect(
        () ->
            moderation.createReport(
                UUID.randomUUID(),
                new ReportCreate(ReportTargetType.REVIEW, reviewId, ReportReason.SPAM)),
        HttpStatus.BAD_REQUEST,
        "CANNOT_REPORT_OWN");
    expect(
        () ->
            moderation.createReport(
                UUID.randomUUID(),
                new ReportCreate(ReportTargetType.POST, postId, ReportReason.SPAM)),
        HttpStatus.BAD_REQUEST,
        "CANNOT_REPORT_OWN");
    assertThat(reports("review", reviewId)).isEmpty();
    assertThat(reports("post", postId)).isEmpty();
  }

  @Test
  @DisplayName("없는 글·운영자가 내린 글·종류가 다른 번호는 404 REPORT_TARGET_NOT_FOUND")
  void reportMissingOrRemovedTarget() {
    long place = place();
    as(member());
    long reviewId = review(place, 2, "내려갈 리뷰");
    long postId = post("내려갈 후기", "본문");
    jdbc.sql("UPDATE review SET removed_at = now() WHERE id = :id").param("id", reviewId).update();
    jdbc.sql("UPDATE community_post SET removed_at = now() WHERE id = :id")
        .param("id", postId)
        .update();

    as(member());
    for (ReportCreate in :
        List.of(
            new ReportCreate(ReportTargetType.REVIEW, Long.MAX_VALUE, ReportReason.SPAM),
            new ReportCreate(ReportTargetType.POST, Long.MAX_VALUE, ReportReason.SPAM),
            new ReportCreate(ReportTargetType.REVIEW, reviewId, ReportReason.SPAM),
            new ReportCreate(ReportTargetType.POST, postId, ReportReason.SPAM))) {
      expect(
          () -> moderation.createReport(UUID.randomUUID(), in),
          HttpStatus.NOT_FOUND,
          "REPORT_TARGET_NOT_FOUND");
    }
    assertThat(reports("review", reviewId)).isEmpty();
    assertThat(reports("post", postId)).isEmpty();
  }

  @Test
  @DisplayName("신고 뒤 글이 지워져도 신고 줄은 남는다(작성자·앞부분 그대로). 작성자가 탈퇴한 글도 신고된다(작성자 비어 있음)")
  void reportSurvivesDeletionAndOrphanCanBeReported() {
    long place = place();
    UUID author = member();
    as(author);
    long reviewId = review(place, 1, "지워질 리뷰 본문");

    as(member());
    moderation.createReport(
        UUID.randomUUID(), new ReportCreate(ReportTargetType.REVIEW, reviewId, ReportReason.ABUSE));

    as(author);
    assertThat(reviews.deleteMyPlaceReview(place).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    List<Map<String, Object>> rows = reports("review", reviewId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("author_id")).isEqualTo(author);
    assertThat((String) rows.get(0).get("excerpt")).contains("지워질 리뷰");

    // 작성자가 끊긴 옛 글(V39 이전 탈퇴분) — 글은 남고 글쓴이만 없다.
    UUID gone = member();
    as(gone);
    long orphanPost = post("탈퇴한 사람의 후기", "본문");
    withdraw(gone);

    as(member());
    assertThat(
            moderation
                .createReport(
                    UUID.randomUUID(),
                    new ReportCreate(
                        ReportTargetType.POST, orphanPost, ReportReason.INAPPROPRIATE_NICKNAME))
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    List<Map<String, Object>> orphanRows = reports("post", orphanPost);
    assertThat(orphanRows).hasSize(1);
    assertThat(orphanRows.get(0).get("author_id")).isNull();
    assertThat(orphanRows.get(0).get("reason")).isEqualTo("inappropriate_nickname");
  }

  @Test
  @DisplayName(
      "신고 401 — 가입 계정의 설치 id 만 오면 SESSION_REQUIRED, 만료된 토큰이면 ACCESS_TOKEN_EXPIRED. 줄이 남지 않는다")
  void reportUnauthorized() {
    long place = place();
    as(member());
    long reviewId = review(place, 2, "신고될 리뷰");

    UUID install = UUID.randomUUID();
    UUID registered = users.resolve(install);
    createdUsers.add(registered);
    links.register(registered, "google", "it-moderation-" + UUID.randomUUID(), null, null);

    withoutToken();
    ReportCreate in = new ReportCreate(ReportTargetType.REVIEW, reviewId, ReportReason.SPAM);
    expect(() -> moderation.createReport(install, in), HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");

    AccessTokens past =
        new AccessTokens(
            SECRET,
            Duration.ofMinutes(30),
            Clock.fixed(Instant.now().minus(Duration.ofHours(2)), java.time.ZoneOffset.UTC));
    request.addHeader("Authorization", "Bearer " + past.issue(registered).value());
    expect(
        () -> moderation.createReport(install, in),
        HttpStatus.UNAUTHORIZED,
        "ACCESS_TOKEN_EXPIRED");

    assertThat(reports("review", reviewId)).isEmpty();
  }

  // ═════════════ 차단: 창구 ═════════════

  @Test
  @DisplayName("토큰 없이 차단하기·목록·풀기는 401 SIGN_IN_REQUIRED")
  void blockingNeedsSignIn() {
    long place = place();
    as(member());
    long reviewId = review(place, 3, "리뷰");
    withoutToken();

    expectStatus(
        () -> moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, reviewId)),
        HttpStatus.UNAUTHORIZED);
    expectStatus(() -> moderation.listMyBlocks(), HttpStatus.UNAUTHORIZED);
    expectStatus(() -> moderation.deleteBlock(1L), HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("리뷰로 차단 201(지금 닉네임) → 같은 사람의 후기로 다시 차단하면 200 같은 줄 → 목록 한 줄 → 작성자가 닉네임을 바꾸면 목록도 바뀐다")
  void blockThenAgainIsIdempotent() {
    long place = place();
    UUID author = member();
    as(author);
    long reviewId = review(place, 2, "리뷰");
    long postId = post("후기", "본문");
    String nickname = users.profile(author).orElseThrow().nickname();

    UUID me = member();
    as(me);
    ResponseEntity<BlockedUser> first =
        moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, reviewId));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody().getNickname()).isEqualTo(nickname);
    assertThat(first.getBody().getBlockedAt()).isNotNull();

    ResponseEntity<BlockedUser> again =
        moderation.createBlock(new BlockCreate(ReportTargetType.POST, postId));
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody().getId()).isEqualTo(first.getBody().getId());

    assertThat(moderation.listMyBlocks().getBody().getItems())
        .extracting(BlockedUser::getId)
        .containsExactly(first.getBody().getId());

    as(author);
    String renamed = "바뀐이름" + digits();
    auth.setMyNickname(new NicknameInput(renamed));
    as(me);
    assertThat(moderation.listMyBlocks().getBody().getItems())
        .extracting(BlockedUser::getNickname)
        .containsExactly(renamed);
  }

  @Test
  @DisplayName("내 차단 목록은 최근에 차단한 사람부터이고 남의 차단은 섞이지 않는다")
  void blockListNewestFirstAndPrivate() throws InterruptedException {
    long place = place();
    UUID a = member();
    as(a);
    long ra = review(place, 3, "A");
    UUID b = member();
    as(b);
    long rb = review(place, 3, "B");

    UUID me = member();
    as(me);
    long first =
        moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, ra)).getBody().getId();
    Thread.sleep(20);
    long second =
        moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, rb)).getBody().getId();
    assertThat(moderation.listMyBlocks().getBody().getItems())
        .extracting(BlockedUser::getId)
        .containsExactly(second, first);

    as(member());
    assertThat(moderation.listMyBlocks().getBody().getItems()).isEmpty();
  }

  @Test
  @DisplayName("자기 글로 차단하면 400 CANNOT_BLOCK_SELF, 탈퇴한 작성자의 글은 400 BLOCK_TARGET_HAS_NO_AUTHOR")
  void cannotBlockSelfOrOrphan() {
    long place = place();
    UUID me = member();
    as(me);
    long mine = review(place, 3, "내 리뷰");
    long myPost = post("내 후기", "본문");
    expect(
        () -> moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, mine)),
        HttpStatus.BAD_REQUEST,
        "CANNOT_BLOCK_SELF");
    expect(
        () -> moderation.createBlock(new BlockCreate(ReportTargetType.POST, myPost)),
        HttpStatus.BAD_REQUEST,
        "CANNOT_BLOCK_SELF");

    UUID gone = member();
    as(gone);
    long orphanReview = review(place, 1, "탈퇴할 사람");
    long orphanPost = post("탈퇴할 사람의 후기", "본문");
    withdraw(gone);

    as(me);
    expect(
        () -> moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, orphanReview)),
        HttpStatus.BAD_REQUEST,
        "BLOCK_TARGET_HAS_NO_AUTHOR");
    expect(
        () -> moderation.createBlock(new BlockCreate(ReportTargetType.POST, orphanPost)),
        HttpStatus.BAD_REQUEST,
        "BLOCK_TARGET_HAS_NO_AUTHOR");
    assertThat(moderation.listMyBlocks().getBody().getItems()).isEmpty();
  }

  @Test
  @DisplayName("없는 글·내려간 글로 차단하면 404 BLOCK_TARGET_NOT_FOUND")
  void blockMissingOrRemovedTarget() {
    long place = place();
    as(member());
    long reviewId = review(place, 2, "내려갈 리뷰");
    long postId = post("내려갈 후기", "본문");
    jdbc.sql("UPDATE review SET removed_at = now() WHERE id = :id").param("id", reviewId).update();
    jdbc.sql("UPDATE community_post SET removed_at = now() WHERE id = :id")
        .param("id", postId)
        .update();

    as(member());
    for (BlockCreate in :
        List.of(
            new BlockCreate(ReportTargetType.REVIEW, Long.MAX_VALUE),
            new BlockCreate(ReportTargetType.POST, Long.MAX_VALUE),
            new BlockCreate(ReportTargetType.REVIEW, reviewId),
            new BlockCreate(ReportTargetType.POST, postId))) {
      expect(() -> moderation.createBlock(in), HttpStatus.NOT_FOUND, "BLOCK_TARGET_NOT_FOUND");
    }
    assertThat(moderation.listMyBlocks().getBody().getItems()).isEmpty();
  }

  @Test
  @DisplayName("풀기 — 남의 줄·없는 줄은 404 BLOCK_NOT_FOUND(남의 줄은 그대로), 내 줄은 204, 다시 풀면 404")
  void unblock() {
    long place = place();
    as(member());
    long reviewId = review(place, 2, "리뷰");

    UUID owner = member();
    as(owner);
    long blockId =
        moderation
            .createBlock(new BlockCreate(ReportTargetType.REVIEW, reviewId))
            .getBody()
            .getId();

    as(member());
    expect(() -> moderation.deleteBlock(blockId), HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND");
    expect(() -> moderation.deleteBlock(Long.MAX_VALUE), HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND");

    as(owner);
    assertThat(moderation.listMyBlocks().getBody().getItems()).hasSize(1);
    assertThat(moderation.deleteBlock(blockId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(moderation.listMyBlocks().getBody().getItems()).isEmpty();
    expect(() -> moderation.deleteBlock(blockId), HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND");
  }

  // ═════════════ 차단: 걸러내기 ═════════════

  @Test
  @DisplayName(
      "촬영지 리뷰 목록 — 차단한 사람에게만 빠진다(total 도), 평균 별점은 모두에게 같다, 비회원·제3자는 다 보고, 차단당한 사람은 차단한 사람의 글을 본다,"
          + " 풀면 다시 보인다")
  void placeReviewListFiltering() {
    long place = place();
    UUID blocked = member();
    as(blocked);
    long blockedReview = review(place, 1, "차단될 사람의 리뷰");
    UUID other = member();
    as(other);
    long otherReview = review(place, 5, "다른 사람");
    UUID me = member();
    as(me);
    long myReview = review(place, 4, "나");

    long blockId =
        moderation
            .createBlock(new BlockCreate(ReportTargetType.REVIEW, blockedReview))
            .getBody()
            .getId();

    ReviewList mine = reviews.listPlaceReviews(place, null, null, null).getBody();
    assertThat(ids(mine)).containsExactlyInAnyOrder(otherReview, myReview);
    assertThat(mine.getTotal()).isEqualTo(2);

    withoutToken();
    ReviewList guest = reviews.listPlaceReviews(place, null, null, null).getBody();
    assertThat(ids(guest)).containsExactlyInAnyOrder(blockedReview, otherReview, myReview);
    assertThat(guest.getTotal()).isEqualTo(3);
    assertThat(mine.getSummary().getAverage()).isEqualTo(guest.getSummary().getAverage());
    assertThat(mine.getSummary().getCount()).isEqualTo(3);
    assertThat(mine.getSummary().getDistribution())
        .containsExactlyElementsOf(guest.getSummary().getDistribution());

    // 상세의 평균도 그대로.
    as(me);
    PlaceDetail detail = places.getPlace(place, Lang.KO, null, null).getBody();
    assertThat(detail.getRating().getCount()).isEqualTo(3);
    assertThat(detail.getRating().getAverage()).isEqualTo(guest.getSummary().getAverage());

    as(other);
    assertThat(ids(reviews.listPlaceReviews(place, null, null, null).getBody())).hasSize(3);
    as(blocked);
    assertThat(ids(reviews.listPlaceReviews(place, null, null, null).getBody())).contains(myReview);

    as(me);
    moderation.deleteBlock(blockId);
    assertThat(ids(reviews.listPlaceReviews(place, null, null, null).getBody()))
        .contains(blockedReview);
  }

  @Test
  @DisplayName("쪽 나누기 — 차단한 사람이 limit 1 로 두 쪽을 넘기면 남은 두 리뷰가 하나씩, total 2, 세 번째 쪽은 비어 있다")
  void reviewPagingSkipsBlocked() {
    long place = place();
    UUID blocked = member();
    as(blocked);
    long blockedReview = review(place, 1, "차단될 사람");
    as(member());
    long b = review(place, 3, "B");
    as(member());
    long c = review(place, 4, "C");

    as(member());
    moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, blockedReview));

    ReviewList p1 = reviews.listPlaceReviews(place, null, 1, 0).getBody();
    ReviewList p2 = reviews.listPlaceReviews(place, null, 1, 1).getBody();
    ReviewList p3 = reviews.listPlaceReviews(place, null, 1, 2).getBody();
    assertThat(p1.getTotal()).isEqualTo(2);
    assertThat(p2.getTotal()).isEqualTo(2);
    List<Long> seen = new ArrayList<>(ids(p1));
    seen.addAll(ids(p2));
    assertThat(seen).containsExactlyInAnyOrder(b, c);
    assertThat(p3.getItems()).isEmpty();
  }

  @Test
  @DisplayName("편의시설 리뷰 목록도 차단한 사람에게서 빠진다")
  void poiReviewListFiltering() {
    long poi = poi();
    UUID blocked = member();
    as(blocked);
    long blockedReview = poiReview(poi, 1, "차단될 사람");
    as(member());
    long other = poiReview(poi, 5, "다른 사람");

    as(member());
    moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, blockedReview));
    ReviewList list = reviews.listPoiReviews(poi, null, null, null).getBody();
    assertThat(ids(list)).containsExactly(other);
    assertThat(list.getTotal()).isEqualTo(1);

    withoutToken();
    assertThat(ids(reviews.listPoiReviews(poi, null, null, null).getBody()))
        .containsExactlyInAnyOrder(blockedReview, other);
  }

  @Test
  @DisplayName("사진첩 — 차단한 사람에게는 차단한 작성자의 리뷰 사진이 빠지고 total 도 줄어든다(촬영지·편의시설). 비회원은 다 본다")
  void galleryFiltering() {
    long place = place();
    long poi = poi();
    UUID blocked = member();
    as(blocked);
    long blockedReview = reviewWithPhoto(place);
    long blockedPoiReview = poiReviewWithPhoto(poi);
    as(member());
    long otherReview = reviewWithPhoto(place);
    long otherPoiReview = poiReviewWithPhoto(poi);

    UUID me = member();
    as(me);
    moderation.createBlock(new BlockCreate(ReportTargetType.REVIEW, blockedReview));

    PhotoList placeMine = reviews.listPlacePhotos(place, null, null).getBody();
    assertThat(placeMine.getItems()).extracting(Photo::getReviewId).containsExactly(otherReview);
    assertThat(placeMine.getTotal()).isEqualTo(1);
    PhotoList poiMine = reviews.listPoiPhotos(poi, null, null).getBody();
    assertThat(poiMine.getItems()).extracting(Photo::getReviewId).containsExactly(otherPoiReview);
    assertThat(poiMine.getTotal()).isEqualTo(1);

    withoutToken();
    PhotoList placeGuest = reviews.listPlacePhotos(place, null, null).getBody();
    assertThat(placeGuest.getItems())
        .extracting(Photo::getReviewId)
        .containsExactlyInAnyOrder(blockedReview, otherReview);
    assertThat(placeGuest.getTotal()).isEqualTo(2);
    assertThat(reviews.listPoiPhotos(poi, null, null).getBody().getItems())
        .extracting(Photo::getReviewId)
        .containsExactlyInAnyOrder(blockedPoiReview, otherPoiReview);
  }

  @Test
  @DisplayName("여행후기 — 차단한 사람의 목록에서 빠지고 상세는 404. 비회원·차단당한 사람은 그대로 본다(한쪽 방향). 풀면 다시 열린다")
  void postFiltering() {
    UUID blocked = member();
    as(blocked);
    long blockedPost = post("차단될 사람의 후기", "본문");
    UUID other = member();
    as(other);
    long otherPost = post("다른 사람의 후기", "본문");
    UUID me = member();
    as(me);
    long myPost = post("내 후기", "본문");

    long blockId =
        moderation
            .createBlock(new BlockCreate(ReportTargetType.POST, blockedPost))
            .getBody()
            .getId();

    List<Long> mine = postIds();
    assertThat(mine).contains(otherPost, myPost).doesNotContain(blockedPost);
    expectStatus(() -> posts.getPost(blockedPost, null), HttpStatus.NOT_FOUND);
    assertThat(posts.getPost(otherPost, null).getStatusCode()).isEqualTo(HttpStatus.OK);

    withoutToken();
    assertThat(postIds()).contains(blockedPost, otherPost, myPost);
    assertThat(posts.getPost(blockedPost, null).getStatusCode()).isEqualTo(HttpStatus.OK);

    as(blocked);
    assertThat(postIds()).contains(myPost);
    assertThat(posts.getPost(myPost, null).getStatusCode()).isEqualTo(HttpStatus.OK);

    as(me);
    moderation.deleteBlock(blockId);
    assertThat(postIds()).contains(blockedPost);
    assertThat(posts.getPost(blockedPost, null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("여행후기 목록의 total 은 차단한 사람에게 하나 줄어든다 — 쪽 나누기가 맞도록")
  void postTotalShrinksForBlocker() {
    UUID blocked = member();
    as(blocked);
    long blockedPost = post("차단될 사람의 후기", "본문");
    UUID me = member();
    as(me);
    long before = posts.listPosts(null, 1, 0).getBody().getTotal();
    moderation.createBlock(new BlockCreate(ReportTargetType.POST, blockedPost));
    long after = posts.listPosts(null, 1, 0).getBody().getTotal();
    assertThat(after).isEqualTo(before - 1);
  }

  // ───────────── 도우미 ─────────────

  private long place() {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(126.98, 37.58), 4326)::geography) RETURNING id")
            .query(Long.class)
            .single();
    createdPlaces.add(id);
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, 'ko', :n)")
        .param("p", id)
        .param("n", "신고차단 시험 촬영지")
        .update();
    return id;
  }

  private long poi() {
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group)
                VALUES (:s, '신고차단 시험 식당',
                        ST_SetSRID(ST_MakePoint(126.25, 33.21), 4326)::geography, '한식', 'food')
                RETURNING id
                """)
            .param("s", "it-moderation-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    createdPois.add(id);
    return id;
  }

  /** 지금 사용자로 촬영지 리뷰를 쓴다. */
  private long review(long place, int rating, String body) {
    long id = reviews.putMyPlaceReview(place, input(rating, body)).getBody().getId();
    createdReviews.add(id);
    return id;
  }

  private long poiReview(long poi, int rating, String body) {
    long id = reviews.putMyPoiReview(poi, input(rating, body)).getBody().getId();
    createdReviews.add(id);
    return id;
  }

  private long reviewWithPhoto(long place) {
    String tmp = upload(UploadPurpose.REVIEW);
    long id =
        reviews.putMyPlaceReview(place, input(4, "사진").photoKeys(List.of(tmp))).getBody().getId();
    createdReviews.add(id);
    return id;
  }

  private long poiReviewWithPhoto(long poi) {
    String tmp = upload(UploadPurpose.REVIEW);
    long id = reviews.putMyPoiReview(poi, input(4, "사진").photoKeys(List.of(tmp))).getBody().getId();
    createdReviews.add(id);
    return id;
  }

  private String upload(UploadPurpose purpose) {
    String key =
        uploadsApi
            .createUpload(new UploadCreate(purpose, "image/jpeg", 123_456L))
            .getBody()
            .getKey();
    storage.files.add(key);
    return key;
  }

  /** 지금 사용자로 여행후기를 쓴다. */
  private long post(String title, String body) {
    var created = posts.createPost(new TripPostCreate(title, body, List.of()), null);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    long id = created.getBody().getId();
    createdPosts.add(id);
    return id;
  }

  /** 글 두 문단 사이에 사진 한 장 — 사진 블록은 넘겨받은 것(설명·출처만 다르게). */
  private TripPostCreate blogPost(TripPostBlock photo) {
    String key = upload(UploadPurpose.POST);
    return new TripPostCreate("블로그 후기", "첫 문단\n\n둘째 문단", List.of(key))
        .bodyBlocks(
            List.of(
                new TripPostBlock(TripPostBlockKind.TEXT).text("첫 문단"),
                photo.photoIndex(0),
                new TripPostBlock(TripPostBlockKind.TEXT).text("둘째 문단")));
  }

  private List<Long> postIds() {
    return posts.listPosts(null, 50, 0).getBody().getItems().stream()
        .map(TripPostSummary::getId)
        .toList();
  }

  private static long postsBy(UUID user) {
    return jdbc.sql("SELECT count(*) FROM community_post WHERE user_id = CAST(:u AS UUID)")
        .param("u", user.toString())
        .query(Long.class)
        .single();
  }

  private static List<Map<String, Object>> reports(String type, long id) {
    return jdbc.sql(
            "SELECT reporter_id, reason, detail, author_id, excerpt FROM content_report"
                + " WHERE target_type = :t AND target_id = :i ORDER BY id")
        .param("t", type)
        .param("i", id)
        .query()
        .listOfRows();
  }

  private static void deleteReports(String type, long id) {
    jdbc.sql("DELETE FROM content_report WHERE target_type = :t AND target_id = :i")
        .param("t", type)
        .param("i", id)
        .update();
  }

  private static long reviewCount(long placeId) {
    return jdbc.sql("SELECT count(*) FROM review WHERE place_id = :p")
        .param("p", placeId)
        .query(Long.class)
        .single();
  }

  /**
   * 글쓴이 없는 글을 만든다 — 예전 탈퇴(MZ2AZ-466 이전, V21·V27 의 ON DELETE SET NULL)가 남긴 행의 모습. 지금은 탈퇴하면 글도 지워지지만
   * (V39 CASCADE, MZ2AZ-500) 그 이전 행은 운영 DB 에 남아 있을 수 있어 신고·차단이 그것을 다루는지 계속 본다. 글쓴이를 먼저 끊고 계정을 지운다.
   */
  private static void withdraw(UUID user) {
    jdbc.sql("UPDATE review SET user_id = NULL WHERE user_id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
    jdbc.sql("UPDATE community_post SET user_id = NULL WHERE user_id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
  }

  private static List<Long> ids(ReviewList list) {
    return list.getItems().stream().map(Review::getId).toList();
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    createdUsers.add(id);
    links.register(id, "google", "it-moderation-" + UUID.randomUUID(), null, null);
    return id;
  }

  private void as(UUID user) {
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + tokens.issue(user).value());
  }

  private void withoutToken() {
    request.removeHeader("Authorization");
  }

  private static ReviewInput input(int rating, String body) {
    return new ReviewInput(rating, List.of()).body(body);
  }

  private static ApiException expect(Runnable call, HttpStatus status, String code) {
    ApiException[] caught = new ApiException[1];
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(status);
              assertThat(e.getCode()).isEqualTo(code);
              caught[0] = e;
            });
    return caught[0];
  }

  private static void expectStatus(Runnable call, HttpStatus status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
  }

  private static String digits() {
    return String.valueOf(
        100000 + (Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 900000));
  }

  private static CourseStore courses() {
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    return new CourseStore(jdbc, DeployedTravel.estimator(), dwell, transactions);
  }

  private static byte[] filled(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }

  private static AppleLogin disabledApple() {
    return new AppleLogin(
        null,
        new AppleClient(null, "com.example", "TEAM", "KEY", null, Clock.systemUTC()),
        new TokenCipher((byte[]) null),
        new AccountLinkStore(IntegrationDatabase.jdbcClient()));
  }

  /** 가짜 S3 — 올린 것처럼 둔 파일 집합. 옮기기는 실제처럼 없으면 실패한다. */
  private static final class FakeStorage implements PhotoStorage {
    final Set<String> files = new HashSet<>();

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public URI viewUrl(String storageKey) {
      return URI.create("https://signed.example/" + storageKey);
    }

    @Override
    public PresignedUpload presignUpload(
        String storageKey, String contentType, long bytes, Duration expiresIn) {
      return new PresignedUpload(
          URI.create("https://bucket.example/" + storageKey),
          Map.of("Content-Type", contentType),
          Instant.now().plus(expiresIn));
    }

    @Override
    public void move(String fromKey, String toKey) {
      if (!files.remove(fromKey)) {
        throw new MissingPhotoException(fromKey);
      }
      files.add(toKey);
    }

    @Override
    public void listPages(
        String prefix, java.util.function.Consumer<java.util.List<StoredObject>> onPage) {}

    @Override
    public void delete(String storageKey) {
      files.remove(storageKey);
    }
  }
}
