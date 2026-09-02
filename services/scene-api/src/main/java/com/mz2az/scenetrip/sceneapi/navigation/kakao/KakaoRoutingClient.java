package com.mz2az.scenetrip.sceneapi.navigation.kakao;

import com.mz2az.scenetrip.sceneapi.navigation.Coordinate;
import com.mz2az.scenetrip.sceneapi.web.ApiException;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 길찾기를 부르는 유일한 곳.
 *
 * <p><b>전송만 한다.</b> 요청을 보내고 JSON 을 record 로 받아 그대로 돌려준다. 응답의 {@code status} 가 {@code OK} 인지 {@code
 * NO_RESULTS} 인지는 보지 않는다 — 그것은 「없으면 걷게 하자」「같은 점이면 도착이다」 같은 <i>길찾기 규칙</i>이고, 규칙은 {@code
 * NextLegPlanner} 에 있다. 이 클래스가 바뀌는 이유는 카카오가 API 를 바꿀 때뿐이어야 한다.
 *
 * <p>여기서 예외를 던지는 것은 <b>전송 자체가 실패했을 때뿐</b>이다 — 연결이 안 되거나, 시간을 넘기거나, 카카오가 4xx·5xx 를 주거나, JSON 이 깨졌을
 * 때. 전부 503 {@code ROUTING_UNAVAILABLE} 로 나가고, 무엇이 문제였는지는 로그 레벨과 문구로 가른다. 앱은 「잠시 뒤 다시」만 알면 되고 운영자는
 * 로그를 본다. 예외는 카카오가 400 을 준 경우다 — 우리가 파라미터를 잘못 보낸 것이라 그대로 던져 500 그물로 보낸다. 재시도해도 같으니 「일시 장애」가 아니다.
 *
 * <p>이 저장소의 <b>첫 외부 HTTP</b> 다. {@link RestClient} 를 직접 만드는 이유: Spring Boot 4 는 자동 구성을 모듈로 쪼갰고
 * {@code RestClient.Builder} 빈을 주는 모듈({@code spring-boot-restclient})을 받지 않고 있다. {@code spring-web}
 * 만으로 되므로 그렇게 한다. 그 결과 Boot 가 설정한 ObjectMapper 가 아니라 기본 ObjectMapper 를 쓴다 — 받기만 하니 상관없고, 모르는 필드 무시는
 * 응답 record 에 직접 박혀 있다.
 *
 * <p><b>관측은 두 겹이다.</b> OTel 에이전트가 밑의 JDK {@code HttpClient} 를 계측해 스팬과 {@code
 * http.client.request.duration} 을 낸다 — 코드 없이 된다. 다만 그 메트릭에는 경로 태그가 없어(표준이 카디널리티 때문에 뺐다) 대중교통과 도보가 한
 * 숫자로 합쳐진다. 쿼터는 둘이 따로이고 이어붙이기 때문에 도보가 대중교통의 두 배 가까이 나가므로 갈라 봐야 한다. 그래서 Spring 관측({@code
 * http.client.requests}, {@code uri} 태그)을 함께 켠다. URI 를 템플릿 문자열로 주는 이유가 이것이다 — 빌더로 조립하면 태그가 {@code
 * none} 이 된다.
 *
 * <p>키는 <b>로그에 남기지 않는다.</b> 거부됐다는 사실만 남긴다.
 */
@Component
public class KakaoRoutingClient {

  private static final Logger log = LoggerFactory.getLogger(KakaoRoutingClient.class);

  /** 카카오 문서의 응답 상태값. {@code OK} 가 아니어도 HTTP 200 이다 — 그래서 여기서 보지 않고 Planner 가 읽는다. */
  public static final String STATUS_OK = "OK";

  public static final String STATUS_NO_RESULTS = "NO_RESULTS";
  public static final String STATUS_EQUAL_POINTS = "EQUAL_POINTS";
  public static final String STATUS_START_NODES_NULL = "STARTNODES_NULL";
  public static final String STATUS_END_NODES_NULL = "ENDNODES_NULL";

  private static final String CODE_UNAVAILABLE = "ROUTING_UNAVAILABLE";

  private final RestClient restClient;
  private final String key;

