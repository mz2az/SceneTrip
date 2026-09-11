package com.mz2az.scenetrip.sceneapi.guide;

import com.mz2az.scenetrip.sceneapi.api.model.ApiError;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatRequest;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanRequest;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.web.ApiException;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 가이드 에이전트({@code agents/trip-guide})를 부르는 유일한 곳.
 *
 * <p><b>전송과 예외 번역만 한다.</b> 요청은 생성된 모델을 그대로 JSON 으로 보내고, 응답은 생성된 모델로 읽어 그대로 돌려준다 — 프록시는 옮겨 담지
 * 않는다(ADR 0013). 그래서 앱이 보낸 {@code context.plan} 이 손대지 않고 에이전트까지 가고, 에이전트가 계약과 다른 모양을 주면 여기서 깨진다.
 * 그것이 의도다 — 조용히 맞춰 주면 어긋난 채로 굴러간다.
 *
 * <p>{@code KakaoRoutingClient} 와 같은 구조다. {@link RestClient} 를 직접 만드는 이유도 그쪽 머리말과 같다(Boot 4 의
 * {@code spring-boot-restclient} 자동 구성을 받지 않는다). 다른 점 둘 — GET 이 아니라 JSON 몸체를 POST 하고, 에이전트의 400 은
 * 앱의 잘못이라 <b>그대로 400</b> 으로 돌려준다(카카오의 400 은 우리 결함이라 500 으로 보냈다).
 *
 * <p><b>타임아웃은 벽이다.</b> 안에서 모델을 몇 번 부르는지는 모르고 알 필요도 없다. 에이전트가 자기 턴 예산(30초) 안에서 스스로 503 을 내는 것이 정상
 * 경로이고, 여기의 응답 상한(40초)은 그 역할을 못 했을 때의 안전망이다. 연결은 3초 — 연결이 안 되면 에이전트가 없는 것이라 40초를 기다릴 이유가 없다. 원칙은
 * 「안쪽이 바깥보다 먼저 포기한다」: 에이전트 30 < 여기 40 < 앱 50 (계획 §2-2).
 *
 * <p><b>재시도하지 않는다.</b> {@code /guide/chat} 은 멱등이 아니다 — 모델 토큰을 쓰고, 에이전트 세션 이력이 쌓이고, {@code cart.add}
 * 가 두 번 올 수 있다. 실패하면 503 을 내고 사용자가 다시 누르게 둔다.
 *
 * <p>여기서 던지는 것은 전부 {@link ApiException} 이고 {@code ApiExceptionHandler} 가 받는다. 무엇이 문제였는지는 로그 레벨과 문구로
 * 가른다 — 앱은 「잠시 뒤 다시」만 알면 되고 운영자는 로그를 본다. 키는 없다. 모델 키는 에이전트가 들고 있고 이 서비스는 모른다.
 */
@Component
public class GuideAgentClient {

  private static final Logger log = LoggerFactory.getLogger(GuideAgentClient.class);

  /** 계약 {@code docs/api/errors.md} 의 코드. 에이전트가 응답하지 않는 모든 경우. */
  public static final String CODE_UNAVAILABLE = "GUIDE_UNAVAILABLE";

  /** 에이전트가 400 을 주면서 {@code code} 를 안 줬을 때의 기본값. 계약의 400 코드다. */
  private static final String CODE_INVALID = "INVALID_PARAMETER";

  /**
   * 에이전트의 경로. scene-api 의 {@code /guide/chat} 은 이쪽 {@code /guide/chat} 으로, {@code /guide/plan} 은
   * {@code /plan} 으로.
   */
  static final String PATH_CHAT = "/guide/chat";

  static final String PATH_PLAN = "/plan";

  /** {@code base-url} 이 비어 있으면 null. 부를 때 503 을 낸다 — 챗봇 없이도 나머지 API 는 돌아야 한다. */
  private final RestClient restClient;

  /**
   * 설정을 받아 클라이언트를 한 번 만든다. 싱글턴이라 이 생성자는 기동 때 한 번 돈다.
   *
   * @param baseUrl 에이전트 주소. 테스트에서 로컬 가짜 서버로 돌리기 위해 설정으로 뺐다. 비어 있어도 기동은 된다.
   * @param timeoutSeconds 응답을 기다리는 상한(벽). 에이전트 턴 예산보다 길고 앱 타임아웃보다 짧다.
   * @param observations 액추에이터가 만들어 두는 측정 장부. 요청마다 경로·시간·상태가 기록돼 SigNoz 로 간다 — 타임아웃 값을 실측으로 정하는 근거가
   *     여기서 나온다.
   */
  public GuideAgentClient(
      @Value("${scenetrip.guide.agent-base-url:}") String baseUrl,
      @Value("${scenetrip.guide.timeout-seconds}") int timeoutSeconds,
      ObservationRegistry observations) {
    String url = baseUrl == null ? "" : baseUrl.trim();
    if (url.isEmpty()) {
      log.warn("가이드 에이전트 주소가 없습니다 — 기동은 하지만 /guide/* 는 503 을 냅니다 (SCENETRIP_GUIDE_AGENT_BASE_URL)");
      this.restClient = null;
      return;
    }
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
    this.restClient =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .observationRegistry(observations)
            .build();
  }

  /** 챗봇 한 턴. 요청은 앱이 보낸 것 그대로 — {@code X-Device-Id} 는 여기 없다. 에이전트는 신원을 모른다. */
  public GuideChatReply chat(GuideChatRequest request, Lang lang) {
    return post(PATH_CHAT, request, lang, GuideChatReply.class);
  }

