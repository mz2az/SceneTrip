package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.model.ApiError;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 예외를 계약의 {@link ApiError} 로 바꾼다.
 *
 * <p>이것이 없으면 Spring 의 기본 오류 응답이 나간다 — {@code timestamp}·{@code path}·{@code error} 를 담은 형태로, 명세에 없는
 * 모양이다. 클라이언트는 명세대로 {@code code} 를 읽으려다 실패한다. 정상 응답만 계약을 지키고 오류 응답은 아닌 상태가 가장 흔한 계약 위반이다.
 *
 * <p><b>스택 트레이스는 응답에 넣지 않는다.</b> 내부 클래스 이름과 경로가 그대로 드러나고, 클라이언트가 할 수 있는 일은 없다. 로그로만 남긴다.
 *
 * <p>{@code traceId} 는 500 응답에만 넣는다. OpenTelemetry 에이전트가 MDC 에 넣어 준 값이라 SigNoz 에서 그대로 조회된다 — 사용자가 그
 * 문자열 하나만 알려 주면 요청 하나를 찾아낼 수 있다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** 파라미터 검증 실패의 공통 코드. 어느 파라미터인지는 message 에 담긴다. */
  private static final String INVALID_PARAMETER = "INVALID_PARAMETER";

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> handleApiException(ApiException e) {
    ApiError body = new ApiError(e.getCode(), e.getMessage());
    // 5xx 는 원인이 서버 안에 있다 — 503 이면 외부 제공자와의 통신. 앱은 code 로 할 일을 알지만
    // 운영자가 고칠 단서는 로그에만 있으므로 그것을 찾는 열쇠를 싣는다.
    if (e.getStatus().is5xxServerError()) {
      body.traceId(currentTraceId());
    }
    return ResponseEntity.status(e.getStatus()).body(body);
  }

  /**
   * 명세의 제약(@Size·@Min·@Max·@NotNull)에 걸린 요청.
   *
   * <p>이 예외들이 실제로 던져지려면 hibernate-validator 가 클래스패스에 있어야 한다. 표식만 있고 구현이 없으면 검사가 아예 일어나지 않아, 명세가 400
   * 을 약속한 요청이 조용히 통과한다.
   */
  @ExceptionHandler({
    // 생성된 인터페이스에 붙은 @Validated 가 메서드 파라미터를 검사하면 이것이 나온다.
    // 처음에 빠뜨렸더니 @Size·@Min 위반이 500 으로 나갔다 — 명세가 400 을 약속한
    // 자리에서 서버 결함처럼 보였다. 테스트가 잡아 준 실제 결함이다.
    ConstraintViolationException.class,
    HandlerMethodValidationException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    HttpMediaTypeNotSupportedException.class,
    // 읽을 수 없는 요청 본문 — 깨진 JSON, 타입이 맞지 않는 필드.
    // 처음에 빠뜨렸더니 500 으로 나갔다. 클라이언트가 보낸 것이 잘못됐는데 서버 결함처럼
    // 보이면, 재시도해도 된다고 오해하게 된다. 실제 호출로 잡았다.
    HttpMessageNotReadableException.class
  })
  ResponseEntity<ApiError> handleValidation(Exception e) {
    // UUID 가 아닌 X-Device-Id 는 헤더가 없는 것과 같은 뜻이다 — 장바구니의 주체를
    // 알 수 없다. 클라이언트가 둘을 나눠 처리할 일이 없으므로 코드도 하나로 준다.
    //
    // 이름을 두 가지로 보는 이유: Spring 이 알려 주는 이름이 자리에 따라 헤더 이름
    // (X-Device-Id)이기도 하고 메서드 파라미터 이름(xDeviceId)이기도 하다. 하나만
    // 보면 형식 오류가 INVALID_PARAMETER 로 새어 나간다(실측).
    if (e instanceof MethodArgumentTypeMismatchException mismatch
        && ("xDeviceId".equalsIgnoreCase(mismatch.getName())
            || "X-Device-Id".equalsIgnoreCase(mismatch.getName()))) {
      return ResponseEntity.badRequest()
          .body(new ApiError("MISSING_DEVICE_ID", "X-Device-Id 헤더가 UUID 형식이 아닙니다"));
    }
    return ResponseEntity.badRequest().body(new ApiError(INVALID_PARAMETER, e.getMessage()));
  }

  /**
   * 명세에 없는 경로를 불렀다.
   *
   * <p>이것이 없으면 아래의 마지막 그물({@code Exception.class})이 대신 받아 <b>500</b> 을 낸다. 그 차이가 클라이언트에게는 크다 —
   * {@code errors.md} 가 "500 은 서버 결함이니 재시도해도 된다" 고 규정했으므로, 경로를 잘못 쓴 클라이언트가 규칙대로 재시도를 반복한다. 영원히 실패할
   * 요청을. 게다가 진짜 서버 장애와 구분되지 않아 로그가 오염된다.
   *
   * <p>Spring 은 매핑되지 않은 경로를 <b>정적 리소스 요청</b>으로 보고 이 예외를 던진다. 이름이 "리소스를 못 찾았다" 라 도메인의 404 ({@code
   * PLACE_NOT_FOUND} 등)와 헷갈리기 쉬운데, 이쪽은 <b>경로 자체가 없다</b>는 뜻이라 코드를 나눈다.
   *
   * <p>실제로 겪은 경로다. 명세가 약속한 {@code /v1} 접두사를 서버가 붙이지 않던 때, {@code /v1/contents} 호출이 500 으로 나갔다.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("ENDPOINT_NOT_FOUND", "그런 경로가 없습니다: " + e.getResourcePath()));
  }

  /** 장바구니 엔드포인트의 X-Device-Id 처럼 필수 헤더가 빠진 경우. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
    String code =
        "X-Device-Id".equalsIgnoreCase(e.getHeaderName()) ? "MISSING_DEVICE_ID" : INVALID_PARAMETER;
    return ResponseEntity.badRequest().body(new ApiError(code, e.getMessage()));
  }

  /**
   * 나머지 전부.
   *
   * <p>예외 종류를 응답에 흘리지 않는다. 어떤 결함이든 클라이언트가 아는 것은 "서버 문제이고 재시도해도 된다" 뿐이다.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception e) {
    log.error("처리하지 못한 예외", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("INTERNAL_ERROR", "서버에서 요청을 처리하지 못했습니다").traceId(currentTraceId()));
  }

  /**
   * 이 요청의 추적 ID.
   *
   * <p>OpenTelemetry API 로 현재 스팬에서 읽는다. 계측과 내보내기는 자바 에이전트가 하고 여기서는 <b>읽기만</b> 한다 — SDK 의존성이 없다.
   *
   * <p>에이전트가 붙지 않은 환경(단위 테스트, 수집기 없는 로컬 실행)에서는 빈 구현이 답해 유효하지 않은 스팬이 나오고, 그때는 {@code null} 을 돌려준다.
   * 코드는 그대로 돈다.
   *
   * <p><b>MDC 에서 읽으려다 실패했다.</b> 에이전트의 logback 계측은 트레이스 ID 를 <i>로그 이벤트</i>에 붙일 뿐, 스레드의 MDC 맵에 넣지
   * 않는다. {@code MDC.get("trace_id")} 는 언제나 {@code null} 이었고 실제 호출로 잡았다.
   *
   * <p>이 값이 왜 응답에 나가는가: 사용자가 500 을 받았을 때 이 문자열 하나로 SigNoz 에서 그 요청의 트레이스와 로그를 찾을 수 있다. 없으면 "몇 시쯤 오류가
   * 났다" 로부터 시작해야 한다.
   *
   * <p>5xx 에만 넣는다 — 500 과 503. 400·404 는 클라이언트가 무엇을 잘못했는지 이미 {@code code} 로 알 수 있어 서버 로그를 뒤질 일이 없다.
   */
  private static String currentTraceId() {
    SpanContext context = Span.current().getSpanContext();
    return context.isValid() ? context.getTraceId() : null;
  }
}
