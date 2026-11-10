package com.mz2az.scenetrip.sceneapi.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 앱 버전 문자열의 형식과 비교(계약 1.27.0 {@code GET /app/versions} 의 「버전 비교」, 계획 {@code app-update-prompt.md}
 * §3).
 *
 * <p>비교 사례는 계약에 적힌 것({@code 1.10.0 > 1.9.9}, {@code 1.2 = 1.2.0})을 그대로 쓴다 — iOS·Android 가 같은 사례로
 * 확인한다.
 */
@DisplayName("AppVersion — 버전 형식과 비교")
class AppVersionTest {

  @Test
  @DisplayName("숫자 한 칸~세 칸을 점으로 이은 것은 맞는 형식")
  void acceptsOneToThreeNumericParts() {
    for (String version : List.of("1", "1.2", "1.2.3", "0.0.0", "10.20.30")) {
      assertThat(AppVersion.valid(version)).as(version).isTrue();
    }
  }

  @Test
  @DisplayName("빈 값·네 칸·숫자 아닌 칸·음수·빈 칸은 틀린 형식")
  void rejectsMalformed() {
    for (String version :
        Arrays.asList(
            null, "", " ", "1.2.3.4", "1.x", "-1", "1..2", "1.", ".1", "v1.2", "1.2.3 ")) {
      assertThat(AppVersion.valid(version)).as(String.valueOf(version)).isFalse();
    }
  }

  @Test
  @DisplayName("칸은 글자가 아니라 숫자로 비교한다 — 1.10.0 > 1.9.9")
  void comparesNumerically() {
    assertThat(AppVersion.compare("1.10.0", "1.9.9")).isPositive();
    assertThat(AppVersion.compare("1.9.9", "1.10.0")).isNegative();
  }

  @Test
  @DisplayName("없는 칸은 0 — 1.2 = 1.2.0, 1 = 1.0.0")
  void missingPartsAreZero() {
    assertThat(AppVersion.compare("1.2", "1.2.0")).isZero();
    assertThat(AppVersion.compare("1.2.0", "1.2")).isZero();
    assertThat(AppVersion.compare("1", "1.0.0")).isZero();
  }

  @Test
  @DisplayName("앞 칸이 먼저 — 2 > 1.99.99")
  void earlierPartWins() {
    assertThat(AppVersion.compare("2", "1.99.99")).isPositive();
    assertThat(AppVersion.compare("1.99.99", "2")).isNegative();
  }

  @Test
  @DisplayName("같은 버전은 0, 마지막 칸 차이도 본다")
  void equalAndPatchDifference() {
    assertThat(AppVersion.compare("1.2.3", "1.2.3")).isZero();
    assertThat(AppVersion.compare("1.2.4", "1.2.3")).isPositive();
    assertThat(AppVersion.compare("1.2", "1.2.1")).isNegative();
  }
}
