package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.CourseDay;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CourseStatus;
import com.mz2az.scenetrip.sceneapi.api.model.TravelBasis;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
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

/** 코스의 HTTP 계층. DB 없이 돈다. */
@WebMvcTest(CourseController.class)
@Import(LanguageConfiguration.class)
class CourseControllerTest {

  private static final String DEVICE = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private CourseStore store;

  @MockitoBean private UserStore users;

  @BeforeEach
  void resolveAccount() {
    when(users.resolve(UUID.fromString(DEVICE))).thenReturn(USER);
  }

  private static CourseDetail course(long id, CourseStatus status) {
    return new CourseDetail(
        id,
        "제주 3일",
        3,
        status,
        CourseOrigin.SELF,
        0,
        OffsetDateTime.parse("2026-08-13T06:00:00Z"),
        OffsetDateTime.parse("2026-08-13T06:00:00Z"),
        List.of(new CourseDay(1, List.of(), 0, 0, 0, TravelBasis.STRAIGHT_LINE)));
  }

  private static String replaceBody(String days) {
    return "{\"title\":\"제주 3일\",\"days\":" + days + "}";
  }

  @Test
  @DisplayName("X-Device-Id 가 없으면 400")
  void missingDeviceIdIsRejected() throws Exception {
    mvc.perform(get("/courses"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_DEVICE_ID"));
  }

  @Test
  @DisplayName("설치 UUID 를 계정으로 바꿔 Store 에 넘긴다")
  void resolvesInstallUuidToAccount() throws Exception {
    when(store.list(USER)).thenReturn(List.of());

    mvc.perform(get("/courses").header("X-Device-Id", DEVICE)).andExpect(status().isOk());

    verify(users).resolve(UUID.fromString(DEVICE));
    verify(store).list(USER);
  }

  @Test
  @DisplayName("만들면 201 이고 몸통은 상세다")
  void createReturnsCreated() throws Exception {
    when(store.create(eq(USER), any())).thenReturn(7L);
    when(store.find(eq(USER), eq(7L), any()))
        .thenReturn(Optional.of(course(7L, CourseStatus.UPCOMING)));

    mvc.perform(
            post("/courses")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dayCount\":3,\"origin\":\"self\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.days.length()").value(1));
  }

  @Test
  @DisplayName("기간이 15일을 넘으면 400 — 계약이 약속한 자리다")
  void rejectsTooLongCourse() throws Exception {
    mvc.perform(
            post("/courses")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dayCount\":16,\"origin\":\"self\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("없는 코스를 읽으면 404")
  void missingCourseIsNotFound() throws Exception {
    when(store.find(eq(USER), eq(99L), any())).thenReturn(Optional.empty());

    mvc.perform(get("/courses/99").header("X-Device-Id", DEVICE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
  }

  @Test
  @DisplayName("장소를 가리키는 방법이 둘 다이거나 둘 다 아니면 400")
  void rejectsAmbiguousItemTarget() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);

    // 둘 다 없다
    mvc.perform(
            put("/courses/7")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[{\"dwellMinutes\":60}]}]")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    // 둘 다 있다
    mvc.perform(
            put("/courses/7")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    replaceBody(
                        "[{\"items\":[{\"dwellMinutes\":60,\"placeId\":1,\"customPin\":"
                            + "{\"name\":\"호텔\",\"category\":\"lodging\","
                            + "\"latitude\":37.5,\"longitude\":127.0}}]}]")))
        .andExpect(status().isBadRequest());

    // 어느 쪽도 Store 까지 가지 않는다 — DB 제약이 500 으로 새 나가기 전에 막는다.
    verify(store, never()).replace(anyLong(), any());
  }

  @Test
  @DisplayName("여행 중인 코스를 지금 일차보다 짧게 줄이면 409")
  void rejectsShrinkingBelowProgress() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.of(3));

    mvc.perform(
            put("/courses/7")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[]},{\"items\":[]}]")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COURSE_SHORTER_THAN_PROGRESS"));

    verify(store, never()).replace(anyLong(), any());
  }

  @Test
  @DisplayName("예정 코스는 얼마든지 줄일 수 있다")
  void allowsShrinkingWhenNotTravelling() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.empty());
    when(store.find(eq(USER), eq(7L), any()))
        .thenReturn(Optional.of(course(7L, CourseStatus.UPCOMING)));

    mvc.perform(
            put("/courses/7")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[]}]")))
        .andExpect(status().isOk());

    verify(store).replace(eq(7L), any(CourseReplace.class));
  }

  @Test
  @DisplayName("남의 항목 id 를 넣으면 400 — 서버 결함이 아니다")
  void unknownItemIsBadRequest() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new CourseStore.UnknownItemException(42L))
        .when(store)
        .replace(eq(7L), any());

    mvc.perform(
            put("/courses/7")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[{\"dwellMinutes\":60,\"placeId\":1}]}]")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNKNOWN_COURSE_ITEM"));
  }

  @Test
  @DisplayName("여행 중으로 바꾸면서 지금 일차를 빠뜨리면 400")
  void activeNeedsCurrentDay() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);

    mvc.perform(
            put("/courses/7/progress")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"active\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).updateProgress(anyLong(), any());
  }

  @Test
  @DisplayName("코스를 시작하면 상세가 돌아온다")
  void startsTrip() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.find(eq(USER), eq(7L), any()))
        .thenReturn(Optional.of(course(7L, CourseStatus.ACTIVE)));

    mvc.perform(
            put("/courses/7/progress")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"active\",\"currentDayNo\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("active"));
  }

  @Test
  @DisplayName("여행 중이 아니면 방문 체크는 409 — 요청이 아니라 코스 상태가 문제다")
  void visitNeedsActiveCourse() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.isActive(USER, 7L)).thenReturn(false);

    mvc.perform(
            put("/courses/7/items/3/visit")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visited\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_ACTIVE"));

    verify(store, never())
        .markVisited(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  @DisplayName("여행 중이면 방문 체크는 204, 없는 항목은 404")
  void visitTogglesWhileTravelling() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.isActive(USER, 7L)).thenReturn(true);
    when(store.markVisited(7L, 3L, true)).thenReturn(true);
    when(store.markVisited(7L, 99L, true)).thenReturn(false);

    mvc.perform(
            put("/courses/7/items/3/visit")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visited\":true}"))
        .andExpect(status().isNoContent());

    mvc.perform(
            put("/courses/7/items/99/visit")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visited\":true}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_ITEM_NOT_FOUND"));
  }

  @Test
  @DisplayName("체류시간을 비운 채로 담아도 통과한다 — 기본값은 서버가 채운다")
  void dwellMinutesIsOptional() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.empty());
    when(store.find(eq(USER), eq(7L), any()))
        .thenReturn(Optional.of(course(7L, CourseStatus.UPCOMING)));

    mvc.perform(
            put("/courses/7")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[{\"placeId\":1}]}]")))
        .andExpect(status().isOk());

    verify(store).replace(eq(7L), any(CourseReplace.class));
  }

  @Test
  @DisplayName("지우면 204, 없으면 404")
  void deleteReturnsNoContentOrNotFound() throws Exception {
    when(store.delete(USER, 7L)).thenReturn(true);
    when(store.delete(USER, 99L)).thenReturn(false);

    mvc.perform(delete("/courses/7").header("X-Device-Id", DEVICE))
        .andExpect(status().isNoContent());

    mvc.perform(delete("/courses/99").header("X-Device-Id", DEVICE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
  }
}
