package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.CourseDay;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseStatus;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.TravelBasis;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 여행후기 창구(계약 1.10.0 tag {@code posts}, 계획 {@code community-post.md})를 HTTP 로 본다 — 인증 규칙·오류 코드·사진
 * 옮기기·응답 모양.
 *
 * <p>{@link CurrentAccount} · {@link AccessTokens} 는 진짜다 — 401 의 코드가 실제 헤더 처리에서 갈린다. DB 를 아는 {@link
 * PostStore} · {@link CourseStore} · {@link UploadStore} · {@link UserStore} 와 사진 저장소만 가짜이고, 그 SQL
 * 은 통합 레인({@code PostStoreIntegrationTest})이 본다.
 */
@WebMvcTest(PostsController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, PostsControllerTest.Tokens.class})
@DisplayName("PostsController — 여행후기 쓰기·목록·상세·지우기·담기")
class PostsControllerTest {

  private static final byte[] SECRET = filled(32, (byte) 7);
  private static final Instant T0 = Instant.parse("2026-10-10T00:00:00Z");
  private static final UUID USER = UUID.fromString("2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d");
  private static final String INSTALL_ID = "7e8f9a0b-1c2d-4e3f-8a5b-6c7d8e9f0a1b";
  private static final UUID GUEST = UUID.fromString("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e");
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-09T09:00:00Z");

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.fixed(T0, ZoneOffset.UTC));
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private AccessTokens tokens;

  @MockitoBean private PostStore store;
  @MockitoBean private CourseStore courses;
  @MockitoBean private PhotoStorage storage;
  @MockitoBean private UploadStore uploads;
  @MockitoBean private UserStore users;

  @BeforeEach
  void setUp() {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.isRegistered(USER)).thenReturn(true);
    when(users.lookup(UUID.fromString(INSTALL_ID))).thenReturn(new UserStore.Account(GUEST, false));
    when(users.isRegistered(GUEST)).thenReturn(false);
    when(storage.viewUrl(anyString()))
        .thenAnswer(inv -> URI.create("https://signed.example/" + inv.getArgument(0)));
    when(uploads.unattached(any(), anyCollection())).thenReturn(Set.of());
    when(store.list(any(), any(), anyInt(), anyInt()))
        .thenReturn(new PostStore.Page<>(List.of(), 0));
    when(store.find(anyLong(), any())).thenReturn(Optional.empty());
    when(store.stops(anyLong(), anyString())).thenReturn(List.of());
  }

  // ───────────── 인증 ─────────────

  @Test
  @DisplayName("쓰기 — 토큰이 없으면 401 SIGN_IN_REQUIRED 이고 아무것도 옮기거나 저장하지 않는다")
  void createRequiresMember() throws Exception {
    mvc.perform(postJson("/posts", createBody("제목", "본문", "[]", null), null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    // 설치 UUID 만으로는 쓸 수 없다
    mvc.perform(
            postJson("/posts", createBody("제목", "본문", "[]", null), null)
                .header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verifyNeverCreated();
    verify(storage, never()).move(anyString(), anyString());
  }

  @Test
  @DisplayName("쓰기 — 만료·위조 토큰은 401 (ACCESS_TOKEN_EXPIRED · ACCESS_TOKEN_INVALID)")
  void createWithBadToken() throws Exception {
    mvc.perform(postJson("/posts", createBody("제목", "본문", "[]", null), expiredBearer()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));
    mvc.perform(
            postJson(
                "/posts",
                createBody("제목", "본문", "[]", null),
                "Bearer " + forgedTokens().issue(USER).value()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));

    verifyNeverCreated();
  }

  @Test
  @DisplayName("지우기 — 토큰이 없으면 401 SIGN_IN_REQUIRED 이고 지우지 않는다")
  void deleteRequiresMember() throws Exception {
    mvc.perform(delete("/posts/9"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verify(store, never()).delete(anyLong(), any());
  }

  @Test
  @DisplayName("내 글 목록 — 토큰이 없으면 401, Store 를 부르지 않는다")
  void myPostsRequireSignIn() throws Exception {
    mvc.perform(get("/me/posts")).andExpect(status().isUnauthorized());
    mvc.perform(get("/me/posts").header("Authorization", expiredBearer()))
        .andExpect(status().isUnauthorized());

    verify(store, never()).list(any(), any(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("목록·상세 — 누구나 읽는다. 토큰이 없거나 믿을 수 없으면 보는 사람은 null 이고 200")
  void readIsOpen() throws Exception {
    when(store.find(eq(3L), any())).thenReturn(Optional.of(detailRow(3L, null, List.of(), false)));

    mvc.perform(get("/posts")).andExpect(status().isOk());
    mvc.perform(get("/posts").header("Authorization", expiredBearer())).andExpect(status().isOk());
    mvc.perform(get("/posts").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isOk());
    mvc.perform(get("/posts/3")).andExpect(status().isOk());
    mvc.perform(get("/posts/3").header("Authorization", expiredBearer()))
        .andExpect(status().isOk());

    verify(store, times(3)).list(isNull(), isNull(), eq(20), eq(0));
    verify(store, times(2)).find(eq(3L), isNull());
    verify(store, never()).list(eq(USER), any(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("목록 — 토큰을 보낸 가입 사용자가 보는 사람으로 가고(isMine), 글쓴이 거르기는 없다. limit·offset 이 Store 로 간다")
  void listWithTokenPassesViewer() throws Exception {
    mvc.perform(
            get("/posts")
                .header("Authorization", bearer())
                .param("limit", "5")
                .param("offset", "10"))
        .andExpect(status().isOk());

    verify(store).list(eq(USER), isNull(), eq(5), eq(10));
  }

  @Test
  @DisplayName("내 글 목록 — 보는 사람과 글쓴이 모두 나, 기본 limit 20 · offset 0")
  void myPostsFilterByAuthor() throws Exception {
    when(store.list(eq(USER), eq(USER), anyInt(), anyInt()))
        .thenReturn(new PostStore.Page<>(List.of(summaryRow(4L, "나", true)), 1));

    mvc.perform(get("/me/posts").header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(4))
        .andExpect(jsonPath("$.items[0].isMine").value(true));

    verify(store).list(eq(USER), eq(USER), eq(20), eq(0));
  }

  @Test
  @DisplayName("담기 — 설치 UUID 만 보낸 비회원은 401 SIGN_IN_REQUIRED 이고 담지 않는다")
  void saveRequiresRegistered() throws Exception {
    mvc.perform(post("/posts/3/saves").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verify(store, never()).save(any(), anyLong());
  }

  @Test
  @DisplayName("담기 — 가입 계정의 설치 UUID 만 오면(토큰 없음) 401 SESSION_REQUIRED")
  void saveWithRegisteredInstallButNoToken() throws Exception {
    when(users.lookup(UUID.fromString(INSTALL_ID))).thenReturn(new UserStore.Account(USER, true));

    mvc.perform(post("/posts/3/saves").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));

    verify(store, never()).save(any(), anyLong());
  }

  // ───────────── POST /posts — 입력 ─────────────

  @Test
  @DisplayName("쓰기 — 제목·본문이 공백뿐이면 400 INVALID_PARAMETER, 사진을 옮기지 않는다")
  void blankTitleOrBody() throws Exception {
    for (String json :
        List.of(
            createBody("   ", "본문", "[\"uploads/tmp/a.jpg\"]", null),
            createBody("제목", " \n\t ", "[\"uploads/tmp/a.jpg\"]", null),
            createBody("", "본문", "[]", null),
            createBody("제목", "", "[]", null))) {
      mvc.perform(postJson("/posts", json, bearer()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    verifyNeverCreated();
    verify(storage, never()).move(anyString(), anyString());
  }

  @Test
  @DisplayName("쓰기 — 같은 키가 두 번이면 400 POST_PHOTO_INVALID, 아무것도 옮기지 않는다")
  void duplicateKeys() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection())).thenReturn(Set.of("uploads/tmp/a.jpg"));

    mvc.perform(
            postJson(
                "/posts",
                createBody("제목", "본문", "[\"uploads/tmp/a.jpg\",\"uploads/tmp/a.jpg\"]", null),
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POST_PHOTO_INVALID"));

    verifyNeverCreated();
    verify(storage, never()).move(anyString(), anyString());
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 내가 받지 않은 키가 섞이면 400 POST_PHOTO_INVALID, 그 전에 옮긴 것은 되돌린다")
  void unknownKeyMovesBack() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection()))
        .thenReturn(Set.of("uploads/tmp/a.jpg", "uploads/tmp/c.png"));

    mvc.perform(
            postJson(
                "/posts",
                createBody(
                    "제목",
                    "본문",
                    "[\"uploads/tmp/a.jpg\",\"uploads/tmp/x.jpg\",\"uploads/tmp/c.png\"]",
                    null),
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POST_PHOTO_INVALID"));

    verifyNeverCreated();
    // 되돌리고 나면 임시 자리에 모두 남아 있어야 한다 — 옮긴 수와 되돌린 수가 같다.
    assertNetMovesUndone();
    verify(storage, never()).move(eq("uploads/tmp/x.jpg"), anyString());
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 받은 주소로 올리지 않아 저장소에 없으면 400 POST_PHOTO_INVALID, 앞서 옮긴 것은 되돌린다")
  void missingInStorageMovesBack() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection()))
        .thenReturn(Set.of("uploads/tmp/a.jpg", "uploads/tmp/b.jpg"));
    doThrow(new PhotoStorage.MissingPhotoException("uploads/tmp/b.jpg"))
        .when(storage)
        .move("uploads/tmp/b.jpg", "posts/b.jpg");

    mvc.perform(
            postJson(
                "/posts",
                createBody("제목", "본문", "[\"uploads/tmp/a.jpg\",\"uploads/tmp/b.jpg\"]", null),
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POST_PHOTO_INVALID"));

    verifyNeverCreated();
    verify(storage).move("uploads/tmp/a.jpg", "posts/a.jpg");
    verify(storage).move("posts/a.jpg", "uploads/tmp/a.jpg");
    verify(storage, never()).move("posts/b.jpg", "uploads/tmp/b.jpg");
  }

  @Test
  @DisplayName("쓰기 — 사진 9 장은 400(계약 maxItems 8)이고 옮기지 않는다")
  void tooManyPhotos() throws Exception {
    StringBuilder keys = new StringBuilder("[");
    for (int i = 0; i < 9; i++) {
      keys.append(i == 0 ? "" : ",").append("\"uploads/tmp/k").append(i).append(".jpg\"");
    }
    keys.append("]");

    mvc.perform(postJson("/posts", createBody("제목", "본문", keys.toString(), null), bearer()))
        .andExpect(status().isBadRequest());

    verifyNeverCreated();
    verify(storage, never()).move(anyString(), anyString());
  }

  // ───────────── POST /posts — 성공·Store 거절 ─────────────

  @Test
  @DisplayName("쓰기 — 201. 키를 순서대로 uploads/tmp/x → posts/x 로 옮기고, 그 순서로 Store 에 넘기고, 업로드 기록을 소비한다")
  void createMovesInOrderAndStores() throws Exception {
    List<String> tmp = List.of("uploads/tmp/c.png", "uploads/tmp/a.jpg", "uploads/tmp/b.webp");
    when(uploads.unattached(eq(USER), anyCollection())).thenReturn(Set.copyOf(tmp));
    when(store.create(eq(USER), anyString(), anyString(), anyList(), anyCollection(), any()))
        .thenReturn(42L);
    when(store.find(eq(42L), eq(USER)))
        .thenReturn(
            Optional.of(
                detailRow(42L, null, List.of("posts/c.png", "posts/a.jpg", "posts/b.webp"), true)));

    mvc.perform(
            postJson(
                "/posts",
                createBody(
                    "  제주 2박  ",
                    "  좋았다  ",
                    "[\"uploads/tmp/c.png\",\"uploads/tmp/a.jpg\",\"uploads/tmp/b.webp\"]",
                    7L),
                bearer()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(42))
        .andExpect(jsonPath("$.isMine").value(true))
        .andExpect(jsonPath("$.photoUrls.length()").value(3))
        .andExpect(jsonPath("$.photoUrls[0]").value("https://signed.example/posts/c.png"));

    InOrder order = inOrder(storage, store, uploads);
    order.verify(storage).move("uploads/tmp/c.png", "posts/c.png");
    order.verify(storage).move("uploads/tmp/a.jpg", "posts/a.jpg");
    order.verify(storage).move("uploads/tmp/b.webp", "posts/b.webp");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> fresh = ArgumentCaptor.forClass(Collection.class);
    verify(store).create(eq(USER), eq("제주 2박"), eq("좋았다"), keys.capture(), fresh.capture(), eq(7L));
    assertThat(keys.getValue()).containsExactly("posts/c.png", "posts/a.jpg", "posts/b.webp");
    assertThat(fresh.getValue())
        .containsExactlyInAnyOrder("posts/c.png", "posts/a.jpg", "posts/b.webp");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> consumed = ArgumentCaptor.forClass(Collection.class);
    verify(uploads).consume(consumed.capture());
    assertThat(consumed.getValue()).containsExactlyInAnyOrderElementsOf(tmp);
    verify(storage, times(3)).move(anyString(), anyString());
  }

  @Test
  @DisplayName("쓰기 — 사진 없이·코스 없이도 201, courseId 는 null 로 간다")
  void createWithoutPhotosOrCourse() throws Exception {
    when(store.create(eq(USER), anyString(), anyString(), anyList(), anyCollection(), any()))
        .thenReturn(5L);
    when(store.find(eq(5L), eq(USER)))
        .thenReturn(Optional.of(detailRow(5L, null, List.of(), true)));

    mvc.perform(postJson("/posts", createBody("제목", "본문", "[]", null), bearer()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.photoUrls.length()").value(0))
        .andExpect(jsonPath("$.course").doesNotExist());

    verify(store).create(eq(USER), eq("제목"), eq("본문"), eq(List.of()), anyCollection(), isNull());
    verify(storage, never()).move(anyString(), anyString());
  }

  @Test
  @DisplayName("쓰기 — Store 가 코스를 거절하면 400 POST_COURSE_INVALID, 옮긴 사진은 모두 되돌리고 업로드 기록은 남긴다")
  void courseRejectedMovesBack() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection()))
        .thenReturn(Set.of("uploads/tmp/a.jpg", "uploads/tmp/b.jpg"));
    when(store.create(eq(USER), anyString(), anyString(), anyList(), anyCollection(), eq(99L)))
        .thenThrow(new PostStore.CourseRejectedException(99L));

    mvc.perform(
            postJson(
                "/posts",
                createBody("제목", "본문", "[\"uploads/tmp/a.jpg\",\"uploads/tmp/b.jpg\"]", 99L),
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POST_COURSE_INVALID"));

    verify(storage).move("uploads/tmp/a.jpg", "posts/a.jpg");
    verify(storage).move("uploads/tmp/b.jpg", "posts/b.jpg");
    verify(storage).move("posts/a.jpg", "uploads/tmp/a.jpg");
    verify(storage).move("posts/b.jpg", "uploads/tmp/b.jpg");
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — Store 가 사진 키를 거절하면 400 POST_PHOTO_INVALID, 옮긴 사진은 되돌린다")
  void photoRejectedByStoreMovesBack() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection())).thenReturn(Set.of("uploads/tmp/a.jpg"));
    when(store.create(eq(USER), anyString(), anyString(), anyList(), anyCollection(), any()))
        .thenThrow(new PostStore.PhotoKeyRejectedException("거절"));

    mvc.perform(
            postJson("/posts", createBody("제목", "본문", "[\"uploads/tmp/a.jpg\"]", null), bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POST_PHOTO_INVALID"));

    verify(storage).move("posts/a.jpg", "uploads/tmp/a.jpg");
    verify(uploads, never()).consume(anyCollection());
  }

  // ───────────── DELETE /posts/{id} ─────────────

  @Test
  @DisplayName("지우기 — 내 글이면 204, 남의 글·없는 글이면 404 POST_NOT_FOUND")
  void deleteMineOrNotFound() throws Exception {
    when(store.delete(9L, USER)).thenReturn(true);
    when(store.delete(10L, USER)).thenReturn(false);

    mvc.perform(delete("/posts/9").header("Authorization", bearer()))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/posts/10").header("Authorization", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  // ───────────── GET /posts/{id} ─────────────

  @Test
  @DisplayName("상세 — 없는(또는 내린) 글은 404 POST_NOT_FOUND")
  void getUnknownIsNotFound() throws Exception {
    mvc.perform(get("/posts/404"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  @Test
  @DisplayName("목록 — 계약 모양: 글쓴이·대표 사진(서명 주소)·사진 수·코스 요약. 탈퇴한 글쓴이는 author 없음, 사진·코스 없으면 그 칸 없음")
  void listMapping() throws Exception {
    PostStore.Summary withAll =
        new PostStore.Summary(
            8L, "제주 2박", "앞부분", "제주러버", CREATED, "posts/cover.jpg", 3, "제주 코스", 2, 5, false);
    PostStore.Summary bare =
        new PostStore.Summary(7L, "탈퇴한 사람의 글", "본문", null, CREATED, null, 0, null, null, 0, false);
    when(store.list(any(), any(), anyInt(), anyInt()))
        .thenReturn(new PostStore.Page<>(List.of(withAll, bare), 12));

    mvc.perform(get("/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(12))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(8))
        .andExpect(jsonPath("$.items[0].title").value("제주 2박"))
        .andExpect(jsonPath("$.items[0].excerpt").value("앞부분"))
        .andExpect(jsonPath("$.items[0].author.nickname").value("제주러버"))
        .andExpect(jsonPath("$.items[0].createdAt").exists())
        .andExpect(
            jsonPath("$.items[0].coverPhotoUrl").value("https://signed.example/posts/cover.jpg"))
        .andExpect(jsonPath("$.items[0].photoCount").value(3))
        .andExpect(jsonPath("$.items[0].course.title").value("제주 코스"))
        .andExpect(jsonPath("$.items[0].course.dayCount").value(2))
        .andExpect(jsonPath("$.items[0].course.placeCount").value(5))
        .andExpect(jsonPath("$.items[0].isMine").value(false))
        .andExpect(jsonPath("$.items[1].author").doesNotExist())
        .andExpect(jsonPath("$.items[1].coverPhotoUrl").doesNotExist())
        .andExpect(jsonPath("$.items[1].photoCount").value(0))
        .andExpect(jsonPath("$.items[1].course").doesNotExist());
  }

  @Test
  @DisplayName("상세 — 사진 주소는 순서대로 서명, 코스는 일차별(핀만 있던 일차는 빈 stops), 촬영지·편의시설 칸, isMine")
  void detailMapping() throws Exception {
    PostStore.Summary head =
        new PostStore.Summary(
            3L, "제목", "발췌", "제주러버", CREATED, "posts/1.jpg", 2, "제주 3일", 3, 3, true);
    when(store.find(eq(3L), eq(USER)))
        .thenReturn(
            Optional.of(
                new PostStore.Detail(head, "본문 전체", List.of("posts/1.jpg", "posts/2.jpg"))));
    when(store.stops(3L, "ja"))
        .thenReturn(
            List.of(
                new PostStore.Stop(
                    1,
                    11L,
                    null,
                    "北村韓屋村",
                    "ソウル",
                    "촬영지",
                    37.58,
                    126.98,
                    "https://img.example/p.jpg",
                    90,
                    null,
                    null,
                    null,
                    null),
                new PostStore.Stop(
                    1,
                    null,
                    22L,
                    "모슬포식당",
                    "제주 서귀포시",
                    "음식점",
                    33.2,
                    126.25,
                    null,
                    60,
                    "モスルポ食堂",
                    "Moseulpo Sikdang",
                    "済州 西帰浦",
                    "飲食店"),
                new PostStore.Stop(
                    3, 12L, null, "성산", null, "촬영지", 33.45, 126.94, null, 30, null, null, null,
                    null)));

    mvc.perform(get("/posts/3").header("Authorization", bearer()).header("Accept-Language", "ja"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(3))
        .andExpect(jsonPath("$.title").value("제목"))
        .andExpect(jsonPath("$.body").value("본문 전체"))
        .andExpect(jsonPath("$.author.nickname").value("제주러버"))
        .andExpect(jsonPath("$.isMine").value(true))
        .andExpect(jsonPath("$.photoUrls[0]").value("https://signed.example/posts/1.jpg"))
        .andExpect(jsonPath("$.photoUrls[1]").value("https://signed.example/posts/2.jpg"))
        .andExpect(jsonPath("$.course.title").value("제주 3일"))
        .andExpect(jsonPath("$.course.days.length()").value(3))
        .andExpect(jsonPath("$.course.days[0].dayNumber").value(1))
        .andExpect(jsonPath("$.course.days[0].stops.length()").value(2))
        .andExpect(jsonPath("$.course.days[0].stops[0].source").value("place"))
        .andExpect(jsonPath("$.course.days[0].stops[0].placeId").value(11))
        .andExpect(jsonPath("$.course.days[0].stops[0].poiId").doesNotExist())
        .andExpect(jsonPath("$.course.days[0].stops[0].name").value("北村韓屋村"))
        .andExpect(jsonPath("$.course.days[0].stops[0].address").value("ソウル"))
        .andExpect(jsonPath("$.course.days[0].stops[0].latitude").value(37.58))
        .andExpect(jsonPath("$.course.days[0].stops[0].longitude").value(126.98))
        .andExpect(
            jsonPath("$.course.days[0].stops[0].imageUrl").value("https://img.example/p.jpg"))
        .andExpect(jsonPath("$.course.days[0].stops[0].dwellMinutes").value(90))
        .andExpect(jsonPath("$.course.days[0].stops[0].displayName").doesNotExist())
        .andExpect(jsonPath("$.course.days[0].stops[1].source").value("poi"))
        .andExpect(jsonPath("$.course.days[0].stops[1].poiId").value(22))
        .andExpect(jsonPath("$.course.days[0].stops[1].placeId").doesNotExist())
        .andExpect(jsonPath("$.course.days[0].stops[1].name").value("모슬포식당"))
        .andExpect(jsonPath("$.course.days[0].stops[1].displayName").value("モスルポ食堂"))
        .andExpect(jsonPath("$.course.days[0].stops[1].nameRoman").value("Moseulpo Sikdang"))
        .andExpect(jsonPath("$.course.days[0].stops[1].displayAddress").value("済州 西帰浦"))
        .andExpect(jsonPath("$.course.days[0].stops[1].category").value("음식점"))
        .andExpect(jsonPath("$.course.days[0].stops[1].categoryLabel").value("飲食店"))
        .andExpect(jsonPath("$.course.days[1].dayNumber").value(2))
        .andExpect(jsonPath("$.course.days[1].stops.length()").value(0))
        .andExpect(jsonPath("$.course.days[2].dayNumber").value(3))
        .andExpect(jsonPath("$.course.days[2].stops[0].placeId").value(12))
        .andExpect(jsonPath("$.course.days[2].stops[0].imageUrl").doesNotExist());

    verify(store).stops(3L, "ja");
  }

  @Test
  @DisplayName("상세 — Accept-Language 가 없으면 코스 장소는 en 으로 묻는다. 탈퇴한 글쓴이는 author 없음, 코스 없으면 course 없음")
  void detailDefaultsAndWithdrawnAuthor() throws Exception {
    PostStore.Summary withCourse =
        new PostStore.Summary(3L, "제목", "발췌", null, CREATED, null, 0, "코스", 1, 0, false);
    when(store.find(eq(3L), any()))
        .thenReturn(Optional.of(new PostStore.Detail(withCourse, "본문", List.of())));
    when(store.find(eq(4L), any())).thenReturn(Optional.of(detailRow(4L, null, List.of(), false)));

    mvc.perform(get("/posts/3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.author").doesNotExist())
        .andExpect(jsonPath("$.isMine").value(false))
        .andExpect(jsonPath("$.course.days.length()").value(1))
        .andExpect(jsonPath("$.course.days[0].stops.length()").value(0));
    verify(store).stops(3L, "en");

    mvc.perform(get("/posts/4"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.course").doesNotExist());
    verify(store, never()).stops(eq(4L), anyString());
  }

  // ───────────── POST /posts/{id}/saves ─────────────

  @Test
  @DisplayName("담기 — 가입 사용자면 201 이고 응답이 새 코스(요청 언어로 읽은 것)다")
  void saveReturnsNewCourse() throws Exception {
    when(store.save(USER, 3L)).thenReturn(Optional.of(77L));
    when(courses.find(USER, 77L, Lang.KO)).thenReturn(Optional.of(courseDetail(77L)));

    mvc.perform(
            post("/posts/3/saves")
                .header("X-Install-Id", INSTALL_ID)
                .header("Authorization", bearer())
                .header("Accept-Language", "ko"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(77))
        .andExpect(jsonPath("$.origin").value("market"));

    verify(store).save(USER, 3L);
  }

  @Test
  @DisplayName("담기 — 없는 글·내린 글·코스가 붙지 않은 글이면 404 POST_NOT_FOUND")
  void saveWithoutCourseIsNotFound() throws Exception {
    when(store.save(USER, 3L)).thenReturn(Optional.empty());

    mvc.perform(
            post("/posts/3/saves")
                .header("X-Install-Id", INSTALL_ID)
                .header("Authorization", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

    verify(courses, never()).find(any(), anyLong(), any());
  }

  // ───────────── 도우미 ─────────────

  /** 옮긴 것(uploads/tmp → posts)마다 같은 수의 되돌리기(posts → uploads/tmp)가 있었는지. */
  private void assertNetMovesUndone() {
    ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
    verify(storage, org.mockito.Mockito.atLeast(0)).move(from.capture(), to.capture());
    List<String> forward = new java.util.ArrayList<>();
    List<String> back = new java.util.ArrayList<>();
    for (int i = 0; i < from.getAllValues().size(); i++) {
      String f = from.getAllValues().get(i);
      String t = to.getAllValues().get(i);
      if (f.startsWith("uploads/tmp/")) {
        assertThat(t).isEqualTo("posts/" + f.substring("uploads/tmp/".length()));
        forward.add(f);
      } else {
        assertThat(f).startsWith("posts/");
        back.add(t);
      }
    }
    assertThat(back).containsExactlyInAnyOrderElementsOf(forward);
  }

  private void verifyNeverCreated() {
    verify(store, never()).create(any(), any(), any(), anyList(), anyCollection(), any());
  }

  private static PostStore.Summary summaryRow(long id, String nickname, boolean mine) {
    return new PostStore.Summary(id, "제목", "발췌", nickname, CREATED, null, 0, null, null, 0, mine);
  }

  private static PostStore.Detail detailRow(
      long id, String nickname, List<String> keys, boolean mine) {
    return new PostStore.Detail(
        new PostStore.Summary(
            id,
            "제목",
            "발췌",
            nickname,
            CREATED,
            keys.isEmpty() ? null : keys.get(0),
            keys.size(),
            null,
            null,
            0,
            mine),
        "본문",
        keys);
  }

  private static CourseDetail courseDetail(long id) {
    return new CourseDetail(
        id,
        "제주 3일",
        1,
        CourseStatus.UPCOMING,
        CourseOrigin.MARKET,
        0,
        CREATED,
        CREATED,
        List.of(new CourseDay(1, List.of(), 0, 0, 0, TravelBasis.STRAIGHT_LINE)));
  }

  private static String createBody(String title, String body, String keys, Long courseId) {
    return "{\"title\":\""
        + title.replace("\n", "\\n").replace("\t", "\\t")
        + "\",\"body\":\""
        + body.replace("\n", "\\n").replace("\t", "\\t")
        + "\",\"photoKeys\":"
        + keys
        + (courseId == null ? "" : ",\"courseId\":" + courseId)
        + "}";
  }

  private static MockHttpServletRequestBuilder postJson(
      String path, String json, String authorization) {
    MockHttpServletRequestBuilder req =
        post(path).contentType(MediaType.APPLICATION_JSON).content(json);
    if (authorization != null) {
      req.header("Authorization", authorization);
    }
    return req;
  }

  /** 같은 키로 한 시간 전에 서명한 토큰 — 수명(30 분)이 지났다. */
  private static String expiredBearer() {
    return "Bearer "
        + new AccessTokens(
                SECRET, Duration.ofMinutes(30), Clock.fixed(T0.minusSeconds(3600), ZoneOffset.UTC))
            .issue(USER)
            .value();
  }

  /** 다른 키로 서명한 토큰 — 서명 검증에서 떨어진다. */
  private static AccessTokens forgedTokens() {
    return new AccessTokens(
        filled(32, (byte) 8), Duration.ofMinutes(30), Clock.fixed(T0, ZoneOffset.UTC));
  }

  private String bearer() {
    return "Bearer " + tokens.issue(USER).value();
  }

  private static byte[] filled(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }
}
