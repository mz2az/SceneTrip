package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 리뷰 사진의 작은 판 칸(계약 1.29.0 {@code ReviewPhoto.thumbnailUrl}·{@code Photo.thumbnailUrl}, MZ2AZ-561) —
 * 계획 {@code photo-thumbnails.md} §2-4·§5.
 *
 * <p>명세에서 나온 기대: 리뷰 사진 줄에 작은 사진 키가 있으면 {@code thumbnailUrl} 은 그 키의 서명 주소, 없으면 칸이 없다(null — 응답은 null
 * 인 선택 칸을 내보내지 않는다) — 앱은 {@code url} 을 쓴다. {@code url} 은 언제나 원본이다. 사진첩의 리뷰 사진도 같다.
 *
 * <p>Store 의 새 모양(계획의 용어로 정한 공개 서명): {@code ReviewStore.Row} 끝에 {@code List<String>
 * photoThumbnailKeys} ({@code photoKeys} 와 같은 길이·순서, 항목 nullable), {@code ReviewStore.Photo} 끝에
 * {@code String thumbnailKey}.
 */
@WebMvcTest(ReviewsController.class)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  ReviewViews.class,
  ReviewsThumbnailControllerTest.Tokens.class
})
@DisplayName("ReviewsController — 리뷰 사진의 작은 판 (1.29.0)")
class ReviewsThumbnailControllerTest {

  private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-11-01T09:00:00Z");

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      byte[] secret = new byte[32];
      Arrays.fill(secret, (byte) 7);
      return new AccessTokens(secret, Duration.ofMinutes(30), Clock.fixed(T0, ZoneOffset.UTC));
    }
  }

  @Autowired private MockMvc mvc;

  @MockitoBean private ReviewStore store;
  @MockitoBean private PhotoStorage storage;
  @MockitoBean private UploadStore uploads;
  @MockitoBean private UserStore users;

  @BeforeEach
  void setUp() {
    when(store.exists(any(), anyLong())).thenReturn(true);
    when(store.summary(any(), anyLong()))
        .thenReturn(new ReviewStore.Summary(null, 0, List.of(0, 0, 0, 0, 0)));
    when(store.list(any(), anyLong(), any(), anyInt(), anyInt(), any()))
        .thenReturn(new ReviewStore.Page<>(List.of(), 0));
    when(storage.viewUrl(anyString()))
        .thenAnswer(inv -> URI.create("https://signed.example/" + inv.getArgument(0)));
  }

  @Test
  @DisplayName("목록 — 작은 사진 키가 있으면 thumbnailUrl 은 그 서명 주소, 없으면 칸이 없다. url 은 원본 그대로")
  void listExposesThumbnailOrNothing() throws Exception {
    ReviewStore.Row row =
        new ReviewStore.Row(
            11L,
            5,
            "좋았다",
            "제주러버",
            CREATED,
            CREATED,
            false,
            List.of("reviews/a.jpg", "reviews/b.jpg"),
            Arrays.asList("reviews/thumb-720/a.jpg", null));
    when(store.list(eq(Target.PLACE), eq(2L), any(), anyInt(), anyInt(), any()))
        .thenReturn(new ReviewStore.Page<>(List.of(row), 1));

    mvc.perform(get("/places/2/reviews"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].photos.length()").value(2))
        .andExpect(jsonPath("$.items[0].photos[0].key").value("reviews/a.jpg"))
        .andExpect(
            jsonPath("$.items[0].photos[0].url").value("https://signed.example/reviews/a.jpg"))
        .andExpect(
            jsonPath("$.items[0].photos[0].thumbnailUrl")
                .value("https://signed.example/reviews/thumb-720/a.jpg"))
        .andExpect(
            jsonPath("$.items[0].photos[1].url").value("https://signed.example/reviews/b.jpg"))
        .andExpect(jsonPath("$.items[0].photos[1].thumbnailUrl").doesNotExist());
  }

  @Test
  @DisplayName("목록 — 작은 사진이 하나도 없는 옛 리뷰는 thumbnailUrl 칸이 모두 없다")
  void listWithoutAnyThumbnail() throws Exception {
    ReviewStore.Row row =
        new ReviewStore.Row(
            12L,
            4,
            null,
            null,
            CREATED,
            CREATED,
            false,
            List.of("reviews/c.jpg"),
            Arrays.asList((String) null));
    when(store.list(eq(Target.POI), eq(3L), any(), anyInt(), anyInt(), any()))
        .thenReturn(new ReviewStore.Page<>(List.of(row), 1));

    mvc.perform(get("/pois/3/reviews"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].photos[0].url").value("https://signed.example/reviews/c.jpg"))
        .andExpect(jsonPath("$.items[0].photos[0].thumbnailUrl").doesNotExist());
  }

  @Test
  @DisplayName("사진첩 — 리뷰 사진도 thumbnailUrl 을 채운다(작은 사진 키의 서명 주소). 없으면 칸이 없다")
  void galleryReviewPhotoThumbnail() throws Exception {
    when(store.photos(Target.PLACE, 2L, 20, 0))
        .thenReturn(
            new ReviewStore.Page<>(
                List.of(
                    new ReviewStore.Photo(
                        null, "reviews/r1.jpg", 55L, null, null, null, "reviews/thumb-720/r1.jpg"),
                    new ReviewStore.Photo(null, "reviews/r2.jpg", 56L, null, null, null, null),
                    new ReviewStore.Photo(
                        "https://img.example/o.jpg",
                        null,
                        null,
                        "한국관광공사",
                        "https://img.example/o_small.jpg",
                        "kogl_type1",
                        null)),
                3));

    mvc.perform(get("/places/2/photos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].source").value("review"))
        .andExpect(jsonPath("$.items[0].url").value("https://signed.example/reviews/r1.jpg"))
        .andExpect(
            jsonPath("$.items[0].thumbnailUrl")
                .value("https://signed.example/reviews/thumb-720/r1.jpg"))
        .andExpect(jsonPath("$.items[1].url").value("https://signed.example/reviews/r2.jpg"))
        .andExpect(jsonPath("$.items[1].thumbnailUrl").doesNotExist())
        // 우리 사진은 원래대로 — 표의 thumbnail_url 을 그대로
        .andExpect(jsonPath("$.items[2].url").value("https://img.example/o.jpg"))
        .andExpect(jsonPath("$.items[2].thumbnailUrl").value("https://img.example/o_small.jpg"));
  }
}
