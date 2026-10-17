package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 공통 오류 — 앱 전체 설정(오류 처리기·검증기·Jackson 설정)으로 띄웠을 때의 400·404. 컨트롤러 시험은 컨트롤러 하나만 띄우므로 앱 설정이 빠진 경우를 잡지
 * 못한다(docs/api/errors.md, 계획 §2).
 */
@DisplayName("API 통합 — 공통 오류")
class CommonErrorsApiTest extends ApiIntegrationTest {

  @Test
  @DisplayName("깨진 JSON 본문은 400 INVALID_PARAMETER — 500 이 아니다")
  void malformedJsonIsBadRequest() throws Exception {
    mvc.perform(
            post("/cart/items")
                .header("X-Install-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\": "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  @DisplayName("명세의 제약을 어긴 파라미터(limit=0, 경로 숫자 자리에 글자)는 400 INVALID_PARAMETER")
  void constraintViolationIsBadRequest() throws Exception {
    mvc.perform(get("/places").param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    mvc.perform(get("/places/{id}", "TEST-abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("없는 경로는 404 ENDPOINT_NOT_FOUND 이고 traceId 키가 없다")
  void unknownPathIsNotFoundWithoutTraceId() throws Exception {
    String body =
        mvc.perform(get("/TEST-없는-경로"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("traceId");
  }
}
