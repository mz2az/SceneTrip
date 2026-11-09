package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.mz2az.scenetrip.sceneapi.trip.TripStore;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 정한 여행 {@code /me/trip} 의 HTTP 계층(계약 1.25.0, 계획 {@code trip-memory.md} §2·§4). DB 없이 돈다 — Store 는
 * 가짜다.
 *
 * <p>명세에서 나온 기대: 없으면 404 {@code TRIP_NOT_FOUND}, {@code days} 1~15·{@code pace} 셋 중 하나·{@code city}
 * 는 앞뒤 공백을 뗀 뒤 1~40자(비면 400 {@code INVALID_PARAMETER}), 남의 코스는 400 {@code TRIP_COURSE_INVALID},
 * 지우기는 없어도 204.
 */
@WebMvcTest(TripController.class)
@Import(LanguageConfiguration.class)
class TripControllerTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  private static final String INSTALL_ID = "5b7c1e20-3d4f-4a61-8c92-7e0f1a2b3c4d";

  private static final UUID USER = UUID.fromString("a1b2c3d4-e5f6-4071-8293-a4b5c6d7e8f9");

  private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-11-09T03:00:00Z");

  @Autowired private MockMvc mvc;

  @MockitoBean private TripStore store;

  @MockitoBean private CurrentAccount accounts;

  @BeforeEach
  void resolveAccount() {
    when(accounts.resolve(UUID.fromString(INSTALL_ID))).thenReturn(USER);
  }

  private static RequestBuilder putTrip(String json) {
    return put("/me/trip")
        .header("X-Install-Id", INSTALL_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json);
  }

  // ── GET ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("정한 여행이 없으면 404 TRIP_NOT_FOUND")
  void missingTripIsNotFound() throws Exception {
    when(store.find(USER)).thenReturn(Optional.empty());

    mvc.perform(get("/me/trip").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
  }

  @Test
  @DisplayName("정한 여행이 있으면 200 TripPlan — city·days·pace·courseId·updatedAt")
  void getReturnsTripPlan() throws Exception {
    when(store.find(USER))
        .thenReturn(Optional.of(new TripStore.Trip("부산", 2, "relaxed", 42L, UPDATED)));

    mvc.perform(get("/me/trip").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("부산"))
        .andExpect(jsonPath("$.days").value(2))
        .andExpect(jsonPath("$.pace").value("relaxed"))
        .andExpect(jsonPath("$.courseId").value(42))
        .andExpect(jsonPath("$.updatedAt").exists());

    verify(accounts).resolve(UUID.fromString(INSTALL_ID));
  }

  @Test
  @DisplayName("코스가 없는 여행은 courseId 칸이 빠진다(응답은 null 인 선택 칸을 내보내지 않는다)")
  void getWithoutCourseOmitsCourseId() throws Exception {
    when(store.find(USER))
        .thenReturn(Optional.of(new TripStore.Trip("제주", 3, "normal", null, UPDATED)));

    mvc.perform(get("/me/trip").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("제주"))
        .andExpect(jsonPath("$.courseId").doesNotExist());
  }

  @Test
  @DisplayName("X-Install-Id 가 없으면 400 MISSING_INSTALL_ID")
  void missingInstallIdIsRejected() throws Exception {
    mvc.perform(get("/me/trip"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
  }

  // ── PUT ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("PUT 은 설치 UUID 를 계정으로 바꿔 Store 에 넘기고 200 TripPlan 을 돌려준다")
  void putReturnsTripPlan() throws Exception {
    when(store.put(USER, "부산", 2, "packed", 42L))
        .thenReturn(new TripStore.Trip("부산", 2, "packed", 42L, UPDATED));

    mvc.perform(putTrip("{\"city\":\"부산\",\"days\":2,\"pace\":\"packed\",\"courseId\":42}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("부산"))
        .andExpect(jsonPath("$.days").value(2))
        .andExpect(jsonPath("$.pace").value("packed"))
        .andExpect(jsonPath("$.courseId").value(42))
        .andExpect(jsonPath("$.updatedAt").exists());

    verify(store).put(USER, "부산", 2, "packed", 42L);
  }

  @Test
  @DisplayName("courseId 없이도 된다 — Store 에는 null 로 간다")
  void putWithoutCourse() throws Exception {
    when(store.put(eq(USER), eq("서울"), eq(1), eq("normal"), isNull()))
        .thenReturn(new TripStore.Trip("서울", 1, "normal", null, UPDATED));

    mvc.perform(putTrip("{\"city\":\"서울\",\"days\":1,\"pace\":\"normal\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.courseId").doesNotExist());

    verify(store).put(eq(USER), eq("서울"), eq(1), eq("normal"), isNull());
  }

  @Test
  @DisplayName("city 는 앞뒤 공백을 뗀 값으로 Store 에 간다")
  void putTrimsCity() throws Exception {
    when(store.put(eq(USER), eq("부산"), eq(2), eq("relaxed"), isNull()))
        .thenReturn(new TripStore.Trip("부산", 2, "relaxed", null, UPDATED));

    mvc.perform(putTrip("{\"city\":\"  부산  \",\"days\":2,\"pace\":\"relaxed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("부산"));

    verify(store).put(eq(USER), eq("부산"), eq(2), eq("relaxed"), isNull());
  }

  @Test
  @DisplayName("days 의 경계 1·15 는 받는다")
  void daysBoundariesAccepted() throws Exception {
    when(store.put(eq(USER), anyString(), anyInt(), anyString(), any()))
        .thenAnswer(
            inv ->
                new TripStore.Trip(
                    inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), null, UPDATED));

    mvc.perform(putTrip("{\"city\":\"부산\",\"days\":1,\"pace\":\"normal\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days").value(1));
    mvc.perform(putTrip("{\"city\":\"부산\",\"days\":15,\"pace\":\"normal\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days").value(15));
  }

  @Test
  @DisplayName("days 0 은 400 INVALID_PARAMETER — Store 까지 가지 않는다")
  void daysZeroRejected() throws Exception {
    mvc.perform(putTrip("{\"city\":\"부산\",\"days\":0,\"pace\":\"normal\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verify(store, never()).put(any(), any(), anyInt(), any(), any());
  }

  @Test
  @DisplayName("days 16 은 400 INVALID_PARAMETER — Store 까지 가지 않는다")
  void daysSixteenRejected() throws Exception {
    mvc.perform(putTrip("{\"city\":\"부산\",\"days\":16,\"pace\":\"normal\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verify(store, never()).put(any(), any(), anyInt(), any(), any());
  }

  @Test
  @DisplayName("pace 가 relaxed·normal·packed 가 아니면 400 INVALID_PARAMETER")
  void badPaceRejected() throws Exception {
    mvc.perform(putTrip("{\"city\":\"부산\",\"days\":2,\"pace\":\"fast\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verify(store, never()).put(any(), any(), anyInt(), any(), any());
  }

  @Test
  @DisplayName("city 가 공백뿐이면 400 INVALID_PARAMETER — 앞뒤 공백을 떼면 비기 때문")
  void blankCityRejected() throws Exception {
    mvc.perform(putTrip("{\"city\":\"   \",\"days\":2,\"pace\":\"normal\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verify(store, never()).put(any(), any(), anyInt(), any(), any());
  }

  @Test
  @DisplayName("city 가 빈 문자열이면 400 INVALID_PARAMETER")
  void emptyCityRejected() throws Exception {
    mvc.perform(putTrip("{\"city\":\"\",\"days\":2,\"pace\":\"normal\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verify(store, never()).put(any(), any(), anyInt(), any(), any());
  }

  @Test
  @DisplayName("Store 가 남의 코스라고 거절하면 400 TRIP_COURSE_INVALID")
  void foreignCourseIsRejected() throws Exception {
    when(store.put(USER, "부산", 2, "normal", 77L))
        .thenThrow(new TripStore.CourseRejectedException(77L));

    mvc.perform(putTrip("{\"city\":\"부산\",\"days\":2,\"pace\":\"normal\",\"courseId\":77}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TRIP_COURSE_INVALID"));
  }

  // ── DELETE ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("DELETE 는 204 — 그 계정의 여행을 지운다")
  void deleteReturnsNoContent() throws Exception {
    mvc.perform(delete("/me/trip").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isNoContent());

    verify(store).delete(USER);
  }
}
