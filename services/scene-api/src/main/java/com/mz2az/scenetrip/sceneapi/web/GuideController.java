package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.GuideApi;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatRequest;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanRequest;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.guide.GuideEffectApplier;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 여행 가이드 — 챗봇 한 턴과 일정짜기 마법사.
 *
 * <p><b>이 서비스는 프록시다</b>(ADR 0013). 챗봇의 LLM 호출·도구·프롬프트·코스 계산은 전부 {@code agents/trip-guide} 에 있고, 여기는
 * 앱의 요청을 에이전트에 넘기고 응답을 <b>옮겨 담지 않고</b> 돌려준다. 이 클래스에 프롬프트 문자열·모델 ID·도구 정의가 생기면 0012 로 되돌아간 것이다.
 *
 * <p>하는 일은 통제와 조립뿐이다. 챗봇은 <b>가입한 사용자만</b> 부를 수 있다 — 턴마다 모델 토큰이 나가는 유료 경로라 호출 여부를 서버가 정한다({@code
 * NavigationController} 와 같은 이유). 판정은 에이전트를 부르기 <b>전</b>이라 미가입 요청은 비용 없이 걸러진다.
 *
 * <p>에이전트는 신원을 모른다. {@code X-Device-Id} 로 찾은 계정은 응답의 {@code effects}(장바구니 쪽지)를 DB 에 적용할 때만 쓰고, 요청
 * 객체에는 애초에 없다 — 헤더를 인터페이스가 따로 떼어 주기 때문에 구조로 지켜진다.
 *
 * <p>{@code try/catch} 가 없는 이유는 {@code NavigationController} 와 같다 — 클라이언트가 던진 {@code
 * ApiException}(400·503)과 처리기가 던진 {@code IllegalStateException}(500)은 {@code ApiExceptionHandler} 가
 * 받는다.
 *
 * <p>마법사({@link #planWithGuide})는 모델을 안 부르는 창구라 가입 조건도 {@code X-Device-Id} 도 없고, 저장도 없다 — 응답은 초안이고
 * 저장은 사용자의 「완료」가 부르는 {@code PUT /courses/{id}} 뿐이다.
 */
@RestController
class GuideController implements GuideApi {

  private final GuideAgentClient agent;
  private final GuideEffectApplier effects;
  private final UserStore users;

  GuideController(GuideAgentClient agent, GuideEffectApplier effects, UserStore users) {
    this.agent = agent;
    this.effects = effects;
    this.users = users;
  }

  @Override
  public ResponseEntity<GuideChatReply> chatWithGuide(
      UUID xDeviceId, GuideChatRequest request, Lang acceptLanguage) {

    UUID user = users.resolve(xDeviceId);
    if (!users.isRegistered(user)) {
      throw ApiException.signInRequired("SIGN_IN_REQUIRED", "이 동작은 가입한 사용자만 할 수 있습니다");
    }

    GuideChatReply reply = agent.chat(request, acceptLanguage);
    // cart.* 만 DB 에. plan.* 과 ui 는 손대지 않고 앱으로 간다.
    effects.apply(user, reply.getEffects());
    return ResponseEntity.ok(reply);
  }

  @Override
  public ResponseEntity<GuidePlanReply> planWithGuide(
      GuidePlanRequest request, Lang acceptLanguage) {
    return ResponseEntity.ok(agent.plan(request, acceptLanguage));
  }
}
