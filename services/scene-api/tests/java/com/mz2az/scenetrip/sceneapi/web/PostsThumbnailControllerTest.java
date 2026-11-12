package com.mz2az.scenetrip.sceneapi.web;

import static org.hamcrest.Matchers.nullValue;
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
import java.util.List;
import java.util.Optional;
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
 * 여행후기 사진의 작은 판 칸(계약 1.29.0 {@code TripPostSummary.coverThumbnailUrl}·{@code
 * TripPostDetail.photoThumbnailUrls}, MZ2AZ-561) — 계획 {@code photo-thumbnails.md} §2-4·§5.
 *
 * <p>명세에서 나온 기대: 목록 줄은 첫 사진의 작은 사진 키가 있으면 {@code coverThumbnailUrl} 이 그 서명 주소, 없으면 칸이 없다({@code
 * coverPhotoUrl} 은 원본 그대로). 상세의 {@code photoThumbnailUrls} 는 {@code photoUrls} 와 같은 길이·같은 순서이고, 작은
 * 사진이 없는 자리는 null 이다.
 *
 * <p>Store 의 새 모양(계획의 용어로 정한 공개 서명): {@code PostStore.Summary} 끝에 {@code String coverThumbnailKey},
 * {@code PostStore.Detail} 끝에 {@code List<String> photoThumbnailKeys}({@code photoKeys} 와 같은 길이·순서,
 * 항목 nullable).
 */
@WebMvcTest(PostsController.class)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  PostsThumbnailControllerTest.Tokens.class
})
@DisplayName("PostsController — 후기 사진의 작은 판 (1.29.0)")
class PostsThumbnailControllerTest {

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

  @MockitoBean private PostStore store;
  @MockitoBean private CourseStore courses;
  @MockitoBean private PhotoStorage storage;
  @MockitoBean private UploadStore uploads;
  @MockitoBean private UserStore users;

  @BeforeEach
  void setUp() {
    when(storage.viewUrl(anyString()))
        .thenAnswer(inv -> URI.create("https://signed.example/" + inv.getArgument(0)));
    when(store.list(any(), any(), anyInt(), anyInt()))
        .thenReturn(new PostStore.Page<>(List.of(), 0));
    when(store.find(anyLong(), any())).thenReturn(Optional.empty());
    when(store.stops(anyLong(), anyString())).thenReturn(List.of());
  }

  @Test
  @DisplayName("목록 — 대표 사진의 작은 사진 키가 있으면 coverThumbnailUrl 은 그 서명 주소, coverPhotoUrl 은 원본 그대로")
  void listCoverThumbnail() throws Exception {
    when(store.list(any(), any(), anyInt(), anyInt()))
        .thenReturn(
            new PostStore.Page<>(
                List.of(
                    summary(8L, "posts/cover.jpg", "posts/thumb-720/cover.jpg", 3),
                    summary(7L, "posts/old.jpg", null, 1),
                    summary(6L, null, null, 0)),
                3));

    mvc.perform(get("/posts"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].coverPhotoUrl").value("https://signed.example/posts/cover.jpg"))
        .andExpect(
            jsonPath("$.items[0].coverThumbnailUrl")
                .value("https://signed.example/posts/thumb-720/cover.jpg"))
        // 작은 사진이 아직 없다 — 칸이 없고, 앱은 coverPhotoUrl 을 쓴다
        .andExpect(
            jsonPath("$.items[1].coverPhotoUrl").value("https://signed.example/posts/old.jpg"))
        .andExpect(jsonPath("$.items[1].coverThumbnailUrl").doesNotExist())
        // 사진 없는 글 — 둘 다 없다
        .andExpect(jsonPath("$.items[2].coverPhotoUrl").doesNotExist())
        .andExpect(jsonPath("$.items[2].coverThumbnailUrl").doesNotExist());
  }

  @Test
  @DisplayName("상세 — photoThumbnailUrls 는 photoUrls 와 같은 길이·순서, 작은 사진이 없는 자리는 null")
  void detailThumbnailsAlignWithPhotos() throws Exception {
    PostStore.Summary head = summary(3L, "posts/1.jpg", "posts/thumb-720/1.jpg", 3);
    when(store.find(eq(3L), any()))
        .thenReturn(
            Optional.of(
                new PostStore.Detail(
                    head,
                    "본문",
                    List.of("posts/1.jpg", "posts/2.jpg", "posts/3.jpg"),
                    null,
                    Arrays.asList("posts/thumb-720/1.jpg", null, "posts/thumb-720/3.jpg"))));

    mvc.perform(get("/posts/3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoUrls.length()").value(3))
        .andExpect(jsonPath("$.photoUrls[0]").value("https://signed.example/posts/1.jpg"))
        .andExpect(jsonPath("$.photoUrls[1]").value("https://signed.example/posts/2.jpg"))
        .andExpect(jsonPath("$.photoThumbnailUrls.length()").value(3))
        .andExpect(
            jsonPath("$.photoThumbnailUrls[0]")
                .value("https://signed.example/posts/thumb-720/1.jpg"))
        .andExpect(jsonPath("$.photoThumbnailUrls[1]").value(nullValue()))
        .andExpect(
            jsonPath("$.photoThumbnailUrls[2]")
                .value("https://signed.example/posts/thumb-720/3.jpg"));
  }

  @Test
  @DisplayName("상세 — 작은 사진이 하나도 없으면 photoUrls 길이만큼 null")
  void detailWithoutThumbnails() throws Exception {
    PostStore.Summary head = summary(4L, "posts/a.jpg", null, 2);
    when(store.find(eq(4L), any()))
        .thenReturn(
            Optional.of(
                new PostStore.Detail(
                    head,
                    "본문",
                    List.of("posts/a.jpg", "posts/b.jpg"),
                    null,
                    Arrays.<String>asList(null, null))));

    mvc.perform(get("/posts/4"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoUrls.length()").value(2))
        .andExpect(jsonPath("$.photoThumbnailUrls.length()").value(2))
        .andExpect(jsonPath("$.photoThumbnailUrls[0]").value(nullValue()))
        .andExpect(jsonPath("$.photoThumbnailUrls[1]").value(nullValue()));
  }

  private static PostStore.Summary summary(
      long id, String coverKey, String coverThumbnailKey, int photoCount) {
    return new PostStore.Summary(
        id,
        "제목",
        "발췌",
        "제주러버",
        CREATED,
        coverKey,
        photoCount,
        null,
        null,
        0,
        false,
        PostStore.REVIEW,
        0,
        0,
        false,
        coverThumbnailKey);
  }
}
