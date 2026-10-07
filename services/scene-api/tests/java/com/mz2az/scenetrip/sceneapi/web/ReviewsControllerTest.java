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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * 리뷰 창구(계약 tag {@code reviews}, 1.4.0)를 HTTP 로 본다 — 상태 코드·오류 코드·응답 모양.
 *
 * <p>{@link CurrentAccount} · {@link AccessTokens} · {@link ReviewViews} 는 진짜다. 401 의 코드({@code
 * SIGN_IN_REQUIRED} 와 {@code ACCESS_TOKEN_INVALID})가 실제 헤더 처리에서 갈리고, 줄 → 계약 모양 옮김도 실제로 탄다. DB 를 아는
 * {@link ReviewStore} · {@link UserStore} · {@link UploadStore} 와 사진 저장소만 가짜이고, 그 SQL 은 통합
 * 레인({@code ReviewStoreIntegrationTest})이 본다.
 */
@WebMvcTest(ReviewsController.class)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  ReviewViews.class,
  ReviewsControllerTest.Tokens.class
})
@DisplayName("ReviewsController — 리뷰 목록·내 리뷰·쓰기·지우기·사진첩")
class ReviewsControllerTest {

  private static final byte[] SECRET = filled(32, (byte) 7);
  private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");
  private static final UUID USER = UUID.fromString("1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f");
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-01T09:00:00Z");
  private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-10-02T10:30:00Z");

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.fixed(T0, ZoneOffset.UTC));
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private AccessTokens tokens;

  @MockitoBean private ReviewStore store;
  @MockitoBean private PhotoStorage storage;
  @MockitoBean private UploadStore uploads;
  @MockitoBean private UserStore users;

  @BeforeEach
  void setUp() {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(store.exists(any(), anyLong())).thenReturn(true);
    when(store.summary(any(), anyLong()))
        .thenReturn(new ReviewStore.Summary(null, 0, List.of(0, 0, 0, 0, 0)));
    when(store.list(any(), anyLong(), any(), anyInt(), anyInt(), any()))
        .thenReturn(new ReviewStore.Page<>(List.of(), 0));
    when(storage.viewUrl(anyString()))
        .thenAnswer(inv -> URI.create("https://signed.example/" + inv.getArgument(0)));
    when(uploads.unattached(any(), anyCollection())).thenReturn(Set.of());
  }

  // ───────────── GET …/reviews ─────────────

  @Test
  @DisplayName("목록 — 누구나 200. 항목·전체 수·요약(평균·수·분포)을 계약 모양으로 싣는다")
  void listIsPublicAndMapped() throws Exception {
    when(store.list(eq(Target.PLACE), eq(2L), any(), anyInt(), anyInt(), any()))
        .thenReturn(
            new ReviewStore.Page<>(
                List.of(
                    row(11L, 5, "최고였어요", "제주러버", false, List.of("reviews/a.jpg")),
                    row(10L, 3, null, null, false, List.of())),
                7));
    when(store.summary(Target.PLACE, 2L))
        .thenReturn(new ReviewStore.Summary(4.3, 7, List.of(0, 1, 1, 0, 5)));

    mvc.perform(get("/places/2/reviews"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(7))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(11))
        .andExpect(jsonPath("$.items[0].rating").value(5))
        .andExpect(jsonPath("$.items[0].body").value("최고였어요"))
        .andExpect(jsonPath("$.items[0].author.nickname").value("제주러버"))
        .andExpect(jsonPath("$.items[0].isMine").value(false))
        .andExpect(jsonPath("$.items[0].photos[0].key").value("reviews/a.jpg"))
        .andExpect(
            jsonPath("$.items[0].photos[0].url").value("https://signed.example/reviews/a.jpg"))
        .andExpect(jsonPath("$.items[0].createdAt").exists())
        .andExpect(jsonPath("$.items[0].updatedAt").exists())
        // 탈퇴한 작성자 — author 가 null(「탈퇴한 사용자」), 별점만 남긴 리뷰 — body 가 null
        .andExpect(jsonPath("$.items[1].author").doesNotExist())
        .andExpect(jsonPath("$.items[1].body").doesNotExist())
        .andExpect(jsonPath("$.items[1].photos.length()").value(0))
        .andExpect(jsonPath("$.summary.average").value(4.3))
        .andExpect(jsonPath("$.summary.count").value(7))
        .andExpect(jsonPath("$.summary.distribution.length()").value(5))
        .andExpect(jsonPath("$.summary.distribution[0]").value(0))
        .andExpect(jsonPath("$.summary.distribution[4]").value(5));
  }

  @Test
  @DisplayName("목록 — 토큰이 없으면 보는 사람이 없다(null), 기본 정렬은 최신순, limit 20 · offset 0")
  void listWithoutTokenHasNoViewer() throws Exception {
    mvc.perform(get("/places/2/reviews")).andExpect(status().isOk());

    verify(store)
        .list(eq(Target.PLACE), eq(2L), eq(ReviewStore.Sort.RECENT), eq(20), eq(0), isNull());
  }

  @Test
  @DisplayName("목록 — 토큰을 보낸 가입 사용자면 그 계정이 보는 사람으로 넘어간다(isMine 판정)")
  void listWithTokenPassesViewer() throws Exception {
    when(store.list(eq(Target.POI), eq(5L), any(), anyInt(), anyInt(), eq(USER)))
        .thenReturn(new ReviewStore.Page<>(List.of(row(3L, 4, "좋아요", "나", true, List.of())), 1));

    mvc.perform(get("/pois/5/reviews").header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].isMine").value(true));

    verify(store).list(eq(Target.POI), eq(5L), any(), eq(20), eq(0), eq(USER));
  }

  @Test
  @DisplayName("목록 — 만료·위조·형식이 틀린 토큰, 탈퇴한 계정의 토큰은 「로그인하지 않음」: 200 이고 isMine 은 모두 false")
  void listWithBadTokenIsAnonymous() throws Exception {
    // 보는 사람이 USER 로 넘어가면 Store 가 내 리뷰라고 답한다 — 컨트롤러가 null 을 넘겨야 isMine 이 false 다.
    when(store.list(any(), anyLong(), any(), anyInt(), anyInt(), isNull()))
        .thenReturn(new ReviewStore.Page<>(List.of(row(3L, 4, "좋아요", "나", false, List.of())), 1));
    when(store.list(any(), anyLong(), any(), anyInt(), anyInt(), eq(USER)))
        .thenReturn(new ReviewStore.Page<>(List.of(row(3L, 4, "좋아요", "나", true, List.of())), 1));
    String deletedAccount = UUID.randomUUID().toString();
    when(users.touchSignedIn(UUID.fromString(deletedAccount))).thenReturn(false);

    for (String authorization :
        List.of(
            expiredBearer(),
            "Bearer " + forgedTokens().issue(USER).value(),
            "Bearer not-a-jwt",
            "Bearer ",
            "Basic dXNlcjpwYXNz",
            "Bearer " + tokens.issue(UUID.fromString(deletedAccount)).value())) {
      mvc.perform(get("/places/2/reviews").header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(1))
          .andExpect(jsonPath("$.items[0].isMine").value(false));
      mvc.perform(get("/pois/5/reviews").header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].isMine").value(false));
    }

    verify(store, never()).list(any(), anyLong(), any(), anyInt(), anyInt(), eq(USER));
  }

  @Test
  @DisplayName("사진첩 — 만료·위조 토큰이 와도 누구나 보는 창구라 200")
  void galleryWithBadTokenIsOk() throws Exception {
    when(store.photos(any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new ReviewStore.Page<>(List.of(), 0));

    mvc.perform(get("/places/2/photos").header("Authorization", expiredBearer()))
        .andExpect(status().isOk());
    mvc.perform(get("/pois/3/photos").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("토큰이 꼭 필요한 창구는 그대로 401 — 만료면 ACCESS_TOKEN_EXPIRED, 위조면 ACCESS_TOKEN_INVALID")
  void tokenRequiredEndpointsStillRejectBadTokens() throws Exception {
    mvc.perform(get("/places/2/reviews/me").header("Authorization", expiredBearer()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));
    mvc.perform(
            putJson(
                "/pois/2/reviews/me",
                "{\"rating\":5,\"photoKeys\":[]}",
                "Bearer " + forgedTokens().issue(USER).value()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    mvc.perform(delete("/places/2/reviews/me").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    mvc.perform(get("/me/reviews").header("Authorization", expiredBearer()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));

    verifyNeverPut();
    verify(store, never()).delete(any(), anyLong(), any());
  }

  @Test
  @DisplayName("목록 — sort·limit·offset 이 Store 로 그대로 간다")
  void listPassesSortAndPaging() throws Exception {
    mvc.perform(
            get("/places/2/reviews")
                .param("sort", "rating_high")
                .param("limit", "5")
                .param("offset", "10"))
        .andExpect(status().isOk());
    mvc.perform(get("/pois/3/reviews").param("sort", "rating_low")).andExpect(status().isOk());
    mvc.perform(get("/pois/3/reviews").param("sort", "recent")).andExpect(status().isOk());

    verify(store)
        .list(eq(Target.PLACE), eq(2L), eq(ReviewStore.Sort.RATING_HIGH), eq(5), eq(10), any());
    verify(store)
        .list(eq(Target.POI), eq(3L), eq(ReviewStore.Sort.RATING_LOW), eq(20), eq(0), any());
    verify(store).list(eq(Target.POI), eq(3L), eq(ReviewStore.Sort.RECENT), eq(20), eq(0), any());
  }

  @Test
  @DisplayName("목록 — 모르는 sort 는 400")
  void unknownSortIsRejected() throws Exception {
    mvc.perform(get("/places/2/reviews").param("sort", "best"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").exists());
    mvc.perform(get("/pois/2/reviews").param("sort", "rating")).andExpect(status().isBadRequest());

    verify(store, never()).list(any(), anyLong(), any(), anyInt(), anyInt(), any());
  }

  @Test
  @DisplayName("목록 — limit 이 1~100 밖이거나 offset 이 음수면 400")
  void pagingOutOfRangeIsRejected() throws Exception {
    mvc.perform(get("/places/2/reviews").param("limit", "0")).andExpect(status().isBadRequest());
    mvc.perform(get("/places/2/reviews").param("limit", "101")).andExpect(status().isBadRequest());
    mvc.perform(get("/places/2/reviews").param("offset", "-1")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("목록 — 없는 촬영지는 404 PLACE_NOT_FOUND, 없거나 폐업한 편의시설은 404 POI_NOT_FOUND")
  void listOfMissingTargetIsNotFound() throws Exception {
    when(store.exists(Target.PLACE, 999L)).thenReturn(false);
    when(store.exists(Target.POI, 999L)).thenReturn(false);

    mvc.perform(get("/places/999/reviews"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    mvc.perform(get("/pois/999/reviews"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }

  // ───────────── GET …/reviews/me ─────────────

  @Test
  @DisplayName("내 리뷰 — 토큰이 없으면 401 SIGN_IN_REQUIRED")
  void myReviewWithoutTokenIsSignInRequired() throws Exception {
    mvc.perform(get("/places/2/reviews/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(get("/pois/2/reviews/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
  }

  @Test
  @DisplayName("내 리뷰 — 쓴 적이 없으면 404 REVIEW_NOT_FOUND")
  void myReviewMissingIsNotFound() throws Exception {
    when(store.mine(Target.PLACE, 2L, USER)).thenReturn(Optional.empty());

    mvc.perform(get("/places/2/reviews/me").header("Authorization", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
  }

  @Test
  @DisplayName("내 리뷰 — 있으면 200, isMine=true")
  void myReviewFound() throws Exception {
    when(store.mine(Target.POI, 4L, USER))
        .thenReturn(Optional.of(row(21L, 2, "별로", "나", true, List.of())));

    mvc.perform(get("/pois/4/reviews/me").header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(21))
        .andExpect(jsonPath("$.rating").value(2))
        .andExpect(jsonPath("$.isMine").value(true))
        .andExpect(jsonPath("$.author.nickname").value("나"));
  }

  @Test
  @DisplayName("내 리뷰 — 대상이 없으면 404 PLACE_NOT_FOUND / POI_NOT_FOUND")
  void myReviewOfMissingTargetIsNotFound() throws Exception {
    when(store.exists(Target.PLACE, 999L)).thenReturn(false);
    when(store.exists(Target.POI, 999L)).thenReturn(false);

    mvc.perform(get("/places/999/reviews/me").header("Authorization", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    mvc.perform(get("/pois/999/reviews/me").header("Authorization", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }

  // ───────────── PUT …/reviews/me ─────────────

  @Test
  @DisplayName("쓰기 — 토큰이 없으면 401 SIGN_IN_REQUIRED 이고 저장하지 않는다")
  void putWithoutTokenIsSignInRequired() throws Exception {
    mvc.perform(putJson("/places/2/reviews/me", "{\"rating\":5,\"photoKeys\":[]}", null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(putJson("/pois/2/reviews/me", "{\"rating\":5,\"photoKeys\":[]}", null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verifyNeverPut();
  }

  @Test
  @DisplayName("쓰기 — 200, 저장된 리뷰를 돌려준다. 별점·글·사진 키가 그대로 Store 로 간다")
  void putSavesAndReturnsReview() throws Exception {
    when(store.put(eq(Target.PLACE), eq(2L), eq(USER), eq(4), any(), anyList(), anyCollection()))
        .thenReturn(row(30L, 4, "좋았어요", "나", true, List.of()));

    mvc.perform(
            putJson(
                "/places/2/reviews/me",
                "{\"rating\":4,\"body\":\"좋았어요\",\"photoKeys\":[]}",
                bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(30))
        .andExpect(jsonPath("$.rating").value(4))
        .andExpect(jsonPath("$.body").value("좋았어요"))
        .andExpect(jsonPath("$.isMine").value(true));

    verify(store)
        .put(eq(Target.PLACE), eq(2L), eq(USER), eq(4), eq("좋았어요"), eq(List.of()), anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 글의 앞뒤 공백을 떼고, 공백뿐이거나 없으면 null 로 저장한다")
  void putTrimsBody() throws Exception {
    when(store.put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection()))
        .thenReturn(row(30L, 5, null, "나", true, List.of()));

    mvc.perform(
            putJson(
                "/pois/3/reviews/me",
                "{\"rating\":5,\"body\":\"  맛집  \\n\",\"photoKeys\":[]}",
                bearer()))
        .andExpect(status().isOk());
    mvc.perform(
            putJson(
                "/pois/3/reviews/me", "{\"rating\":5,\"body\":\"   \",\"photoKeys\":[]}", bearer()))
        .andExpect(status().isOk());
    mvc.perform(putJson("/pois/3/reviews/me", "{\"rating\":5,\"photoKeys\":[]}", bearer()))
        .andExpect(status().isOk());

    ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
    verify(store, org.mockito.Mockito.times(3))
        .put(eq(Target.POI), eq(3L), eq(USER), eq(5), bodies.capture(), anyList(), anyCollection());
    assertThat(bodies.getAllValues()).containsExactly("맛집", null, null);
  }

  @Test
  @DisplayName("쓰기 — 별점이 1~5 밖이거나 없으면 400, 저장하지 않는다")
  void putRatingOutOfRangeIsRejected() throws Exception {
    for (String json :
        List.of(
            "{\"rating\":0,\"photoKeys\":[]}",
            "{\"rating\":6,\"photoKeys\":[]}",
            "{\"photoKeys\":[]}")) {
      mvc.perform(putJson("/places/2/reviews/me", json, bearer()))
          .andExpect(status().isBadRequest());
    }
    verifyNeverPut();
  }

  @Test
  @DisplayName("쓰기 — 글이 2000 자를 넘으면 400, 2000 자는 받는다")
  void putBodyLengthLimit() throws Exception {
    when(store.put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection()))
        .thenReturn(row(30L, 5, "x", "나", true, List.of()));

    mvc.perform(
            putJson(
                "/places/2/reviews/me",
                "{\"rating\":5,\"body\":\"" + "가".repeat(2001) + "\",\"photoKeys\":[]}",
                bearer()))
        .andExpect(status().isBadRequest());
    verifyNeverPut();

    mvc.perform(
            putJson(
                "/places/2/reviews/me",
                "{\"rating\":5,\"body\":\"" + "가".repeat(2000) + "\",\"photoKeys\":[]}",
                bearer()))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("쓰기 — photoKeys 가 없거나 11 장이면 400")
  void putPhotoKeysShapeIsValidated() throws Exception {
    mvc.perform(putJson("/places/2/reviews/me", "{\"rating\":5}", bearer()))
        .andExpect(status().isBadRequest());
    StringBuilder eleven = new StringBuilder();
    for (int i = 0; i < 11; i++) {
      eleven.append(i == 0 ? "" : ",").append("\"k").append(i).append('"');
    }
    mvc.perform(
            putJson(
                "/places/2/reviews/me", "{\"rating\":5,\"photoKeys\":[" + eleven + "]}", bearer()))
        .andExpect(status().isBadRequest());

    verifyNeverPut();
  }

  @Test
  @DisplayName("쓰기 — Store 가 사진 키를 거절하면 400 REVIEW_PHOTO_INVALID")
  void putWithRejectedPhotoIsPhotoInvalid() throws Exception {
    when(store.put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection()))
        .thenThrow(ReviewStore.PhotoKeyRejectedException.class);

    mvc.perform(
            putJson(
                "/pois/3/reviews/me",
                "{\"rating\":5,\"photoKeys\":[\"uploads/tmp/not-mine.jpg\"]}",
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));
  }

  @Test
  @DisplayName(
      "쓰기 — 이 사용자가 받은 새 키(uploads/tmp/)는 reviews/ 로 옮겨 그 키로 저장하고, 이미 붙은 키는 그대로 — 보낸 순서대로."
          + " 저장 뒤 올리기 기록을 지운다")
  void putMovesFreshUploadsAndConsumesThem() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection()))
        .thenReturn(Set.of("uploads/tmp/a.jpg", "uploads/tmp/c.png"));
    when(store.put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection()))
        .thenReturn(
            row(
                30L,
                5,
                null,
                "나",
                true,
                List.of("reviews/a.jpg", "reviews/b.jpg", "reviews/c.png")));

    mvc.perform(
            putJson(
                "/places/2/reviews/me",
                "{\"rating\":5,\"photoKeys\":[\"uploads/tmp/a.jpg\",\"reviews/b.jpg\",\"uploads/tmp/c.png\"]}",
                bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photos.length()").value(3))
        .andExpect(jsonPath("$.photos[0].key").value("reviews/a.jpg"))
        .andExpect(jsonPath("$.photos[0].url").value("https://signed.example/reviews/a.jpg"))
        .andExpect(jsonPath("$.photos[1].key").value("reviews/b.jpg"))
        .andExpect(jsonPath("$.photos[2].key").value("reviews/c.png"));

    verify(uploads)
        .unattached(
            eq(USER), eq(List.of("uploads/tmp/a.jpg", "reviews/b.jpg", "uploads/tmp/c.png")));
    verify(storage).move("uploads/tmp/a.jpg", "reviews/a.jpg");
    verify(storage).move("uploads/tmp/c.png", "reviews/c.png");
    verify(storage, never()).move(eq("reviews/b.jpg"), anyString());
    verify(store)
        .put(
            eq(Target.PLACE),
            eq(2L),
            eq(USER),
            eq(5),
            isNull(),
            eq(List.of("reviews/a.jpg", "reviews/b.jpg", "reviews/c.png")),
            eq(Set.of("reviews/a.jpg", "reviews/c.png")));
    verify(uploads).consume(Set.of("uploads/tmp/a.jpg", "uploads/tmp/c.png"));
  }

  @Test
  @DisplayName("쓰기 — 이 사용자가 받지 않은 키(남의 것·만료·모르는 것)는 옮기지 않고 그대로 Store 에 넘겨 Store 가 가린다")
  void putPassesUnknownKeysThroughUnmoved() throws Exception {
    when(store.put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection()))
        .thenThrow(ReviewStore.PhotoKeyRejectedException.class);

    mvc.perform(
            putJson(
                "/pois/3/reviews/me",
                "{\"rating\":5,\"photoKeys\":[\"uploads/tmp/someone-else.jpg\"]}",
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));

    verify(storage, never()).move(anyString(), anyString());
    verify(store)
        .put(
            eq(Target.POI),
            eq(3L),
            eq(USER),
            eq(5),
            isNull(),
            eq(List.of("uploads/tmp/someone-else.jpg")),
            eq(Set.of()));
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 받은 키인데 저장소에 파일이 없으면 400 REVIEW_PHOTO_INVALID, 저장하지 않고 기록도 지우지 않는다")
  void putWithMissingUploadedFileIsPhotoInvalid() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection())).thenReturn(Set.of("uploads/tmp/a.jpg"));
    org.mockito.Mockito.doThrow(new PhotoStorage.MissingPhotoException("uploads/tmp/a.jpg"))
        .when(storage)
        .move("uploads/tmp/a.jpg", "reviews/a.jpg");

    mvc.perform(
            putJson(
                "/places/2/reviews/me",
                "{\"rating\":5,\"photoKeys\":[\"uploads/tmp/a.jpg\"]}",
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));

    verifyNeverPut();
    verify(storage, org.mockito.Mockito.times(1)).move(anyString(), anyString());
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — Store 가 거절하면 옮긴 사진을 원래 임시 키로 되돌리고, 올리기 기록을 지우지 않는다")
  void putRejectedByStoreMovesBackAndDoesNotConsume() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection()))
        .thenReturn(Set.of("uploads/tmp/a.jpg", "uploads/tmp/c.png"));
    when(store.put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection()))
        .thenThrow(ReviewStore.PhotoKeyRejectedException.class);

    mvc.perform(
            putJson(
                "/places/2/reviews/me",
                "{\"rating\":5,\"photoKeys\":[\"uploads/tmp/a.jpg\",\"reviews/x.jpg\",\"uploads/tmp/c.png\"]}",
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(storage, store);
    order.verify(storage).move("uploads/tmp/a.jpg", "reviews/a.jpg");
    order.verify(storage).move("uploads/tmp/c.png", "reviews/c.png");
    order.verify(store).put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection());
    verify(storage).move("reviews/a.jpg", "uploads/tmp/a.jpg");
    verify(storage).move("reviews/c.png", "uploads/tmp/c.png");
    verify(storage, never()).move(eq("reviews/x.jpg"), anyString());
    verify(storage, org.mockito.Mockito.times(4)).move(anyString(), anyString());
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 뒤 사진이 저장소에 없으면 앞에서 옮긴 사진을 되돌리고 400, 저장하지 않는다")
  void putWithLaterMissingFileMovesBackEarlierOnes() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection()))
        .thenReturn(Set.of("uploads/tmp/a.jpg", "uploads/tmp/b.jpg"));
    org.mockito.Mockito.doThrow(new PhotoStorage.MissingPhotoException("uploads/tmp/b.jpg"))
        .when(storage)
        .move("uploads/tmp/b.jpg", "reviews/b.jpg");

    mvc.perform(
            putJson(
                "/pois/3/reviews/me",
                "{\"rating\":5,\"photoKeys\":[\"uploads/tmp/a.jpg\",\"uploads/tmp/b.jpg\"]}",
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(storage);
    order.verify(storage).move("uploads/tmp/a.jpg", "reviews/a.jpg");
    order.verify(storage).move("uploads/tmp/b.jpg", "reviews/b.jpg");
    order.verify(storage).move("reviews/a.jpg", "uploads/tmp/a.jpg");
    verify(storage, never()).move("reviews/b.jpg", "uploads/tmp/b.jpg");
    verifyNeverPut();
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 되돌리기가 실패해도 무시하고 400 REVIEW_PHOTO_INVALID, 나머지도 되돌린다")
  void putMoveBackFailureIsIgnored() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection()))
        .thenReturn(Set.of("uploads/tmp/a.jpg", "uploads/tmp/b.jpg"));
    when(store.put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection()))
        .thenThrow(ReviewStore.PhotoKeyRejectedException.class);
    org.mockito.Mockito.doThrow(new RuntimeException("storage down"))
        .when(storage)
        .move("reviews/a.jpg", "uploads/tmp/a.jpg");

    mvc.perform(
            putJson(
                "/places/2/reviews/me",
                "{\"rating\":5,\"photoKeys\":[\"uploads/tmp/a.jpg\",\"uploads/tmp/b.jpg\"]}",
                bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));

    verify(storage).move("reviews/a.jpg", "uploads/tmp/a.jpg");
    verify(storage).move("reviews/b.jpg", "uploads/tmp/b.jpg");
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 같은 키가 두 번이면 아무것도 옮기기 전에 400 REVIEW_PHOTO_INVALID")
  void putDuplicateKeysRejectedBeforeAnyMove() throws Exception {
    when(uploads.unattached(eq(USER), anyCollection())).thenReturn(Set.of("uploads/tmp/a.jpg"));

    for (String keys :
        List.of(
            "[\"uploads/tmp/a.jpg\",\"uploads/tmp/a.jpg\"]",
            "[\"reviews/x.jpg\",\"uploads/tmp/a.jpg\",\"reviews/x.jpg\"]")) {
      mvc.perform(
              putJson(
                  "/places/2/reviews/me", "{\"rating\":5,\"photoKeys\":" + keys + "}", bearer()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));
    }

    verify(storage, never()).move(anyString(), anyString());
    verifyNeverPut();
    verify(uploads, never()).consume(anyCollection());
  }

  @Test
  @DisplayName("쓰기 — 없는 촬영지는 404 PLACE_NOT_FOUND, 없거나 폐업한 편의시설은 404 POI_NOT_FOUND, 저장하지 않는다")
  void putToMissingTargetIsNotFound() throws Exception {
    when(store.exists(Target.PLACE, 999L)).thenReturn(false);
    when(store.exists(Target.POI, 999L)).thenReturn(false);

    mvc.perform(putJson("/places/999/reviews/me", "{\"rating\":5,\"photoKeys\":[]}", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    mvc.perform(putJson("/pois/999/reviews/me", "{\"rating\":5,\"photoKeys\":[]}", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));

    verifyNeverPut();
  }

  // ───────────── DELETE …/reviews/me ─────────────

  @Test
  @DisplayName("지우기 — 204. 쓴 적이 없어도 204")
  void deleteIsNoContentEvenIfNone() throws Exception {
    when(store.delete(Target.PLACE, 2L, USER)).thenReturn(true);
    when(store.delete(Target.POI, 3L, USER)).thenReturn(false);

    mvc.perform(delete("/places/2/reviews/me").header("Authorization", bearer()))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/pois/3/reviews/me").header("Authorization", bearer()))
        .andExpect(status().isNoContent());

    verify(store).delete(Target.PLACE, 2L, USER);
    verify(store).delete(Target.POI, 3L, USER);
  }

  @Test
  @DisplayName("지우기 — 토큰이 없으면 401 SIGN_IN_REQUIRED, 대상이 없으면 404")
  void deleteRejections() throws Exception {
    when(store.exists(Target.POI, 999L)).thenReturn(false);

    mvc.perform(delete("/places/2/reviews/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(delete("/pois/999/reviews/me").header("Authorization", bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));

    verify(store, never()).delete(any(), anyLong(), any());
  }

  // ───────────── GET /me/reviews ─────────────

  @Test
  @DisplayName("내가 쓴 리뷰 — 토큰이 없으면 401")
  void myReviewsWithoutTokenIsUnauthorized() throws Exception {
    mvc.perform(get("/me/reviews")).andExpect(status().isUnauthorized());

    verify(store, never()).listMine(any(), any(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("내가 쓴 리뷰 — 촬영지·편의시설이 섞여 target{type,id,name} 을 달고 나온다. 요청 언어가 Store 로 간다")
  void myReviewsCarryTarget() throws Exception {
    when(store.listMine(USER, "ja", 20, 0))
        .thenReturn(
            new ReviewStore.Page<>(
                List.of(
                    new ReviewStore.MineRow(
                        row(41L, 5, "또 갈래요", "나", true, List.of()), Target.POI, 77L, "모슬포호텔"),
                    new ReviewStore.MineRow(
                        row(40L, 4, null, "나", true, List.of()), Target.PLACE, 2L, "北村韓屋村")),
                2));

    mvc.perform(
            get("/me/reviews").header("Authorization", bearer()).header("Accept-Language", "ja"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].id").value(41))
        .andExpect(jsonPath("$.items[0].target.type").value("poi"))
        .andExpect(jsonPath("$.items[0].target.id").value(77))
        .andExpect(jsonPath("$.items[0].target.name").value("모슬포호텔"))
        .andExpect(jsonPath("$.items[0].isMine").value(true))
        .andExpect(jsonPath("$.items[1].target.type").value("place"))
        .andExpect(jsonPath("$.items[1].target.id").value(2))
        .andExpect(jsonPath("$.items[1].target.name").value("北村韓屋村"));
  }

  @Test
  @DisplayName("내가 쓴 리뷰 — Accept-Language 가 없으면 en, limit·offset 이 Store 로 간다")
  void myReviewsDefaultLanguage() throws Exception {
    when(store.listMine(any(), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewStore.Page<>(List.of(), 0));

    mvc.perform(
            get("/me/reviews")
                .header("Authorization", bearer())
                .param("limit", "5")
                .param("offset", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.total").value(0));

    verify(store).listMine(USER, "en", 5, 5);
  }

  // ───────────── GET …/photos ─────────────

  @Test
  @DisplayName("사진첩 — 우리 사진은 주소·저작자 그대로 official, 리뷰 사진은 서명 주소와 reviewId 를 단 review")
  void galleryMapsBothKinds() throws Exception {
    when(store.photos(Target.POI, 3L, 20, 0))
        .thenReturn(
            new ReviewStore.Page<>(
                List.of(
                    new ReviewStore.Photo("https://img.example/o.jpg", null, null, "한국관광공사"),
                    new ReviewStore.Photo(null, "reviews/r1.jpg", 55L, null)),
                23));

    mvc.perform(get("/pois/3/photos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(23))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].source").value("official"))
        .andExpect(jsonPath("$.items[0].url").value("https://img.example/o.jpg"))
        .andExpect(jsonPath("$.items[0].credit").value("한국관광공사"))
        .andExpect(jsonPath("$.items[0].reviewId").doesNotExist())
        .andExpect(jsonPath("$.items[1].source").value("review"))
        .andExpect(jsonPath("$.items[1].url").value("https://signed.example/reviews/r1.jpg"))
        .andExpect(jsonPath("$.items[1].reviewId").value(55));
  }

  @Test
  @DisplayName("사진첩 — limit·offset 이 Store 로 가고, 대상이 없으면 404")
  void galleryPagingAndMissingTarget() throws Exception {
    when(store.photos(any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new ReviewStore.Page<>(List.of(), 0));
    when(store.exists(Target.PLACE, 999L)).thenReturn(false);

    mvc.perform(get("/places/2/photos").param("limit", "10").param("offset", "20"))
        .andExpect(status().isOk());
    verify(store).photos(Target.PLACE, 2L, 10, 20);

    mvc.perform(get("/places/999/photos"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
  }

  // ───────────── 도우미 ─────────────

  private void verifyNeverPut() {
    verify(store, never())
        .put(any(), anyLong(), any(), anyInt(), any(), anyList(), anyCollection());
  }

  private static ReviewStore.Row row(
      long id, int rating, String body, String nickname, boolean mine, List<String> keys) {
    return new ReviewStore.Row(id, rating, body, nickname, CREATED, UPDATED, mine, keys);
  }

  private static MockHttpServletRequestBuilder putJson(
      String path, String json, String authorization) {
    MockHttpServletRequestBuilder req =
        put(path).contentType(MediaType.APPLICATION_JSON).content(json);
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

  /** 다른 키로 서명한 토큰을 만드는 것 — 서명 검증에서 떨어진다. */
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
