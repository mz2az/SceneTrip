package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * 명세의 enum 을 요청 파라미터에서 읽을 수 있게 한다.
 *
 * <p>이것이 없으면 {@code Accept-Language: ko} 가 바인딩되지 않는다. Spring 의 기본 String→enum 변환은 상수 이름 ({@code
 * KO})을 찾는데, 생성된 enum 의 값은 소문자({@code ko})이기 때문이다. 생성기가 원래 이 설정을 함께 만들어 주지만 {@code interfaceOnly} 로
 * 껍데기를 걷어내면서 같이 빠졌다.
 */
@Configuration
class LanguageConfiguration {

  /**
   * Accept-Language 를 관대하게 읽는다.
   *
   * <p>명세는 이 헤더를 {@code Lang} 으로 정의하지만, HTTP 표준 헤더라 실제로는 {@code en-US,en;q=0.9,ko;q=0.8} 같은 값이 온다.
   * 브라우저나 OS 가 자동으로 채우는 자리이기 때문이다. 그것을 그대로 거부하면 앱이 아닌 클라이언트는 전부 400 을 받는다.
   *
   * <p>그래서 첫 번째 태그만 보고, 지역 표기를 떼어 내고({@code en-US} → {@code en}), 모르는 값은 {@code ko} 로 떨어뜨린다. 명세가
   * "생략하거나 해당 번역이 없으면 ko 로 폴백한다" 고 한 것과 같은 규칙이다.
   *
   * <p>{@code zh-Hant} 는 지역이 아니라 문자 체계 표기라 잘라 내면 안 된다 — 간체({@code zh-Hans})와 구분되지 않는다. 그래서 전체 값을 먼저
   * 맞춰 본 뒤에 자른다.
   */
  @Bean
  Converter<String, Lang> langConverter() {
    return source -> {
      if (source == null || source.isBlank()) {
        return Lang.KO;
      }
      String first = source.split(",")[0].split(";")[0].trim();
      for (Lang lang : Lang.values()) {
        if (lang.getValue().equalsIgnoreCase(first)) {
          return lang;
        }
      }
      String base = first.split("-")[0];
      for (Lang lang : Lang.values()) {
        if (lang.getValue().equalsIgnoreCase(base)) {
          return lang;
        }
      }
      return Lang.KO;
    };
  }

  /**
   * 작품 분류.
   *
   * <p>이쪽은 관대하게 받지 않는다. 우리가 정의한 질의 파라미터라 오타는 클라이언트의 실수이고, 조용히 무시하면 "필터를 걸었는데 안 걸린" 상태가 된다. 모르는 값은
   * 400 으로 돌려준다.
   */
  @Bean
  Converter<String, ContentCategory> contentCategoryConverter() {
    return ContentCategory::fromValue;
  }
}
