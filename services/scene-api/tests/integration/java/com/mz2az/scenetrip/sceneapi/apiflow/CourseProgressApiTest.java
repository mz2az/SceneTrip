package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 여행 중 일차 이동(계약 {@code updateCourseProgress}) — 코스 길이를 넘는 일차는 클라이언트 잘못이라 400 이다 (MZ2AZ-498,
 * errors.md {@code INVALID_PARAMETER}: 「범위」를 어긴 파라미터).
 */
@DisplayName("API 통합 — 코스 일차 이동")
class CourseProgressApiTest extends ApiIntegrationTest {

  private final String install = UUID.randomUUID().toString();

  @Test
  @DisplayName("2일 코스: 2일차로 시작은 200, 3일차로 옮기면 400 INVALID_PARAMETER (500 이 아니다)")
  void dayBeyondCourseLengthIsBadRequest() throws Exception {
    long courseId =
        body(mvc.perform(
                    post("/courses")
                        .header("X-Install-Id", install)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "dayCount", 2, "origin", "self", "title", "TEST-일차-" + tag))))
                .andExpect(status().isCreated())
                .andReturn())
            .path("id")
            .asLong();

    mvc.perform(progress(courseId, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("active"))
        .andExpect(jsonPath("$.currentDayNo").value(2));

    mvc.perform(progress(courseId, 3))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  private org.springframework.test.web.servlet.RequestBuilder progress(long courseId, int dayNo)
      throws Exception {
    return put("/courses/{id}/progress", courseId)
        .header("X-Install-Id", install)
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("status", "active", "currentDayNo", dayNo)));
  }
}
