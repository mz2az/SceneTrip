package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * 응답에 {@code Content-Language} 를 붙인다.
 *
 * <p>이 헤더는 <b>서버가 실제로 쓴 언어</b>다. 요청한 언어와 다를 수 있다 — 그 언어의 번역이 없으면 {@code ko} 로 떨어지기 때문이다. 클라이언트는 이
 * 값으로 "지금 보이는 글자가 내가 요청한 언어인가" 를 안다.
 *
 * <p>번역 폴백은 행 단위로 일어나므로 한 응답 안에 두 언어가 섞일 수 있다(작품 제목은 영어인데 장소 이름은 한국어인 경우 — 수집 데이터에 영어 장소명이 없다). 헤더는
 * 값 하나뿐이라 <b>돌려준 항목 중 하나라도 요청한 언어로 나왔으면 그 언어를, 전부 폴백했으면 {@code ko} 를</b> 보낸다.
 */
final class Responses {

  private Responses() {}

  static <T> ResponseEntity<T> ok(T body, Lang used) {
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_LANGUAGE, used.getValue()).body(body);
  }

  /**
   * 실제로 쓰인 언어를 정한다.
   *
   * @param requested 클라이언트가 요청한 언어
   * @param anyMatched 돌려줄 항목 중 하나라도 그 언어의 번역이 있었는가
   */
  static Lang used(Lang requested, boolean anyMatched) {
    return anyMatched ? requested : Lang.KO;
  }
}
