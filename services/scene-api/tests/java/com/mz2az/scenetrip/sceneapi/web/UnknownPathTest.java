package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.search.SuggestionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 명세에 없는 경로를 불렀을 때.
 *
 * <p><b>이 테스트가 지키는 것은 상태 코드 하나다.</b> 없는 경로가 500 으로 나가면 {@code errors.md} 의 규칙("500 은 서버 결함이니 재시도해도
 * 된다")에 따라 클라이언트가 재시도를 반복한다 — 영원히 실패할 요청을. 게다가 진짜 서버 장애와 로그에서 구분되지 않는다.
 *
 * <p>실제로 그런 상태였다. Spring 이 매핑되지 않은 경로에 대해 던지는 {@code NoResourceFoundException} 이 {@link
 * ApiExceptionHandler} 의 마지막 그물에 걸려 500 으로 나갔고, 명세가 약속한 {@code /v1} 접두사를 서버가 붙이지 않던 때 앱이 부를 모든 주소가
 * 그 500 을 받았다.
 */
@WebMvcTest(SearchController.class)
@Import(LanguageConfiguration.class)
class UnknownPathTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private SuggestionStore store;

  @Test
  @DisplayName("없는 경로는 404 — 500 이 아니다")
  void unknownPathIsNotFound() throws Exception {
    mvc.perform(get("/이런경로는없다"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
  }

  /**
   * 쓰이지 않는 필드는 응답에 나오지 않는다.
   *
   * <p>{@code traceId} 는 500 에만 채운다. 그런데 Jackson 기본값은 값이 없는 필드도 {@code null} 로 내보내서, 404 응답마다
   * {@code "traceId": null} 이 따라붙었다. {@code application.yaml} 의 {@code default-property-inclusion}
   * 이 그것을 막는데, 설정 파일은 조용히 지워져도 아무 데서도 티가 나지 않아 여기서 지킨다.
   *
   * <p><b>{@code jsonPath("$.traceId").doesNotExist()} 로 쓰면 안 된다.</b> 그 단언은 필드가 {@code null} 로 들어
   * 있어도 통과한다 — 설정을 지우고 돌려서 확인했다. 검사하지 않는 것을 통과로 보고하는 셈이라, 응답 원문을 직접 본다.
   */
  @Test
  @DisplayName("404 응답에 빈 traceId 가 붙지 않는다")
  void notFoundHasNoTraceId() throws Exception {
    String body =
        mvc.perform(get("/이런경로는없다"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("traceId");
  }

  /**
   * 있는 경로에 없는 하위 경로를 붙인 경우.
   *
   * <p>오타는 대개 이런 모양이라 함께 본다.
   */
  @Test
  @DisplayName("있는 경로 아래의 없는 하위 경로도 404")
  void unknownSubPathIsNotFound() throws Exception {
    mvc.perform(get("/search/없는것"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
  }
}
