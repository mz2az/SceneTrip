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
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
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

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  private static final String INSTALL_ID = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private CourseStore store;

  @MockitoBean private CurrentAccount accounts;

  @BeforeEach
  void resolveAccount() {
    when(accounts.resolve(UUID.fromString(INSTALL_ID))).thenReturn(USER);
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
  @DisplayName("X-Install-Id 가 없으면 400")
  void missingInstallIdIsRejected() throws Exception {
    mvc.perform(get("/courses"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
  }

  @Test
  @DisplayName("설치 UUID 를 계정으로 바꿔 Store 에 넘긴다")
  void resolvesInstallUuidToAccount() throws Exception {
    when(store.list(USER)).thenReturn(List.of());

    mvc.perform(get("/courses").header("X-Install-Id", INSTALL_ID)).andExpect(status().isOk());

    verify(accounts).resolve(UUID.fromString(INSTALL_ID));
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
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dayCount\":16,\"origin\":\"self\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("없는 코스를 읽으면 404")
  void missingCourseIsNotFound() throws Exception {
    when(store.find(eq(USER), eq(99L), any())).thenReturn(Optional.empty());

    mvc.perform(get("/courses/99").header("X-Install-Id", INSTALL_ID))
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
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[{\"dwellMinutes\":60}]}]")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    // 둘 다 있다
    mvc.perform(
            put("/courses/7")
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visited\":true}"))
        .andExpect(status().isNoContent());

    mvc.perform(
            put("/courses/7/items/99/visit")
                .header("X-Install-Id", INSTALL_ID)
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
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[{\"placeId\":1}]}]")))
        .andExpect(status().isOk());

    verify(store).replace(eq(7L), any(CourseReplace.class));
  }

  // ───────────── 편의시설 항목 (1.8.0, MZ2AZ-377) ─────────────

  private static final String PIN =
      "{\"name\":\"호텔\",\"category\":\"lodging\",\"latitude\":37.5,\"longitude\":127.0}";

  @Test
  @DisplayName("poiId 하나만 보내면 통과해 Store 까지 간다")
  void acceptsPoiIdAlone() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.empty());
    when(store.find(eq(USER), eq(7L), any()))
        .thenReturn(Optional.of(course(7L, CourseStatus.UPCOMING)));

    mvc.perform(
            put("/courses/7")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[{\"poiId\":277819}]}]")))
        .andExpect(status().isOk());

    org.mockito.ArgumentCaptor<CourseReplace> sent =
        org.mockito.ArgumentCaptor.forClass(CourseReplace.class);
    verify(store).replace(eq(7L), sent.capture());
    org.assertj.core.api.Assertions.assertThat(
            sent.getValue().getDays().get(0).getItems().get(0).getPoiId())
        .isEqualTo(277819L);
  }

  @Test
  @DisplayName("placeId · poiId · customPin 중 둘 이상이면 400 INVALID_PARAMETER — Store 까지 가지 않는다")
  void rejectsPoiIdWithAnotherTarget() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.empty());

    for (String item :
        List.of(
            "{\"placeId\":1,\"poiId\":2}",
            "{\"poiId\":2,\"customPin\":" + PIN + "}",
            "{\"placeId\":1,\"poiId\":2,\"customPin\":" + PIN + "}")) {
      mvc.perform(
              put("/courses/7")
                  .header("X-Install-Id", INSTALL_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(replaceBody("[{\"items\":[" + item + "]}]")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    verify(store, never()).replace(anyLong(), any());
  }

  @Test
  @DisplayName("둘째 일차의 항목이 셋 다 비어 있어도 400 INVALID_PARAMETER")
  void rejectsEmptyTargetInLaterDay() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.empty());

    mvc.perform(
            put("/courses/7")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    replaceBody(
                        "[{\"items\":[{\"poiId\":2}]},{\"items\":[{\"dwellMinutes\":30}]}]")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).replace(anyLong(), any());
  }

  @Test
  @DisplayName("없거나 폐업한 편의시설이면 400 POI_NOT_FOUND — docs/api/errors.md")
  void unknownPoiIsBadRequest() throws Exception {
    when(store.exists(USER, 7L)).thenReturn(true);
    when(store.currentDayNo(USER, 7L)).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new CourseStore.UnknownPoiException(277819L))
        .when(store)
        .replace(eq(7L), any());

    mvc.perform(
            put("/courses/7")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("[{\"items\":[{\"poiId\":277819}]}]")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }

  @Test
  @DisplayName("상세의 편의시설 항목은 source poi · poiId 로 나간다")
  void detailSerializesPoiItem() throws Exception {
    var poiItem =
        new com.mz2az.scenetrip.sceneapi.api.model.CourseItem(
                11L,
                com.mz2az.scenetrip.sceneapi.api.model.CourseItemSource.POI,
                "카페 하나",
                37.5,
                127.0,
                60)
            .poiId(277819L)
            .address("서울 중구 명동")
            .category("카페");
    CourseDetail detail =
        new CourseDetail(
            7L,
            "제주 3일",
            1,
            CourseStatus.UPCOMING,
            CourseOrigin.SELF,
            1,
            OffsetDateTime.parse("2026-08-13T06:00:00Z"),
            OffsetDateTime.parse("2026-08-13T06:00:00Z"),
            List.of(new CourseDay(1, List.of(poiItem), 60, 0, 60, TravelBasis.STRAIGHT_LINE)));
    when(store.find(eq(USER), eq(7L), any())).thenReturn(Optional.of(detail));

    mvc.perform(get("/courses/7").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].items[0].source").value("poi"))
        .andExpect(jsonPath("$.days[0].items[0].poiId").value(277819))
        .andExpect(jsonPath("$.days[0].items[0].category").value("카페"));
  }

  @Test
  @DisplayName("지우면 204, 없으면 404")
  void deleteReturnsNoContentOrNotFound() throws Exception {
    when(store.delete(USER, 7L)).thenReturn(true);
    when(store.delete(USER, 99L)).thenReturn(false);

    mvc.perform(delete("/courses/7").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isNoContent());

    mvc.perform(delete("/courses/99").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
  }
}
