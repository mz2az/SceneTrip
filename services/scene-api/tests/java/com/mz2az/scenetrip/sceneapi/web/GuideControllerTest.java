package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlan;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideUiDirective;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.guide.GuideEffectApplier;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
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
 * 가이드의 HTTP 계층 — 가입 판정이 에이전트 앞에 있는지, 응답을 옮겨 담지 않는지, effects 가 그 계정으로 처리기에 가는지, 실패가 계약의 상태 코드로 나가는지.
 * DB 도 에이전트도 없다.
 */
@WebMvcTest(GuideController.class)
@Import(LanguageConfiguration.class)
class GuideControllerTest {

  private static final String DEVICE = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private GuideAgentClient agent;
  @MockitoBean private GuideEffectApplier effects;
  @MockitoBean private UserStore users;

  private static final String CHAT_BODY =
      """
      {"sessionId":"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11","latitude":37.5665,"longitude":126.978,
       "messages":[{"role":"user","content":"1번 담아 줘"}],
       "context":{"stops":[]}}
      """;

  private static GuideChatReply chatReply() {
    GuideEffect add = new GuideEffect("cart.add");
    add.setPlaceId(1187L);
    add.setName("서울중앙고");
    GuideUiDirective focus = new GuideUiDirective("map.focus");
    focus.setPlaceIds(List.of(1187L));
    return new GuideChatReply(
        "서울중앙고를 담았어요.", List.of(), List.of(), 3.4, List.of(add), List.of(focus));
  }

  @BeforeEach
  void happyPathByDefault() {
    when(users.resolve(UUID.fromString(DEVICE))).thenReturn(USER);
    when(users.isRegistered(USER)).thenReturn(true);
    when(agent.chat(any(), any())).thenReturn(chatReply());
    GuidePlanReply planReply =
        new GuidePlanReply(new GuidePlan(List.of()), List.of(), List.of(), 0.02);
    when(agent.plan(any(), any())).thenReturn(planReply);
  }

  private static MockHttpServletRequestBuilder chat(String acceptLanguage) {
    return post("/guide/chat")
        .header("X-Device-Id", DEVICE)
        .header("Accept-Language", acceptLanguage)
        .contentType(MediaType.APPLICATION_JSON)
        .content(CHAT_BODY);
  }

  @Test
  @DisplayName("정상 — 응답을 옮겨 담지 않는다(effects·ui 그대로), effects 는 그 계정으로 처리기에 간다")
  void happyPath() throws Exception {
    mvc.perform(chat("en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("서울중앙고를 담았어요."))
        .andExpect(jsonPath("$.tookSeconds").value(3.4))
        .andExpect(jsonPath("$.effects[0].op").value("cart.add"))
        .andExpect(jsonPath("$.effects[0].placeId").value(1187))
        .andExpect(jsonPath("$.ui[0].op").value("map.focus"))
        .andExpect(jsonPath("$.ui[0].placeIds[0]").value(1187));

    verify(agent).chat(any(), eq(Lang.EN));
    verify(effects).apply(eq(USER), any());
  }

  @Test
  @DisplayName("미가입은 401 — 에이전트를 부르기 전이라 토큰이 안 나간다")
  void unregisteredIs401BeforeAgent() throws Exception {
    when(users.isRegistered(USER)).thenReturn(false);

    mvc.perform(chat("ko"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verify(agent, never()).chat(any(), any());
    verify(effects, never()).apply(any(), any());
  }

  @Test
  @DisplayName("X-Device-Id 없으면 400 — 에이전트를 부르지 않는다")
  void missingDeviceIdIs400() throws Exception {
    mvc.perform(post("/guide/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
        .andExpect(status().isBadRequest());

    verify(agent, never()).chat(any(), any());
  }

  @Test
  @DisplayName("계약의 필수 필드(messages)가 없으면 400 — 생성된 @Valid 가 막는다")
  void invalidBodyIs400() throws Exception {
    mvc.perform(
            post("/guide/chat")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sessionId\":\"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11\",\"latitude\":37.0,\"longitude\":127.0}"))
        .andExpect(status().isBadRequest());

    verify(agent, never()).chat(any(), any());
  }

  @Test
  @DisplayName("에이전트에 못 닿으면 503 GUIDE_UNAVAILABLE — 처리기는 안 불린다")
  void agentDownIs503() throws Exception {
    when(agent.chat(any(), any()))
        .thenThrow(
            ApiException.unavailable(GuideAgentClient.CODE_UNAVAILABLE, "가이드 에이전트에 연결하지 못했습니다"));

    mvc.perform(chat("ko"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("GUIDE_UNAVAILABLE"));

    verify(effects, never()).apply(any(), any());
  }

  @Test
  @DisplayName("에이전트의 400 은 그대로 400 — code·message 가 앱까지 간다")
  void agent400PassesThrough() throws Exception {
    when(agent.chat(any(), any()))
        .thenThrow(ApiException.badRequest("INVALID_PARAMETER", "context.plan: 모양이 맞지 않는다"));

    mvc.perform(chat("ko"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
        .andExpect(jsonPath("$.message").value("context.plan: 모양이 맞지 않는다"));
  }

  @Test
  @DisplayName("effects 적용이 결함이면 500 INTERNAL_ERROR — 「담았어요」 뒤에 200 을 주지 않는다")
  void effectFailureIs500() throws Exception {
    doThrow(new IllegalStateException("cart.add 에 placeId 가 없다")).when(effects).apply(any(), any());

    mvc.perform(chat("ko"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }

  @Test
  @DisplayName("마법사 — X-Device-Id 없이 200, 가입 판정도 처리기도 안 거친다")
  void planNeedsNoIdentity() throws Exception {
    mvc.perform(
            post("/guide/plan")
                .header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titles\":[\"도깨비\"],\"days\":2,\"pace\":\"relaxed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tookSeconds").value(0.02));

    verify(agent).plan(any(), eq(Lang.JA));
    verify(users, never()).isRegistered(any());
    verify(effects, never()).apply(any(), any());
  }

  @Test
  @DisplayName("마법사 — days 가 범위 밖(8)이면 400, 에이전트를 부르지 않는다")
  void planOutOfRangeIs400() throws Exception {
    mvc.perform(
            post("/guide/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titles\":[\"도깨비\"],\"days\":8}"))
        .andExpect(status().isBadRequest());

    verify(agent, never()).plan(any(), any());
  }
}
