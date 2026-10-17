package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoWalkResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 유료 한도 — 챗봇(1 시간 15 턴)·길찾기(1 분 10 번)의 N+1 번째가 실제 DB 카운터({@code usage_counter})로 429 와 {@code
 * Retry-After} 를 받는다(rate-limit.md §2·§3, 계약 「요청 한도」·{@code TooManyRequests}, 계획 §2). 숫자는
 * application.yaml 의 free 요금제 기본값이다.
 */
@DisplayName("API 통합 — 유료 한도")
class PaidQuotaApiTest extends ApiIntegrationTest {

  private static final int GUIDE_PER_HOUR = 15;
  private static final int NAVIGATION_PER_MINUTE = 10;

  private final String install = UUID.randomUUID().toString();

  @Test
  @DisplayName("챗봇: 15 턴까지 200, 16 번째는 429 GUIDE_LIMIT_REACHED + Retry-After — 에이전트는 15 번만 불린다")
  void guideChatHourLimit() throws Exception {
    UUID me = member("TEST_gd" + tag.substring(0, 6));
    when(agent.chat(any(), any(), any()))
        .thenReturn(new GuideChatReply("TEST 답", List.of(), List.of(), 1.0, List.of(), List.of()));

    for (int i = 0; i < GUIDE_PER_HOUR; i++) {
      mvc.perform(chat(me))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reply").value("TEST 답"));
    }
    MvcResult over =
        mvc.perform(chat(me))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("GUIDE_LIMIT_REACHED"))
            .andReturn();

    assertThat(Long.parseLong(over.getResponse().getHeader("Retry-After"))).isPositive();
    assertThat(over.getResponse().getHeader("RateLimit-Remaining")).isEqualTo("0");
    verify(agent, times(GUIDE_PER_HOUR)).chat(any(), any(), any());
  }

  @Test
  @DisplayName("챗봇: 비회원은 401 SIGN_IN_REQUIRED — 에이전트를 부르지 않는다")
  void guideChatNeedsSignIn() throws Exception {
    mvc.perform(
            post("/guide/chat")
                .header("X-Install-Id", install)
                .contentType(MediaType.APPLICATION_JSON)
                .content(chatBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    verify(agent, never()).chat(any(), any(), any());
  }

  @Test
  @DisplayName("길찾기: 여행 중 코스에서 10 번까지 200, 11 번째는 429 NAVIGATION_LIMIT_REACHED + Retry-After")
  void navigationMinuteLimit() throws Exception {
    UUID me = member("TEST_nv" + tag.substring(0, 6));
    String auth = bearer(me);
    // 지금 위치 바로 옆(약 111 m) — 걷기만 묻는 거리.
    long p = place("TEST-길찾기-" + tag, 127.0, 37.001);
    when(kakao.walk(any(), any(), any())).thenReturn(walk());

    long courseId =
        body(mvc.perform(
                    post("/courses")
                        .header("Authorization", auth)
                        .header("X-Install-Id", install)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("dayCount", 1, "origin", "self"))))
                .andExpect(status().isCreated())
                .andReturn())
            .path("id")
            .asLong();
    JsonNode replaced =
        body(
            mvc.perform(
                    put("/courses/{id}", courseId)
                        .header("Authorization", auth)
                        .header("X-Install-Id", install)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "title",
                                    "TEST-길찾기코스",
                                    "days",
                                    List.of(Map.of("items", List.of(Map.of("placeId", p))))))))
                .andExpect(status().isOk())
                .andReturn());
    long itemId = replaced.path("days").get(0).path("items").get(0).path("id").asLong();
    mvc.perform(
            put("/courses/{id}/progress", courseId)
                .header("Authorization", auth)
                .header("X-Install-Id", install)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("status", "active", "currentDayNo", 1))))
        .andExpect(status().isOk());

    String leg =
        JSON.writeValueAsString(
            Map.of("courseId", courseId, "itemId", itemId, "latitude", 37.0, "longitude", 127.0));
    for (int i = 0; i < NAVIGATION_PER_MINUTE; i++) {
      mvc.perform(nextLeg(auth, leg)).andExpect(status().isOk());
    }
    MvcResult over =
        mvc.perform(nextLeg(auth, leg))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("NAVIGATION_LIMIT_REACHED"))
            .andReturn();
    assertThat(Long.parseLong(over.getResponse().getHeader("Retry-After"))).isPositive();
  }

  private RequestBuilder chat(UUID user) throws Exception {
    return post("/guide/chat")
        .header("Authorization", bearer(user))
        .header("X-Install-Id", install)
        .contentType(MediaType.APPLICATION_JSON)
        .content(chatBody());
  }

  private RequestBuilder nextLeg(String auth, String body) {
    return post("/navigation/next-leg")
        .header("Authorization", auth)
        .header("X-Install-Id", install)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static String chatBody() throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "sessionId",
            UUID.randomUUID().toString(),
            "latitude",
            37.0,
            "longitude",
            127.0,
            "messages",
            List.of(Map.of("role", "user", "content", "TEST 근처 카페?"))));
  }

  private static KakaoWalkResponse walk() {
    return new KakaoWalkResponse(
        "OK",
        new KakaoWalkResponse.Route(
            new KakaoWalkResponse.Properties(120, 100),
            List.of(
                new KakaoWalkResponse.Leg(
                    new KakaoWalkResponse.LegProperties(120, 100),
                    List.of(
                        new KakaoWalkResponse.Step(
                            new KakaoWalkResponse.StepProperties(120, 100, "TEST 직진", 127.0, 37.0),
                            new KakaoWalkResponse.Path(
                                List.of(List.of(127.0, 37.0), List.of(127.0, 37.001)))))))));
  }
}
