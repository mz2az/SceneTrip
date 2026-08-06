package com.mz2az.scenetrip.sceneapi.web;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에게 그대로 돌려줄 오류.
 *
 * <p>{@code code} 가 계약의 일부다. 클라이언트는 이 값만 보고 분기하므로, 뜻을 바꾸는 것은 파괴적 변경이다. 전체 목록은 {@code
 * docs/api/errors.md} 에 있고, 코드를 늘릴 때는 그 문서와 명세의 {@code ApiError} 설명을 같은 커밋에서 고친다.
 *
 * <p>메시지는 사람이 로그에서 읽는 것이다. 사용자에게 그대로 보여 줄 문구가 아니므로 번역하지 않는다.
 */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;
  private final String code;

  private ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /** 요청이 규칙에 맞지 않는다. 클라이언트가 고치기 전에는 재시도해도 같은 결과다. */
  public static ApiException badRequest(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }

  /**
   * 경로에 지정한 대상이 없다.
   *
   * <p>목록이 비는 것에는 쓰지 않는다 — 그것은 200 에 빈 배열이다. 404 는 "있다고 지목한 것이 없다" 는 뜻이다.
   */
  public static ApiException notFound(String code, String message) {
    return new ApiException(HttpStatus.NOT_FOUND, code, message);
  }

  /** 이미 있는 것을 또 만들려 했다. */
  public static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
