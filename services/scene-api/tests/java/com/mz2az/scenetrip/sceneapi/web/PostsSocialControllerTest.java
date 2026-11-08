package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 커뮤니티 보강(계약 1.24.0, 계획 {@code community-social.md} §2·§4 단위 갈래) — 좋아요·댓글의 인증 규칙, 목록의 {@code
 * q}·{@code cursor}·{@code board} 입력 검사, 댓글 본문 길이, 댓글 지우기의 404 코드를 HTTP 로 본다.
 *
 * <p>{@link CurrentAccount} · {@link AccessTokens} 는 진짜다. DB 를 아는 Store 들은 가짜이고 따로 길들이지 않는다 —
 * Mockito 의 기본 답(false·0·빈 Optional·빈 목록)이 곧 「없다」 이다. 실제 SQL 은 통합 레인({@code PostSocialApiTest})이
 * 본다.
 */
@WebMvcTest(PostsController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, PostsSocialControllerTest.Tokens.class})
@DisplayName("PostsController — 좋아요·댓글·게시판·검색·커서 (1.24.0)")
class PostsSocialControllerTest {

  private static final byte[] SECRET = filled(32, (byte) 7);
  private static final Instant T0 = Instant.parse("2026-11-09T00:00:00Z");
  private static final UUID USER = UUID.fromString("2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d");
  private static final String INSTALL_ID = "7e8f9a0b-1c2d-4e3f-8a5b-6c7d8e9f0a1b";
  private static final UUID GUEST = UUID.fromString("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e");

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
  }

  // ───────────── 좋아요 — 로그인한 사용자만 ─────────────

  @Test
  @DisplayName("좋아요 PUT — 토큰이 없으면 401 SIGN_IN_REQUIRED, 설치 UUID 만 있어도 401 SIGN_IN_REQUIRED")
  void likeRequiresSignIn() throws Exception {
    mvc.perform(put("/posts/3/like"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(put("/posts/3/like").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
  }

  @Test
  @DisplayName("좋아요 DELETE — 토큰이 없으면 401 SIGN_IN_REQUIRED, 설치 UUID 만 있어도 401 SIGN_IN_REQUIRED")
  void unlikeRequiresSignIn() throws Exception {
    mvc.perform(delete("/posts/3/like"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(delete("/posts/3/like").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
  }

  // ───────────── 댓글 — 쓰기·지우기는 가입한 사용자만 ─────────────

  @Test
  @DisplayName("댓글 쓰기 — 토큰이 없으면 401 SIGN_IN_REQUIRED, 설치 UUID 만 있어도 401 SIGN_IN_REQUIRED")
  void createCommentRequiresMember() throws Exception {
    mvc.perform(commentJson(3, "{\"body\":\"좋은 글이네요\"}", null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(commentJson(3, "{\"body\":\"좋은 글이네요\"}", null).header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
  }

  @Test
  @DisplayName("댓글 지우기 — 토큰이 없으면 401 SIGN_IN_REQUIRED")
  void deleteCommentRequiresMember() throws Exception {
    mvc.perform(delete("/posts/3/comments/11"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(delete("/posts/3/comments/11").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
  }

  @Test
  @DisplayName("댓글 쓰기 — 본문이 비었거나 공백뿐이면 400 INVALID_PARAMETER")
  void blankCommentBody() throws Exception {
    for (String json :
        List.of("{\"body\":\"\"}", "{\"body\":\"   \"}", "{\"body\":\" \\n\\t \"}")) {
      mvc.perform(commentJson(3, json, bearer()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
  }

  @Test
  @DisplayName("댓글 쓰기 — 501 자는 400 INVALID_PARAMETER (1~500자)")
  void tooLongCommentBody() throws Exception {
    mvc.perform(commentJson(3, "{\"body\":\"" + "가".repeat(501) + "\"}", bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("댓글 지우기 — Store 가 「없다」(남의 것·없는 것)고 하면 404 COMMENT_NOT_FOUND")
  void deleteMissingCommentIs404() throws Exception {
    mvc.perform(delete("/posts/3/comments/11").header("Authorization", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
  }

  // ───────────── GET /posts — q · cursor · board ─────────────

  @Test
  @DisplayName("목록 q — 앞뒤 공백을 지운 뒤 1자면 400 INVALID_PARAMETER")
  void shortQueryIsRejected() throws Exception {
    for (String q : List.of("a", "제", "  a  ", "   ")) {
      mvc.perform(get("/posts").param("q", q))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
  }

  @Test
  @DisplayName("목록 — cursor 와 offset 을 함께 주면 400 INVALID_PARAMETER")
  void cursorWithOffsetIsRejected() throws Exception {
    mvc.perform(get("/posts").param("cursor", "abc").param("offset", "20"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("목록 — 서버가 만들지 않은(풀 수 없는) cursor 는 400 INVALID_PARAMETER")
  void garbageCursorIsRejected() throws Exception {
    for (String cursor : List.of("not-a-cursor", "%%%", "eyJ4IjoxfQ")) {
      mvc.perform(get("/posts").param("cursor", cursor))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
  }

  @Test
  @DisplayName("목록 — board 가 review·free 가 아니면 400 INVALID_PARAMETER")
  void unknownBoardIsRejected() throws Exception {
    mvc.perform(get("/posts").param("board", "market"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  // ───────────── 도우미 ─────────────

  private static MockHttpServletRequestBuilder commentJson(
      long postId, String json, String authorization) {
    MockHttpServletRequestBuilder req =
        post("/posts/{id}/comments", postId).contentType(MediaType.APPLICATION_JSON).content(json);
    if (authorization != null) {
      req.header("Authorization", authorization);
    }
    return req;
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
