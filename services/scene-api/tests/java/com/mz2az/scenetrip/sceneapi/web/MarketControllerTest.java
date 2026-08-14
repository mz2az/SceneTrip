package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.MarketSort;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.market.MarketStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 코스마켓의 HTTP 계층. DB 없이 돈다.
 *
 * <p>여기서 지키는 것은 <b>둘러보기와 남기기의 경계</b>다. 목록·상세는 계정 없이 열리고 올리기·담기·좋아요·내리기는 {@code 401} 이다. 지금은 가입시키는
 * 경로가 없어 실제로 그 넷을 아무도 통과하지 못하는데, 그 상태가 <b>의도한 것</b>임을 테스트가 못 박는다 — 나중에 누가 "왜 마켓이 안 되지" 하고 벽을 지워 버리지
 * 않도록.
 */
@WebMvcTest(MarketController.class)
@Import(LanguageConfiguration.class)
class MarketControllerTest {

  private static final String DEVICE = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private MarketStore store;

  @MockitoBean private CourseStore courses;

  @MockitoBean private UserStore users;

  @BeforeEach
  void resolveAccount() {
    when(users.resolve(UUID.fromString(DEVICE))).thenReturn(USER);
  }

  private void signedIn() {
    when(users.isRegistered(USER)).thenReturn(true);
  }

  private static MarketCourseSummary summary(long id) {
    return new MarketCourseSummary(
        id,
        "제주 3일",
        "설명",
        3,
        5,
        2,
        7,
        false,
        false,
        false,
        OffsetDateTime.parse("2026-08-13T06:00:00Z"));
  }

  @Test
  @DisplayName("둘러보기는 계정이 없어도 된다")
  void browsingNeedsNoAccount() throws Exception {
    MarketStore.Page page = new MarketStore.Page(List.of(summary(3L)), 1);
    when(store.list(eq(USER), eq(null), any(), eq(20), eq(0))).thenReturn(page);
    when(store.withContents(any(), any())).thenReturn(page);

    mvc.perform(get("/market/courses").header("X-Device-Id", DEVICE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].saveCount").value(7));

    verify(users, never()).isRegistered(any());
  }

  @Test
  @DisplayName("공백만 든 q 는 없는 것으로 본다")
  void blankQueryIsIgnored() throws Exception {
    MarketStore.Page page = new MarketStore.Page(List.of(), 0);
    when(store.list(eq(USER), eq(null), any(), eq(20), eq(0))).thenReturn(page);
    when(store.withContents(any(), any())).thenReturn(page);

    mvc.perform(get("/market/courses").param("q", "   ").header("X-Device-Id", DEVICE))
        .andExpect(status().isOk());

    verify(store).list(eq(USER), eq(null), any(), eq(20), eq(0));
  }

  @Test
  @DisplayName("올리기·담기·좋아요·내리기는 가입해야 한다 — 지금은 아무도 통과하지 못한다")
  void writingNeedsRegistration() throws Exception {
    when(users.isRegistered(USER)).thenReturn(false);

    mvc.perform(
            post("/market/courses")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":7,\"description\":\"설명\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    mvc.perform(post("/market/courses/3/saves").header("X-Device-Id", DEVICE))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/market/courses/3/likes").header("X-Device-Id", DEVICE))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete("/market/courses/3/likes").header("X-Device-Id", DEVICE))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete("/market/courses/3").header("X-Device-Id", DEVICE))
        .andExpect(status().isUnauthorized());

    // 벽 뒤로는 한 줄도 넘어가지 않는다.
    verify(store, never()).publish(any(), anyLong(), anyString());
    verify(store, never()).save(any(), anyLong());
    verify(store, never()).like(any(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    verify(store, never()).unpublish(any(), anyLong());
  }

  @Test
  @DisplayName("남의 코스를 올리려 하면 404")
  void cannotPublishSomeoneElsesCourse() throws Exception {
    signedIn();
    when(courses.exists(USER, 7L)).thenReturn(false);

    mvc.perform(
            post("/market/courses")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":7,\"description\":\"설명\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
  }

  @Test
  @DisplayName("이미 올라가 있으면 409")
  void alreadyPublishedIsConflict() throws Exception {
    signedIn();
    when(courses.exists(USER, 7L)).thenReturn(true);
    when(store.publish(eq(USER), eq(7L), anyString())).thenReturn(Optional.empty());

    mvc.perform(
            post("/market/courses")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":7,\"description\":\"설명\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COURSE_ALREADY_PUBLISHED"));
  }

  @Test
  @DisplayName("설명이 200자를 넘으면 400 — 계약이 약속한 자리")
  void rejectsTooLongDescription() throws Exception {
    signedIn();

    mvc.perform(
            post("/market/courses")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":7,\"description\":\"" + "가".repeat(201) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("남이 올린 것을 내리려 하면 403 — 존재는 이미 공개돼 있어 숨길 이유가 없다")
  void unpublishingSomeoneElsesPostIsForbidden() throws Exception {
    signedIn();
    when(store.isLive(3L)).thenReturn(true);
    when(store.isAuthor(USER, 3L)).thenReturn(false);

    mvc.perform(delete("/market/courses/3").header("X-Device-Id", DEVICE))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_MARKET_COURSE_AUTHOR"));

    verify(store, never()).unpublish(any(), anyLong());
  }

  @Test
  @DisplayName("내려간 것에는 담기·좋아요가 404")
  void unpublishedPostIsGone() throws Exception {
    signedIn();
    when(store.isLive(3L)).thenReturn(false);

    mvc.perform(post("/market/courses/3/saves").header("X-Device-Id", DEVICE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MARKET_COURSE_NOT_FOUND"));

    mvc.perform(post("/market/courses/3/likes").header("X-Device-Id", DEVICE))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("없는 사본을 읽으면 404")
  void missingPostIsNotFound() throws Exception {
    when(store.find(eq(USER), eq(99L), any())).thenReturn(Optional.empty());

    mvc.perform(get("/market/courses/99").header("X-Device-Id", DEVICE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MARKET_COURSE_NOT_FOUND"));
  }

  @Test
  @DisplayName("정렬 기준이 그대로 Store 에 넘어간다")
  void passesSortThrough() throws Exception {
    MarketStore.Page page = new MarketStore.Page(List.of(), 0);
    when(store.list(eq(USER), eq(null), eq(MarketSort.LIKES), eq(20), eq(0))).thenReturn(page);
    when(store.withContents(any(), any())).thenReturn(page);

    mvc.perform(get("/market/courses").param("sort", "likes").header("X-Device-Id", DEVICE))
        .andExpect(status().isOk());

    verify(store).list(eq(USER), eq(null), eq(MarketSort.LIKES), eq(20), eq(0));
  }
}
