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
    return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getCode(), e.getMessage()));
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
   * <p>500 에만 넣는다. 400·404 는 클라이언트가 무엇을 잘못했는지 이미 {@code code} 로 알 수 있어 서버 로그를 뒤질 일이 없다.
   */
  private static String currentTraceId() {
    SpanContext context = Span.current().getSpanContext();
    return context.isValid() ? context.getTraceId() : null;
  }
}