  /** 마법사의 일정 초안. 모델을 부르지 않는 창구라 빠르고, 저장도 없다. */
  public GuidePlanReply plan(GuidePlanRequest request, Lang lang) {
    return post(PATH_PLAN, request, lang, GuidePlanReply.class);
  }

  /**
   * 두 창구의 공통 호출부. 전송·헤더·예외 번역이 여기 있다.
   *
   * @param path URI 템플릿 문자열 그대로 — 관측의 {@code uri} 태그가 이 문자열을 쓴다.
   */
  private <T> T post(String path, Object body, Lang lang, Class<T> type) {
    if (restClient == null) {
      log.error("가이드 에이전트 주소가 없어 부를 수 없습니다 — SCENETRIP_GUIDE_AGENT_BASE_URL 을 설정하세요");
      throw ApiException.unavailable(CODE_UNAVAILABLE, "가이드 에이전트 설정이 없습니다");
    }
    try {
      T reply =
          restClient
              .post()
              .uri(path)
              .header("Accept-Language", lang == null ? Lang.KO.getValue() : lang.getValue())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(type);
      if (reply == null || !isContractShaped(reply)) {
        // 200 인데 몸체가 계약 모양이 아니다. Jackson 은 모르는 필드를 무시하고 없는 필드를 null 로 두므로
        // {"error": …} 같은 것도 오류 없이 빈 객체가 된다 — 2026-09-10 실측(MZ2AZ-321): 모델 키가
        // 없을 때 에이전트가 {"error"} 를 200 으로 줬고 그것이 빈 {} 로 앱까지 갔다. 에이전트는
        // 그 뒤 503 으로 고쳤지만, 같은 종류의 다음 실수를 여기서 막는다 — 앱에는 「잠시 뒤 다시」.
        log.warn("가이드 에이전트 응답이 계약 모양이 아닙니다: {}", path);
        throw ApiException.unavailable(CODE_UNAVAILABLE, "가이드 에이전트의 응답을 읽지 못했습니다");
      }
      return reply;
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 400) {
        // 앱이 잘못 보낸 것이다 — 작품 못 찾음, 일수 범위 밖, context.plan 모양 오류. 재시도해도 같으니
        // 그대로 400. 에이전트가 계약 모양 {code, message} 으로 주므로 그것을 살린다.
        ApiError err = readError(e);
        String code = err != null && err.getCode() != null ? err.getCode() : CODE_INVALID;
        String message =
            err != null && err.getMessage() != null ? err.getMessage() : "가이드 요청이 잘못됐습니다";
        throw ApiException.badRequest(code, message);
      }
      log.warn("가이드 에이전트가 {} 를 돌려줬습니다: {}", e.getStatusCode().value(), path);
      throw ApiException.unavailable(CODE_UNAVAILABLE, "가이드 에이전트가 요청을 거부했습니다");
    } catch (HttpServerErrorException e) {
      // 에이전트가 스스로 낸 503(모델 안 뜸·턴 예산 초과)이 정상 경로다. 그 message 는 사람이 읽는 것이라 살린다.
      ApiError err = readError(e);
      String message =
          err != null && err.getMessage() != null ? err.getMessage() : "가이드 에이전트가 응답하지 못했습니다";
      log.warn("가이드 에이전트 {}: {} — {}", e.getStatusCode().value(), path, message);
      throw ApiException.unavailable(CODE_UNAVAILABLE, message);
    } catch (ResourceAccessException e) {
      // 연결 거부·연결 시간 초과·응답 시간 초과. 에이전트 프로세스가 없거나 벽(40초)을 넘긴 것이다.
      log.warn("가이드 에이전트에 닿지 못했습니다: {} — {}", path, e.getMessage());
      throw ApiException.unavailable(CODE_UNAVAILABLE, "가이드 에이전트에 연결하지 못했습니다");
    } catch (RestClientException e) {
      // 응답은 왔는데 계약 모델로 읽지 못했다 — 에이전트가 계약을 어긴 것이다. MZ2AZ-320.
      log.warn("가이드 에이전트 응답을 읽지 못했습니다: {} — {}", path, e.getMessage());
      throw ApiException.unavailable(CODE_UNAVAILABLE, "가이드 에이전트의 응답을 읽지 못했습니다");
    }
  }

  /**
   * 계약이 필수라고 한 필드가 채워져 있는가. {@code GuideChatReply} 는 {@code reply}·{@code effects}·{@code ui},
   * {@code GuidePlanReply} 는 {@code plan}·{@code effects}·{@code ui}. 생성된 모델은 필수 필드에도 기본값(null 또는 빈
   * 목록)을 두어 역직렬화가 실패하지 않으므로 여기서 본다.
   */
  private static boolean isContractShaped(Object reply) {
    if (reply instanceof GuideChatReply r) {
      return r.getReply() != null && r.getEffects() != null && r.getUi() != null;
    }
    if (reply instanceof GuidePlanReply r) {
      return r.getPlan() != null && r.getEffects() != null && r.getUi() != null;
    }
    return true;
  }

  /** 오류 몸체를 계약 모양으로 읽는다. 못 읽으면 null — 그때는 기본 문구로 간다. */
  private static ApiError readError(RestClientResponseException e) {
    try {
      return e.getResponseBodyAs(ApiError.class);
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
