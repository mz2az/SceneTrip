package com.mz2az.scenetrip.sceneapi.popularity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 인기도 손잡이(계획 {@code popularity.md} §2 「가중치·K·기간 — 설정 {@code scenetrip.popularity.*}」, §4 무게, §5 K,
 * §2 기간·보관).
 *
 * <p>운영이 읽는 {@code application.yaml} 의 값이 계획에 적힌 처음 값과 같은지 본다 — 코드 없이 고치는 손잡이라, 어긋나도 컴파일러가 알려 주지
 * 않는다.
 */
@DisplayName("PopularitySettings — 계획의 처음 값")
class PopularitySettingsTest {

  private static void assertPlanDefaults(PopularitySettings s) {
    assertThat(s.getK()).as("K (§5: 처음 200)").isEqualTo(200.0);
    assertThat(s.getWindowDays()).as("기간 (§2: 최근 30 일)").isEqualTo(30);
    assertThat(s.getRetentionDays()).as("보관 (§2: 원본 90 일)").isEqualTo(90);
    assertThat(s.getWeightView()).as("상세 조회 무게 (§4)").isEqualTo(1.0);
    assertThat(s.getWeightHeart()).as("작품 하트 무게 (§4)").isEqualTo(3.0);
    assertThat(s.getWeightCart()).as("장소 담기 무게 (§4)").isEqualTo(3.0);
    assertThat(s.getWeightCourse()).as("코스에 넣기 무게 (§4)").isEqualTo(5.0);
    assertThat(s.getWeightReview()).as("리뷰 무게 (§4)").isEqualTo(5.0);
    assertThat(s.getWeightVisit()).as("실제 방문 무게 (§4)").isEqualTo(8.0);
  }

  @Test
  @DisplayName("application.yaml 의 scenetrip.popularity 가 계획의 처음 값이다")
  void applicationYamlMatchesPlan() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    for (PropertySource<?> source :
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yaml"))) {
      environment.getPropertySources().addLast(source);
    }

    PopularitySettings bound =
        Binder.get(environment)
            .bind("scenetrip.popularity", Bindable.ofInstance(new PopularitySettings()))
            .orElseThrow(() -> new AssertionError("application.yaml 에 scenetrip.popularity 가 없다"));

    assertPlanDefaults(bound);
  }

  @Test
  @DisplayName("설정이 없을 때의 기본값도 계획의 처음 값이다")
  void codeDefaultsMatchPlan() {
    assertPlanDefaults(new PopularitySettings());
  }
}
