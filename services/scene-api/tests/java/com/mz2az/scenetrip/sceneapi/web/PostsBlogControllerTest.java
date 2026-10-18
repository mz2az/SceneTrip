package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlock;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlockKind;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PostsController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, PostsControllerTest.Tokens.class})
class PostsBlogControllerTest {
  private static final UUID USER = UUID.fromString("2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d");
  private static final String BODY = "첫 문단\n\n둘째 문단";
  private static final String INPUT =
      """
      {"title":"제목","body":"첫 문단\\n\\n둘째 문단","photoKeys":["uploads/tmp/a.jpg"],
       "bodyBlocks":[{"kind":"text","text":"첫 문단"},
        {"kind":"photo","photoIndex":0,"caption":"경복궁","credit":"원본 출처","aiGenerated":true},
        {"kind":"text","text":"둘째 문단"}]}
      """;
  @Autowired private MockMvc mvc;
  @Autowired private AccessTokens tokens;
  @MockitoBean private PostStore store;
  @MockitoBean private CourseStore courses;
  @MockitoBean private PhotoStorage storage;
  @MockitoBean private UploadStore uploads;
  @MockitoBean private UserStore users;

  @BeforeEach
  void setup() {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.isRegistered(USER)).thenReturn(true);
  }

  @Test
  void createReturnsSameTextPhotoOrderAndMetadata() throws Exception {
    List<TripPostBlock> blocks =
        List.of(
            new TripPostBlock(TripPostBlockKind.TEXT).text("첫 문단"),
            new TripPostBlock(TripPostBlockKind.PHOTO)
                .photoIndex(0)
                .caption("경복궁")
                .credit("원본 출처")
                .aiGenerated(true),
            new TripPostBlock(TripPostBlockKind.TEXT).text("둘째 문단"));
    when(uploads.claim(eq(USER), anyCollection())).thenReturn(claimOf(Set.of("uploads/tmp/a.jpg")));
    when(store.create(
            eq(USER), eq("제목"), eq(BODY), anyList(), anyCollection(), isNull(), eq(blocks)))
        .thenReturn(88L);
    PostStore.Summary head =
        new PostStore.Summary(
            88,
            "제목",
            "발췌",
            "작성자",
            OffsetDateTime.parse("2026-10-13T00:00:00Z"),
            "posts/a.jpg",
            1,
            null,
            null,
            0,
            true);
    when(store.find(88L, USER))
        .thenReturn(Optional.of(new PostStore.Detail(head, BODY, List.of("posts/a.jpg"), blocks)));
    when(storage.viewUrl("posts/a.jpg"))
        .thenReturn(URI.create("https://signed.example/posts/a.jpg"));

    mvc.perform(
            post("/posts")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.body").value(BODY))
        .andExpect(jsonPath("$.bodyBlocks[0].text").value("첫 문단"))
        .andExpect(jsonPath("$.bodyBlocks[1].photoIndex").value(0))
        .andExpect(jsonPath("$.bodyBlocks[1].caption").value("경복궁"))
        .andExpect(jsonPath("$.bodyBlocks[1].credit").value("원본 출처"))
        .andExpect(jsonPath("$.bodyBlocks[1].aiGenerated").value(true))
        .andExpect(jsonPath("$.bodyBlocks[2].text").value("둘째 문단"));
    verify(uploads, never()).release(any());
  }

  @Test
  void inconsistentBodyIsRejectedBeforeMovingPhotos() throws Exception {
    rejected(INPUT.replace("\"text\":\"첫 문단\"", "\"text\":\"다른 문단\""));
  }

  @Test
  void outOfAttachmentRangeIsRejectedBeforeMovingPhotos() throws Exception {
    rejected(INPUT.replace("\"photoIndex\":0", "\"photoIndex\":1"));
  }

  @Test
  void captionOnTextIsRejectedBeforeMovingPhotos() throws Exception {
    rejected(INPUT.replace("\"text\":\"첫 문단\"", "\"text\":\"첫 문단\",\"caption\":\"설명\""));
  }

  private void rejected(String input) throws Exception {
    mvc.perform(
            post("/posts")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POST_BODY_INVALID"));
    verify(storage, never()).move(anyString(), anyString());
    verifyUploadsReleasedIfClaimed();
    verify(store, never())
        .create(any(), any(), any(), anyList(), anyCollection(), any(), anyList());
  }

  private String bearer() {
    return "Bearer " + tokens.issue(USER).value();
  }

  /** 업로드 기록을 차지한 결과(MZ2AZ-498) — {@code keys} 를 모두 차지했다. */
  private static UploadStore.Claim claimOf(Set<String> keys) {
    return new UploadStore.Claim(
        keys.stream()
            .map(k -> new UploadStore.Row(k, USER, "review", "image/jpeg", 1L, null))
            .toList());
  }

  /** 저장하지 못했으면 업로드를 다시 쓸 수 있어야 한다 — 차지했다면 그만큼 돌려놓았다(예전 「consume 하지 않음」 과 같은 뜻). */
  private void verifyUploadsReleasedIfClaimed() {
    long claims =
        org.mockito.Mockito.mockingDetails(uploads).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("claim"))
            .count();
    verify(uploads, times((int) claims)).release(any());
  }
}
