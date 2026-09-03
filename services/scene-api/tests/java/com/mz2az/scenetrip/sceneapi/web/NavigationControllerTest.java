package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.NextLeg;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.navigation.Coordinate;
import com.mz2az.scenetrip.sceneapi.navigation.NextLegPlanner;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 길찾기의 HTTP 계층 — 통제 다섯이 그 순서로 갈리는지. DB 도 카카오도 없다.
 *
 * <p>순서가 계약이다. 404 가 409 보다 앞이어야 남의 코스의 존재가 새지 않고, 다섯이 전부 Planner 앞이어야 제공자 쿼터를 안 쓴다.
 */
@WebMvcTest(NavigationController.class)
@Import(LanguageConfiguration.class)
class NavigationControllerTest {

  private static final String DEVICE = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");
  private static final long COURSE = 7;
  private static final long ITEM = 3;

  @Autowired private MockMvc mvc;

  @MockitoBean private NextLegPlanner planner;
  @MockitoBean private CourseStore courses;
  @MockitoBean private UserStore users;

  @BeforeEach
  void happyPathByDefault() {
    when(users.resolve(UUID.fromString(DEVICE))).thenReturn(USER);
    when(users.isRegistered(USER)).thenReturn(true);
    when(courses.exists(USER, COURSE)).thenReturn(true);
    when(courses.isActive(USER, COURSE)).thenReturn(true);
    when(courses.findItemLocation(COURSE, ITEM))
        .thenReturn(Optional.of(new CourseStore.ItemLocation(37.02, 127.0)));
    when(planner.plan(any(), any(), any()))
        .thenReturn(
            new NextLeg(25, 0, NextLeg.GuidanceLangEnum.KO, List.of())
                .walkMeters(700)
                .fareWon(1200));
  }

  private static MockHttpServletRequestBuilder request(String acceptLanguage) {
    return post("/navigation/next-leg")
        .header("X-Device-Id", DEVICE)
        .header("Accept-Language", acceptLanguage)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"courseId\":"
                + COURSE
                + ",\"itemId\":"
                + ITEM
                + ",\"latitude\":37.0,\"longitude\":127.0}");
  }

  @Test
  @DisplayName("정상 — 좌표와 언어가 Planner 까지 그대로 간다")
  void happyPath() throws Exception {
    mvc.perform(request("ja"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalMinutes").value(25))
        .andExpect(jsonPath("$.walkMeters").value(700))
        .andExpect(jsonPath("$.fareWon").value(1200))
        .andExpect(jsonPath("$.guidanceLang").value("ko"));

    verify(planner)
        .plan(eq(new Coordinate(37.0, 127.0)), eq(new Coordinate(37.02, 127.0)), eq(Lang.JA));
  }

  @Test
  @DisplayName("가입 안 했으면 401 — 코스도 Planner 도 안 본다")
  void unregisteredIs401() throws Exception {
    when(users.isRegistered(USER)).thenReturn(false);

    mvc.perform(request("ko"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verify(courses, never()).exists(any(), anyLong());
    verify(planner, never()).plan(any(), any(), any());
  }

  @Test
  @DisplayName("남의 코스는 404 — 409 보다 앞이라 존재가 새지 않는다")
  void foreignCourseIs404BeforeActiveCheck() throws Exception {
    when(courses.exists(USER, COURSE)).thenReturn(false);

    mvc.perform(request("ko"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));

    verify(courses, never()).isActive(any(), anyLong());
    verify(planner, never()).plan(any(), any(), any());
  }

  @Test
  @DisplayName("예정 코스는 409")
  void upcomingCourseIs409() throws Exception {
    when(courses.isActive(USER, COURSE)).thenReturn(false);

    mvc.perform(request("ko"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_ACTIVE"));

    verify(planner, never()).plan(any(), any(), any());
  }

  @Test
  @DisplayName("없는 항목은 404 COURSE_ITEM_NOT_FOUND")
  void missingItemIs404() throws Exception {
    when(courses.findItemLocation(COURSE, ITEM)).thenReturn(Optional.empty());

    mvc.perform(request("ko"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_ITEM_NOT_FOUND"));

    verify(planner, never()).plan(any(), any(), any());
  }

  @Test
  @DisplayName("Planner 의 422 가 그대로 나간다 — 컨트롤러는 잡지 않는다")
  void plannerUnprocessablePassesThrough() throws Exception {
    when(planner.plan(any(), any(), any()))
        .thenThrow(ApiException.unprocessable("ROUTE_NOT_FOUND", "없음"));

    mvc.perform(request("ko"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
        .andExpect(jsonPath("$.traceId").doesNotExist());
  }

  @Test
  @DisplayName("Planner 의 503 도 그대로 — 5xx 라 핸들러가 traceId 자리를 챙긴다")
  void plannerUnavailablePassesThrough() throws Exception {
    when(planner.plan(any(), any(), any()))
        .thenThrow(ApiException.unavailable("ROUTING_UNAVAILABLE", "한도"));

    mvc.perform(request("ko"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ROUTING_UNAVAILABLE"));
  }

  @Test
  @DisplayName("Accept-Language 가 없으면 ko 로 간다")
  void defaultLanguageIsKo() throws Exception {
    mvc.perform(
            post("/navigation/next-leg")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":7,\"itemId\":3,\"latitude\":37.0,\"longitude\":127.0}"))
        .andExpect(status().isOk());

    verify(planner).plan(any(), any(), eq(Lang.KO));
  }
}
