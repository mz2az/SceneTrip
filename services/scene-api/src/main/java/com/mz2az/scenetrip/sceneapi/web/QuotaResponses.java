package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.limit.PaidQuota;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** 유료 API 한도를 응답으로 — 429 와 RateLimit 헤더(계약 「요청 한도」). */
final class QuotaResponses {

  private QuotaResponses() {}

  static ApiException tooMany(PaidQuota.LimitExceeded e) {
    return ApiException.tooManyRequests(
        e.code(),
        "사용 한도를 넘었습니다 — " + e.retryAfterSeconds() + " 초 뒤에 다시 쓸 수 있습니다",
        Map.of(
            HttpHeaders.RETRY_AFTER,
            Long.toString(e.retryAfterSeconds()),
            "RateLimit-Limit",
            Integer.toString(e.headers().limit()),
            "RateLimit-Remaining",
            "0",
            "RateLimit-Reset",
            Long.toString(e.headers().resetSeconds())));
  }

  static ResponseEntity.BodyBuilder ok(PaidQuota.Grant grant) {
    PaidQuota.Headers h = grant.headers();
    return ResponseEntity.ok()
        .header("RateLimit-Limit", Integer.toString(h.limit()))
        .header("RateLimit-Remaining", Integer.toString(Math.max(0, h.remaining())))
        .header("RateLimit-Reset", Long.toString(h.resetSeconds()));
  }
}
