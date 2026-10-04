package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;

/**
 * {@code Accept-Language} 변환기 하나만 본다. 스프링 없이 빈 메서드를 직접 부른다.
 *
 * <p>폴백 사슬의 첫 칸이 여기다 — 모르는 값을 무엇으로 떨어뜨리느냐가 응답 언어를 정한다. 예전에는 {@code ko} 였고 지금은 {@code en} 이다. 앱이 아닌
 * 클라이언트(브라우저·OS 가 채운 {@code fr-FR})가 한국어를 받으면 읽지 못하는 화면이 되기 때문이다.
 */
@DisplayName("LanguageConfiguration — Accept-Language 변환")
class LanguageConfigurationTest {

  private final Converter<String, Lang> converter = new LanguageConfiguration().langConverter();

  @Test
  @DisplayName("아는 언어는 그 언어로 읽는다 — 지역 표기는 떼고, 목록은 첫 태그만")
  void knownValues() {
    Map<String, Lang> cases =
        Map.of(
            "ko", Lang.KO,
            "en", Lang.EN,
            "ja", Lang.JA,
            // 문자 체계 표기는 잘라 내면 간체와 구분되지 않는다. 그대로 살아야 한다.
            "zh-Hant", Lang.ZH_HANT,
            "en-US", Lang.EN,
            "ja-JP", Lang.JA,
            // 브라우저가 보내는 모양. 첫 태그만 본다.
            "en-US,en;q=0.9", Lang.EN,
            "ko-KR,ko;q=0.9,en;q=0.8", Lang.KO,
            // 대소문자는 가리지 않는다.
            "ZH-hant", Lang.ZH_HANT);

    cases.forEach(
        (header, expected) -> assertThat(converter.convert(header)).as(header).isEqualTo(expected));
  }

  @Test
  @DisplayName("모르는 언어는 en 으로 떨어진다 — ko 가 아니다")
  void unknownFallsBackToEnglish() {
    for (String header : List.of("fr", "fr-FR", "de-DE,de;q=0.9", "xx")) {
      assertThat(converter.convert(header)).as(header).isEqualTo(Lang.EN);
    }
  }

  @Test
  @DisplayName("빈 값·공백뿐인 값도 en")
  void blankFallsBackToEnglish() {
    assertThat(converter.convert("")).isEqualTo(Lang.EN);
    assertThat(converter.convert("   ")).isEqualTo(Lang.EN);
  }
}
