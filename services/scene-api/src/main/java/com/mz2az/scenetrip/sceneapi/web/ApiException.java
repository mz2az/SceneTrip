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

  /**
   * 가입해야 할 수 있는 동작이다.
   *
   * <p>넷뿐이다 — 마켓 좋아요·담기·올리기, 그리고 여행 중 길찾기. 검색·장바구니·작품 찜·코스 만들기는 계정 없이 다 된다. 로그인 벽을 앞에 세우면 사람들이 앱을 써
   * 보기도 전에 나가기 때문이다.
   */
  public static ApiException signInRequired(String code, String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
  }

  /**
   * 남의 것을 고치거나 지우려 했다.
   *
   * <p>{@code 404} 와 갈리는 자리에 주의한다. 존재 자체를 숨겨야 하면 404 이고(내 코스), 이미 누구에게나 보이는 것이면 403 이다(마켓에 올라온 코스).
   */
  public static ApiException forbidden(String code, String message) {
    return new ApiException(HttpStatus.FORBIDDEN, code, message);
  }

  /**
   * 요청도 대상도 멀쩡한데 처리할 수 없는 상태다. 길찾기에서 경로가 없을 때 — 섬·산속처럼 길이 안 이어지거나, 근처에 정류장이 없는 자리다.
   *
   * <p>400 이 아닌 이유: 클라이언트가 고칠 것이 없다. 500 이 아닌 이유: 서버 결함이 아니고 다시 불러도 같다. 그래서 재시도 버튼을 보이지 않는다.
   */
  public static ApiException unprocessable(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  /**
   * 외부 제공자(길찾기)가 응답하지 않거나 호출 한도를 넘었다. 잠시 뒤 다시 시도할 수 있다.
   *
   * <p>500 과 갈리는 자리: 우리 결함이 아니라 일시적이다. 502·504 로 나누지 않는 이유는 앱이 하는 일이 같아서다 — 타임아웃이었는지 깨진 응답이었는지는 서버
   * 로그에만 있고, 그것을 찾는 열쇠로 {@code traceId} 가 실린다.
   */
  public static ApiException unavailable(String code, String message) {
    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
