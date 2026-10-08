package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.GuideApi;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatRequest;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanRequest;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.guide.GuideEffectApplier;
import com.mz2az.scenetrip.sceneapi.guide.IdempotencyStore;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 여행 가이드 — 챗봇 한 턴과 일정짜기 마법사.
 *
 * <p><b>이 서비스는 프록시다</b>(ADR 0013). 챗봇의 LLM 호출·도구·프롬프트·코스 계산은 전부 {@code agents/trip-guide} 에 있고, 여기는
 * 앱의 요청을 에이전트에 넘기고 응답을 <b>옮겨 담지 않고</b> 돌려준다. 이 클래스에 프롬프트 문자열·모델 ID·도구 정의가 생기면 0012 로 되돌아간 것이다.
 *
 * <p>하는 일은 통제와 조립뿐이다. 챗봇은 <b>가입한 사용자만</b> 부를 수 있다 — 턴마다 모델 토큰이 나가는 유료 경로라 호출 여부를 서버가 정한다({@code
 * NavigationController} 와 같은 이유). 판정은 에이전트를 부르기 <b>전</b>이라 미가입 요청은 비용 없이 걸러진다.
 *
 * <p>에이전트는 신원을 모른다. {@code X-Install-Id} 로 찾은 계정은 응답의 {@code effects}(장바구니 쪽지)를 DB 에 적용할 때만 쓰고, 요청
 * 객체에는 애초에 없다 — 헤더를 인터페이스가 따로 떼어 주기 때문에 구조로 지켜진다.
 *
 * <p>{@code try/catch} 가 없는 이유는 {@code NavigationController} 와 같다 — 클라이언트가 던진 {@code
 * ApiException}(400·503)과 처리기가 던진 {@code IllegalStateException}(500)은 {@code ApiExceptionHandler} 가
 * 받는다.
 *
 * <p>마법사({@link #planWithGuide})는 모델을 안 부르는 창구라 가입 조건도 {@code X-Install-Id} 도 없고, 저장도 없다 — 응답은 초안이고
 * 저장은 사용자의 「완료」가 부르는 {@code PUT /courses/{id}} 뿐이다.
 */
@RestController
class GuideController implements GuideApi {

  private final GuideAgentClient agent;
  private final GuideEffectApplier effects;
  private final UserStore users;
  private final CurrentAccount accounts;
  private final PaidQuota quota;
  private final IdempotencyStore idempotency;
  private final JsonMapper json;

  GuideController(
      GuideAgentClient agent,
      GuideEffectApplier effects,
      UserStore users,
      CurrentAccount accounts,
      PaidQuota quota,
      IdempotencyStore idempotency,
      JsonMapper json) {
    this.agent = agent;
    this.effects = effects;
    this.users = users;
    this.accounts = accounts;
    this.quota = quota;
    this.idempotency = idempotency;
    this.json = json;
  }

  @Override
  public ResponseEntity<GuideChatReply> chatWithGuide(
      UUID xInstallId, GuideChatRequest request, Lang acceptLanguage, String idempotencyKey) {

    UUID user = accounts.resolve(xInstallId);
    if (!users.isRegistered(user)) {
      throw ApiException.signInRequired("SIGN_IN_REQUIRED", "이 동작은 가입한 사용자만 할 수 있습니다");
    }

    // 멱등 키(계약 1.5.0, rate-limit.md §5). 같은 키는 한 번만 처리하고 그 뒤에는 저장한 답을 준다 —
    // 모델을 다시 부르지 않고 한도도 깎지 않는다. 키가 없으면 예전처럼 매번 처리한다.
    String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
    if (key != null) {
      switch (idempotency.begin(user, key, requestHash(request, acceptLanguage))) {
        case IdempotencyStore.Begin.Replay r -> {
          return ResponseEntity.ok().body(json.readValue(r.responseJson(), GuideChatReply.class));
        }
        case IdempotencyStore.Begin.InProgress i ->
            throw ApiException.conflict(
                "IDEMPOTENCY_IN_PROGRESS", "같은 Idempotency-Key 의 턴이 아직 처리 중입니다");
        case IdempotencyStore.Begin.Mismatch m ->
            throw ApiException.unprocessable(
                "IDEMPOTENCY_KEY_REUSED", "같은 Idempotency-Key 로 다른 내용을 보냈습니다");
        case IdempotencyStore.Begin.Started s -> {}
      }
    }

    // 한도는 모델을 부르기 직전에 센다. 에이전트가 답하지 못하면 되돌린다 — 사용자 몫이 아니다.
    PaidQuota.Grant grant;
    try {
      grant = quota.consume(user, PaidQuota.Feature.GUIDE_CHAT);
    } catch (PaidQuota.LimitExceeded e) {
      if (key != null) {
        idempotency.abandon(user, key);
      }
      throw QuotaResponses.tooMany(e);
    }
    GuideChatReply reply;
    try {
      reply = agent.chat(request, acceptLanguage);
    } catch (RuntimeException e) {
      quota.refund(grant);
      if (key != null) {
        idempotency.abandon(user, key);
      }
      throw e;
    }
    try {
      // cart.* 만 DB 에. plan.* 과 ui 는 손대지 않고 앱으로 간다.
      effects.apply(user, reply.getEffects());
    } catch (RuntimeException e) {
      // 모델은 이미 답했다(토큰이 나갔다) — 한도는 되돌리지 않는다. 키만 지워 재시도가 다시 처리되게.
      if (key != null) {
        idempotency.abandon(user, key);
      }
      throw e;
    }
    if (key != null) {
      idempotency.complete(user, key, json.writeValueAsString(reply));
    }
    return QuotaResponses.ok(grant).body(reply);
  }

  /** 같은 키에 같은 내용인가를 가리는 지문 — 본문과 언어. */
  private String requestHash(GuideChatRequest request, Lang lang) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update(json.writeValueAsBytes(request));
      sha.update((lang == null ? "" : lang.getValue()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public ResponseEntity<GuidePlanReply> planWithGuide(
      GuidePlanRequest request, Lang acceptLanguage) {
    return ResponseEntity.ok(agent.plan(request, acceptLanguage));
  }
}