  /**
   * @param baseUrl 카카오 주소. 테스트에서 로컬 가짜 서버로 돌리기 위해 설정으로 뺐다.
   * @param key REST API 키. 비어 있어도 기동은 된다 — 길찾기 없이도 나머지 API 는 돌아야 하므로, 부를 때 503 을 낸다.
   * @param timeoutSeconds 응답을 기다리는 상한. 앱이 쓰던 12초와 같다.
   * @param observations 액추에이터가 만들어 두는 장부. 여기 기록된 것이 Micrometer → OTel 브리지를 거쳐 SigNoz 로 간다.
   */
  public KakaoRoutingClient(
      @Value("${scenetrip.navigation.kakao.base-url}") String baseUrl,
      @Value("${scenetrip.navigation.kakao.rest-key:}") String key,
      @Value("${scenetrip.navigation.kakao.timeout-seconds}") int timeoutSeconds,
      ObservationRegistry observations) {
    this.key = key == null ? "" : key.trim();
    if (this.key.isEmpty()) {
      log.warn("카카오 길찾기 키가 없습니다 — 기동은 하지만 길찾기 호출은 503 을 냅니다 (KAKAO_REST_KEY)");
    }
    // 연결은 짧게, 응답은 넉넉히. 카카오가 아예 안 뜨는 것은 연결 단계에서 빨리 아는 편이 낫고,
    // 뜬 뒤에는 경로 계산에 시간이 걸릴 수 있다.
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
    this.restClient =
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .observationRegistry(observations)
            .build();
  }

  /** 대중교통. 후보를 최대 15개 준다. 양 끝 도보는 step 에 없고 합계에만 들어 있다 — 응답 record 의 Javadoc 참고. */
  public KakaoTransitResponse transit(Coordinate from, Coordinate to) {
    return call("publictraffic", from, to, KakaoTransitResponse.class);
  }

  /** 도보. 실제 골목을 따라가는 좌표와 턴바이턴 안내문이 온다. */
  public KakaoWalkResponse walk(Coordinate from, Coordinate to) {
    return call("walk", from, to, KakaoWalkResponse.class);
  }

  /**
   * @param kind {@code publictraffic} 또는 {@code walk}. URI 템플릿의 {@code {kind}} 자리에 들어가고, 관측 태그에는
   *     템플릿 그대로 찍힌다.
   */
  private <T> T call(String kind, Coordinate from, Coordinate to, Class<T> type) {
    if (key.isEmpty()) {
      log.error("카카오 길찾기 키가 없어 부를 수 없습니다 — KAKAO_REST_KEY 를 설정하세요");
      throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자 설정이 없습니다");
    }
    try {
      T body =
          restClient
              .get()
              // 카카오는 x 가 경도, y 가 위도다. Coordinate 가 이름으로 들고 있어 여기서 뒤집힐 일이 없다.
              // 템플릿 문자열인 이유는 머리말 참고 — 관측의 uri 태그가 이 문자열을 그대로 쓴다.
              .uri(
                  "/v2/routing/{kind}?start_x={sx}&start_y={sy}&end_x={ex}&end_y={ey}"
                      + "&s_name={sn}&e_name={en}&input_coord=WGS84&output_coord=WGS84",
                  kind,
                  from.lng(),
                  from.lat(),
                  to.lng(),
                  to.lat(),
                  "출발",
                  "도착")
              .header("Authorization", "KakaoAK " + key)
              .retrieve()
              .body(type);
      if (body == null) {
        log.warn("카카오 길찾기 응답이 비어 있습니다: {}", kind);
        throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자가 빈 응답을 보냈습니다");
      }
      return body;
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      if (status == 429) {
        // 하루 1,000건. 여기가 찍히기 시작하면 유료 전환이나 호출 억제를 정할 때다.
        log.warn("카카오 길찾기 호출 한도 초과: {} {}", kind, status);
        throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자의 호출 한도를 넘었습니다");
      }
      if (status == 401 || status == 403) {
        log.error("카카오 길찾기 키가 거부됐습니다: {} {} — 키 값이나 앱 설정을 확인하세요", kind, status);
        throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자가 인증을 거부했습니다");
      }
      // 400 등 — 우리가 파라미터를 잘못 보낸 것이다. 재시도해도 같으므로 일시 장애가 아니라 결함이다.
      throw e;
    } catch (HttpServerErrorException e) {
      log.warn("카카오 길찾기가 서버 오류를 냈습니다: {} {}", kind, e.getStatusCode().value());
      throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자가 응답하지 못했습니다");
    } catch (ResourceAccessException e) {
      log.warn("카카오 길찾기에 닿지 못했습니다: {} — {}", kind, e.getMessage());
      throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자에 연결하지 못했습니다");
    } catch (RestClientException e) {
      // 응답은 왔는데 우리 record 로 읽지 못했다 — 카카오가 모양을 바꿨을 가능성이 크다.
      log.warn("카카오 길찾기 응답을 읽지 못했습니다: {} — {}", kind, e.getMessage());
      throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자의 응답을 읽지 못했습니다");
    }
  }
}
