package com.mz2az.scenetrip.sceneapi.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시작할 때의 버전 설정 검사(계획 {@code app-update-prompt.md} §4). 틀린 숫자로 모든 사용자를 막는 사고를 막으려고, 형식이 틀리거나 {@code
 * minimum > latest} 이면 서버가 뜨지 않아야 한다.
 */
@DisplayName("AppVersionSettings — 시작할 때 검사")
class AppVersionSettingsTest {

  private static AppVersionSettings.Rule rule(String minimum, String latest, String storeUrl) {
    AppVersionSettings.Rule rule = new AppVersionSettings.Rule();
    rule.setMinimum(minimum);
    rule.setLatest(latest);
    rule.setStoreUrl(storeUrl);
    return rule;
  }

  private static AppVersionSettings settings(
      AppVersionSettings.Rule ios, AppVersionSettings.Rule android) {
    AppVersionSettings settings = new AppVersionSettings();
    settings.setIos(ios);
    settings.setAndroid(android);
    return settings;
  }

  @Test
  @DisplayName("iOS 만 있고 minimum = latest 면 통과(처음 값)")
  void iosOnlyEqualPasses() {
    assertThatCode(() -> settings(rule("1.0.0", "1.0.0", null), null).validate())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("두 플랫폼 모두 minimum < latest 이고 스토어 주소가 있으면 통과")
  void bothPlatformsPass() {
    assertThatCode(
            () ->
                settings(
                        rule("1.2.0", "1.10.0", "https://apps.apple.com/app/id1"),
                        rule("1", "1.0.1", "https://play.google.com/store/apps/details?id=x"))
                    .validate())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("스토어 주소는 비워 둘 수 있다 — 빈 문자열도 통과")
  void blankStoreUrlPasses() {
    assertThatCode(() -> settings(rule("1.0.0", "1.0.0", ""), rule("1.0", "1.0", " ")).validate())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("iOS 규칙이 없으면 뜨지 않는다")
  void missingIosFails() {
    assertThatThrownBy(() -> settings(null, rule("1.0.0", "1.0.0", null)).validate())
        .isInstanceOf(IllegalStateException.class);
  }

  private static final List<String> MALFORMED =
      Arrays.asList(null, "", "1.2.3.4", "1.x", "-1", "1..2");

  @Test
  @DisplayName("iOS minimum 형식이 틀리면 뜨지 않는다")
  void malformedIosMinimumFails() {
    for (String minimum : MALFORMED) {
      assertThatThrownBy(() -> settings(rule(minimum, "9.9.9", null), null).validate())
          .as(String.valueOf(minimum))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("iOS latest 형식이 틀리면 뜨지 않는다")
  void malformedIosLatestFails() {
    for (String latest : MALFORMED) {
      assertThatThrownBy(() -> settings(rule("0", latest, null), null).validate())
          .as(String.valueOf(latest))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("iOS minimum > latest 면 뜨지 않는다 — 숫자로 비교(1.10.0 > 1.9.9)")
  void iosMinimumAboveLatestFails() {
    assertThatThrownBy(() -> settings(rule("1.10.0", "1.9.9", null), null).validate())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Android 규칙이 있으면 같은 검사를 받는다 — 형식")
  void malformedAndroidFails() {
    assertThatThrownBy(
            () -> settings(rule("1.0.0", "1.0.0", null), rule("1.0.0", "1.0.0.1", null)).validate())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> settings(rule("1.0.0", "1.0.0", null), rule(null, "1.0.0", null)).validate())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Android 규칙이 있으면 같은 검사를 받는다 — minimum > latest")
  void androidMinimumAboveLatestFails() {
    assertThatThrownBy(
            () -> settings(rule("1.0.0", "1.0.0", null), rule("2", "1.99.99", null)).validate())
        .isInstanceOf(IllegalStateException.class);
  }
}
