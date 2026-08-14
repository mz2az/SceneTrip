package com.mz2az.scenetrip.sceneapi.course;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 장소 유형별 기본 체류시간.
 *
 * <p>고궁에서 90분, 카페에서 40분, 다리에서 15분. 목업이 정한 값이고 유형마다 다르다.
 *
 * <p><b>이 표를 서버가 든다.</b> 프론트가 들면 같은 값이 iOS·Android·서버 세 곳에 흩어지고, 하나만 고치면 기기마다 다른 시간이 붙는다. 클라이언트는
 * {@code dwellMinutes} 를 비워 보내기만 하면 되고 채워진 값은 응답으로 돌아온다.
 *
 * <p><b>DB 코드 표로 만들지 않은 이유</b>는 이 저장소가 데이터를 마이그레이션에 넣지 않기 때문이다(README §데이터 적재). 게다가 이것은 자주 만질 튜닝
 * 값이라 설정이 제자리다 — 배포 없이 ConfigMap 만 고쳐도 바뀐다.
 *
 * <p>수집된 {@code place.type} 은 닫힌 목록이 아니라 자유 문자열이고 지금 37종이다. 전부 적지 않고 <b>오래 머무는 곳과 스쳐 가는 곳만</b> 적은 뒤
 * 나머지는 기본값으로 보낸다. 모르는 유형이 들어와도 조용히 실패하지 않는다.
 */
@Component
@ConfigurationProperties(prefix = "scenetrip.course.dwell")
public class DwellDefaults {

  private int fallback = 60;
  private Map<String, Integer> byPlaceType = new HashMap<>();

  /**
   * 표를 YAML 맵이 아니라 <b>문자열 하나</b>로 받는다.
   *
   * <p>맵으로 두면 한글 키가 조용히 어긋난다. 스프링은 설정 이름을 완화 바인딩하면서 {@code [a-z0-9-]} 밖의 글자를 떨어내는데, 한글 키는 그 과정에서 전부
   * 빈 문자열이 되어 서로 충돌하고 맵이 아니라 스칼라가 들어온다 — 기동이 실패한다.
   *
   * <p>대괄호로 감싸는 것은 <b>답이 아니다.</b> YAML 에서 {@code [카페]} 는 "글자 그대로의 키" 표시가 아니라 원소 하나짜리 리스트라, 키가
   * {@code "[카페]"} 라는 문자열이 되어 조회가 영영 빗나간다. 기동은 성공하고 <b>모든 장소가 조용히 폴백값을 받는다</b> — 실제로 그렇게 만들었다가 다리
   * 15분·카페 40분이 전부 60분으로 나왔고, 실제 요청을 흘려 보기 전까지 아무도 몰랐다.
   *
   * <p>그래서 파싱을 우리가 한다. 형식이 눈에 보이고, 어긋나면 기동할 때 바로 죽는다.
   *
   * @param spec {@code 유형=분} 을 쉼표로 이은 것. 예: {@code 카페=40,다리=15}
   */
  public void setByPlaceType(String spec) {
    Map<String, Integer> parsed = new HashMap<>();
    for (String entry : spec.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int equals = trimmed.lastIndexOf('=');
      if (equals <= 0) {
        throw new IllegalArgumentException("체류시간 기본값의 형식이 '유형=분' 이 아닙니다: '" + trimmed + "'");
      }
      String type = trimmed.substring(0, equals).trim();
      int minutes = Integer.parseInt(trimmed.substring(equals + 1).trim());
      if (minutes < 15 || minutes > 180) {
        throw new IllegalArgumentException(
            "체류시간 기본값 " + type + "=" + minutes + " 이 계약의 범위(15~180분) 밖입니다");
      }
      parsed.put(type, minutes);
    }
    this.byPlaceType = parsed;
  }

  /**
   * 이 유형에 붙일 기본 체류시간.
   *
   * @param placeType 수집된 장소 유형. 직접 찍은 핀이거나 유형이 없으면 {@code null} 이고, 그때는 기본값이다.
   */
  public int forPlaceType(String placeType) {
    if (placeType == null) {
      return fallback;
    }
    return byPlaceType.getOrDefault(placeType, fallback);
  }

  public void setFallback(int fallback) {
    this.fallback = fallback;
  }

  /** 테스트가 표를 직접 넣을 때 쓴다. 설정에서 오는 길은 {@link #setByPlaceType(String)} 이다. */
  public void putAll(Map<String, Integer> table) {
    this.byPlaceType = new HashMap<>(table);
  }

  /**
   * 표가 몇 줄 실렸는지 기동할 때 남긴다.
   *
   * <p>이 표는 <b>비어도 서비스가 멀쩡히 돈다.</b> 모든 장소가 폴백값을 받을 뿐이라 오류도 경고도 나지 않는다. 실제로 두 번 그렇게 됐고(맵 키가 뭉개져서, 그
   * 다음엔 대괄호가 리스트로 읽혀서), 배포해 실제 요청을 흘려 보기 전까지 몰랐다. 한 줄이라도 로그에 남으면 그 상태를 기동 직후에 알아챌 수 있다.
   */
  @jakarta.annotation.PostConstruct
  void logLoadedTable() {
    org.slf4j.LoggerFactory.getLogger(DwellDefaults.class)
        .info(
            "체류시간 기본값 {}종 적재 (폴백 {}분){}",
            byPlaceType.size(),
            fallback,
            byPlaceType.isEmpty() ? " — 표가 비어 모든 장소가 폴백을 받는다" : "");
  }
}
