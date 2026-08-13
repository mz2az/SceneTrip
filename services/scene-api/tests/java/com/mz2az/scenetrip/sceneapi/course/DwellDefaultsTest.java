package com.mz2az.scenetrip.sceneapi.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

/**
 * 장소 유형별 기본 체류시간이 <b>실제로 붙는가</b>.
 *
 * <p>이 테스트가 있는 이유가 있다. 처음에는 표를 YAML 맵으로 두었는데, 한글 키가 스프링 완화 바인딩에서 뭉개져 기동이 실패했다. 대괄호로 감쌌더니 이번에는 YAML
 * 이 그것을 리스트로 읽어 키가 {@code "[카페]"} 가 됐다 — <b>기동은 성공하고 모든 장소가 조용히 폴백값을 받았다.</b> 다리 15분·카페 40분이 전부
 * 60분으로 나왔고, 배포해서 실제 요청을 흘려 보기 전까지 아무도 몰랐다.
 *
 * <p>그 상태를 게이트가 잡게 하는 것이 여기 목적이다. 표가 비어도 서비스는 멀쩡히 돌기 때문에 다른 테스트로는 드러나지 않는다.
 */
@DisplayName("DwellDefaults — 유형별 기본 체류시간")
class DwellDefaultsTest {

  @Test
  @DisplayName("설정 문자열을 읽어 유형별로 다른 값을 준다")
  void parsesSpec() {
    DwellDefaults defaults = new DwellDefaults();
    defaults.setFallback(60);
    defaults.setByPlaceType("카페=40, 다리=15, 박물관/미술관=120");

    assertThat(defaults.forPlaceType("카페")).isEqualTo(40);
    assertThat(defaults.forPlaceType("다리")).isEqualTo(15);
    // 슬래시가 든 유형도 그대로 걸린다 — 앞선 두 시도가 여기서 깨졌다.
    assertThat(defaults.forPlaceType("박물관/미술관")).isEqualTo(120);
  }

  @Test
  @DisplayName("모르는 유형과 유형 없음은 폴백")
  void fallsBack() {
    DwellDefaults defaults = new DwellDefaults();
    defaults.setFallback(60);
    defaults.setByPlaceType("카페=40");

    assertThat(defaults.forPlaceType("성당")).isEqualTo(60);
    assertThat(defaults.forPlaceType(null)).isEqualTo(60);
  }

  @Test
  @DisplayName("형식이 어긋나면 기동할 때 죽는다 — 조용히 폴백으로 넘어가지 않는다")
  void rejectsMalformedSpec() {
    DwellDefaults defaults = new DwellDefaults();

    assertThatThrownBy(() -> defaults.setByPlaceType("카페40"))
        .isInstanceOf(IllegalArgumentException.class);
    // 계약이 15~180 분을 약속했으므로 그 밖의 기본값은 애초에 만들 수 없어야 한다.
    assertThatThrownBy(() -> defaults.setByPlaceType("카페=5"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults.setByPlaceType("카페=999"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("스프링 바인딩을 실제로 태운다 — 한글 키가 살아남는가")
  void bindsFromSpringProperties() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("scenetrip.course.dwell.fallback", "60");
    environment.setProperty("scenetrip.course.dwell.by-place-type", "카페=40, 역/교통=15");

    DwellDefaults bound =
        Binder.get(environment)
            .bind("scenetrip.course.dwell", DwellDefaults.class)
            .orElseThrow(() -> new AssertionError("바인딩이 아예 일어나지 않았다"));

    assertThat(bound.forPlaceType("카페")).isEqualTo(40);
    assertThat(bound.forPlaceType("역/교통")).isEqualTo(15);
  }

  @Test
  @DisplayName("배포되는 application.yaml 이 실제로 바인딩된다")
  void bindsTheRealApplicationYaml() throws Exception {
    // 앞선 두 번의 실패가 전부 "클래스는 멀쩡한데 설정 파일이 안 붙는" 모양이었다.
    // 그래서 손으로 만든 값이 아니라 **실제로 배포되는 파일**을 태운다.
    var sources =
        new org.springframework.boot.env.YamlPropertySourceLoader()
            .load(
                "application",
                new org.springframework.core.io.ClassPathResource("application.yaml"));
    var environment = new org.springframework.core.env.StandardEnvironment();
    sources.forEach(source -> environment.getPropertySources().addFirst(source));

    DwellDefaults bound =
        Binder.get(environment)
            .bind("scenetrip.course.dwell", DwellDefaults.class)
            .orElseThrow(() -> new AssertionError("application.yaml 에서 바인딩되지 않았다"));

    assertThat(bound.forPlaceType("카페")).isEqualTo(40);
    assertThat(bound.forPlaceType("다리")).isEqualTo(15);
    assertThat(bound.forPlaceType("박물관/미술관")).isEqualTo(120);
    // 표가 통째로 비어 폴백만 돌아오는 상태를 잡는 단언이다.
    assertThat(bound.forPlaceType("카페")).isNotEqualTo(bound.forPlaceType("없는유형"));
  }

  @Test
  @DisplayName("표를 직접 넣는 길도 같은 결과를 준다")
  void putAllMatches() {
    DwellDefaults defaults = new DwellDefaults();
    defaults.setFallback(60);
    defaults.putAll(Map.of("카페", 40));

    assertThat(defaults.forPlaceType("카페")).isEqualTo(40);
    assertThat(defaults.forPlaceType("사찰")).isEqualTo(60);
  }
}
