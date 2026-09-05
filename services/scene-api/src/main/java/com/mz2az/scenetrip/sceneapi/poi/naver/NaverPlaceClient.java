package com.mz2az.scenetrip.sceneapi.poi.naver;

import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * 네이버 장소와 HTTP 로 말하는 유일한 클래스. 검색(후보 5 개)과 상세(사진·영업시간·평점) 둘뿐이다.
 *
 * <p><b>비공식이다.</b> 네이버 지도 웹페이지가 자기 화면을 그리려고 부르는 내부 주소를, 그 페이지인 척하는 헤더를 붙여 부른다. 공식 지역검색 API 는 장소 id
 * 를 주지 않아 사진·평점에 닿을 수 없다. 데모 한정이고 정식 출시 전에 대체한다 — ADR 0011. 그래서 이 클래스는:
 *
 * <ul>
 *   <li><b>절대 던지지 않는다.</b> 카드가 뜨는 화면이 바깥 사정으로 죽으면 안 된다. 실패는 {@link Outcome#failed} 로 답한다.
 *   <li><b>스위치가 있다.</b> {@code scenetrip.naver.enabled=false} 면 부르지도 않는다. 데모 중 막히면 내린다.
 *   <li><b>막힌 것을 구분한다.</b> 403·429 는 {@code blocked} — 뒤에서 채우는 일꾼이 쉬어야 할 신호다. 타임아웃·5xx 는 이번만 실패.
 * </ul>
 *
 * <p>상태가 없다. 요청 스레드와 일꾼 스레드가 같은 인스턴스를 같이 쓴다.
 */
@Component
public class NaverPlaceClient {

  private static final Logger log = LoggerFactory.getLogger(NaverPlaceClient.class);

  /**
   * 한 번 부른 결과. 성공이면 {@code value}, 실패면 {@code why} 와 {@code blocked}.
   *
   * @param value 성공했을 때의 값
   * @param why 실패 이유 한 줄
   * @param blocked 출처가 막았다(403·429). 이번만이 아니라 한동안 안 될 신호
   */
  public record Outcome<T>(T value, String why, boolean blocked) {
    public boolean ok() {
      return value != null;
    }

    static <T> Outcome<T> ok(T value) {
      return new Outcome<>(value, null, false);
    }

    static <T> Outcome<T> failed(String why, boolean blocked) {
      return new Outcome<>(null, why, blocked);
    }
  }

  /**
   * 상세. 출처에 없는 값은 null 이다.
   *
   * @param name 출처가 부르는 이름
   * @param category 분류 문구
   * @param address 도로명, 없으면 지번
   * @param phone 전화
   * @param hours 영업시간 문구
   * @param score 별점. 없을 수 있다
   * @param reviewCount 방문자 리뷰 수
   * @param blogReviews 블로그 리뷰 수
   * @param images 사진 URL 최대 3
   * @param url 장소 페이지
   */
  public record Detail(
      String name,
      String category,
      String address,
      String phone,
      String hours,
      Double score,
      Integer reviewCount,
      Integer blogReviews,
      List<String> images,
      String url) {}

  /** 검색 GraphQL. 프로토타입이 브라우저 개발자 도구에서 베낀 것 그대로. */
  private static final String SEARCH_QUERY =
      """
      query getPlacesList($input: PlaceExternalListInput) {
        placeList(input: $input) {
          businesses {
            total
            items {
              id
              name
              address { roadAddress address }
              coordinate { latitude longitude }
            }
          }
        }
      }
      """;

  /** 갤럭시 크롬. 이 헤더가 「폰 브라우저인 척」의 실체다. */
  private static final String USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/138.0.0.0 Mobile Safari/537.36";

  private static final String PLACE_PAGE = "https://map.naver.com/p/entry/place/";
  private static final int MAX_IMAGES = 3;
  private static final Pattern DIGITS = Pattern.compile("[\\d,]+");

  private final boolean enabled;
  private final String searchUrl;
  private final String detailUrl;
  private final RestClient http;

  NaverPlaceClient(
      @Value("${scenetrip.naver.enabled:true}") boolean enabled,
      @Value("${scenetrip.naver.search-url}") String searchUrl,
      @Value("${scenetrip.naver.detail-url}") String detailUrl,
      @Value("${scenetrip.naver.timeout-seconds:3}") int timeoutSeconds) {
    this.enabled = enabled;
    this.searchUrl = searchUrl;
    this.detailUrl = detailUrl;
    Duration timeout = Duration.ofSeconds(timeoutSeconds);
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
    factory.setReadTimeout(timeout);
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  /** 이름(+주소)으로 후보 최대 5 개. 0 건은 실패가 아니라 빈 목록이다. */
  public Outcome<List<Candidate>> search(String query) {
    if (!enabled) {
      return Outcome.failed("네이버 조회가 꺼져 있다 (scenetrip.naver.enabled=false)", false);
    }
    Map<String, Object> body =
        Map.of(
            "operationName",
            "getPlacesList",
            "variables",
            Map.of(
                "input",
                Map.of(
                    "query",
                    query,
                    "businessType",
                    "place",
                    "start",
                    1,
                    "display",
                    5,
                    "deviceType",
                    "MOBILE")),
            "query",
            SEARCH_QUERY);
    try {
      JsonNode root =
          http.post()
              .uri(searchUrl)
              .contentType(MediaType.APPLICATION_JSON)
              .header("User-Agent", USER_AGENT)
              .header("Accept", "application/json, text/plain, */*")
              .header("Origin", "https://m.place.naver.com")
              .header("Referer", "https://m.place.naver.com/place/list")
              .header("apollographql-client-name", "place-search-service")
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      List<Candidate> out = new ArrayList<>();
      for (JsonNode item : path(root, "data", "placeList", "businesses", "items")) {
        JsonNode coord = item.path("coordinate");
        out.add(
            new Candidate(
                text(item.path("id")),
                text(item.path("name")),
                number(coord.path("latitude")),
                number(coord.path("longitude"))));
      }
      return Outcome.ok(out);
    } catch (RestClientResponseException e) {
      return failure("검색", e.getStatusCode(), e);
    } catch (RestClientException e) {
      return failure("검색", null, e);
    }
  }

  /** 장소 id 로 상세. 상세가 비어 있으면 실패다 — id 가 낡았거나 형식이 바뀐 것. */
  public Outcome<Detail> detail(String naverId) {
    if (!enabled) {
      return Outcome.failed("네이버 조회가 꺼져 있다 (scenetrip.naver.enabled=false)", false);
    }
    try {
      JsonNode root =
          http.get()
              .uri(detailUrl, naverId)
              .header("User-Agent", USER_AGENT)
              .header("Accept", "application/json, text/plain, */*")
              .header("Referer", PLACE_PAGE + naverId)
              .retrieve()
              .body(JsonNode.class);
      JsonNode det = path(root, "data", "placeDetail");
      if (det.isMissingNode() || det.isNull() || det.isEmpty()) {
        return Outcome.failed("상세가 비어 있다 — id " + naverId, false);
      }
      JsonNode reviews = det.path("visitorReviews");
      List<String> images = new ArrayList<>();
      for (JsonNode im : det.path("images").path("images")) {
        String origin = text(im.path("origin"));
        if (origin != null && images.size() < MAX_IMAGES) {
          images.add(origin);
        }
      }
      String phone = text(det.path("phone"));
      if (phone == null) {
        phone = text(det.path("virtualPhone"));
      }
      JsonNode address = det.path("address");
      String road = text(address.path("roadAddress"));
      return Outcome.ok(
          new Detail(
              text(det.path("name")),
              text(det.path("category").path("category")),
              road != null ? road : text(address.path("address")),
              phone,
              text(det.path("businessHours").path("description")),
              number(reviews.path("score")),
              parseReviewCount(text(reviews.path("displayText"))),
              integer(det.path("blogReviews").path("total")),
              List.copyOf(images),
              PLACE_PAGE + naverId));
    } catch (RestClientResponseException e) {
      return failure("상세", e.getStatusCode(), e);
    } catch (RestClientException e) {
      return failure("상세", null, e);
    }
  }

  /** 「방문자 리뷰 5,056」 → 5056. 숫자가 없으면 null. */
  static Integer parseReviewCount(String text) {
    if (text == null) {
      return null;
    }
    Matcher m = DIGITS.matcher(text);
    if (!m.find()) {
      return null;
    }
    try {
      return Integer.parseInt(m.group().replace(",", ""));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static <T> Outcome<T> failure(String what, HttpStatusCode status, Exception e) {
    boolean blocked = status != null && (status.value() == 403 || status.value() == 429);
    String why =
        status == null
            ? "네이버 " + what + " 실패 — " + e.getClass().getSimpleName()
            : "네이버 " + what + " 실패 — HTTP " + status.value();
    if (blocked) {
      log.warn("네이버가 막았다 — {}", why);
    } else {
      log.info("{}", why);
    }
    return Outcome.failed(why, blocked);
  }

  private static JsonNode path(JsonNode node, String... keys) {
    JsonNode n = node;
    for (String k : keys) {
      n = n.path(k);
    }
    return n;
  }

  private static String text(JsonNode n) {
    return n.isMissingNode() || n.isNull() ? null : n.asString();
  }

  private static Double number(JsonNode n) {
    return n.isNumber() ? n.asDouble() : null;
  }

  private static Integer integer(JsonNode n) {
    return n.isNumber() ? n.asInt() : null;
  }
}
