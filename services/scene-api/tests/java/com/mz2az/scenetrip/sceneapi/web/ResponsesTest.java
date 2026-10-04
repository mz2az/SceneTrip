package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 목록 응답의 {@code Content-Language} 를 정하는 규칙.
 *
 * <p>헤더는 값 하나뿐인데 목록의 항목은 행마다 다른 언어로 나올 수 있다. 그래서 「요청한 언어 → en → ko」 의 순서로, 실제로 나온 언어 중 가장 앞선 것을
 * 고른다. 이 순서가 Store SQL 의 {@code ORDER BY} 와 같아야 클라이언트가 헤더를 믿을 수 있다.
 */
@DisplayName("Responses.used — 실제로 쓴 언어")
class ResponsesTest {

  @Test
  @DisplayName("요청한 언어로 나온 항목이 하나라도 있으면 그 언어")
  void requestedWins() {
    assertThat(Responses.used(Lang.JA, Set.of(Lang.JA))).isEqualTo(Lang.JA);
    assertThat(Responses.used(Lang.JA, Set.of(Lang.JA, Lang.EN, Lang.KO))).isEqualTo(Lang.JA);
    assertThat(Responses.used(Lang.KO, Set.of(Lang.KO))).isEqualTo(Lang.KO);
  }

  @Test
  @DisplayName("요청한 언어와 ko 가 섞이면 요청한 언어 — 하나라도 맞았으면 폴백이 아니다")
  void mixedWithKoreanStillRequested() {
    assertThat(Responses.used(Lang.JA, Set.of(Lang.JA, Lang.KO))).isEqualTo(Lang.JA);
  }

  @Test
  @DisplayName("요청한 언어가 없고 en 이 있으면 en — ko 가 섞여도")
  void fallsBackToEnglishFirst() {
    assertThat(Responses.used(Lang.JA, Set.of(Lang.EN, Lang.KO))).isEqualTo(Lang.EN);
    assertThat(Responses.used(Lang.ZH_HANT, Set.of(Lang.EN))).isEqualTo(Lang.EN);
  }

  @Test
  @DisplayName("전부 ko 로 나왔으면 ko")
  void allKoreanIsKorean() {
    assertThat(Responses.used(Lang.JA, Set.of(Lang.KO))).isEqualTo(Lang.KO);
    assertThat(Responses.used(Lang.EN, Set.of(Lang.KO))).isEqualTo(Lang.KO);
  }

  @Test
  @DisplayName("빈 목록은 요청한 언어 — 폴백한 항목이 없다")
  void emptyIsRequested() {
    // 예전 규칙(하나도 안 맞으면 ko)으로는 빈 검색 결과가 늘 ko 를 달고 나갔다.
    assertThat(Responses.used(Lang.JA, Set.of())).isEqualTo(Lang.JA);
    assertThat(Responses.used(Lang.EN, Set.of())).isEqualTo(Lang.EN);
  }
}
