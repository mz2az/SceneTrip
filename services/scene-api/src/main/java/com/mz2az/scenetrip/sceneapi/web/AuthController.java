package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.AuthApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 창구 — <b>아직 구현이 없다.</b>
 *
 * <p>계약(MZ2AZ-330)이 서버(MZ2AZ-331)보다 먼저 나갔다. 앱이 생성 클라이언트로 작업을 시작할 수 있게 하려는 것이다. 이 클래스가 없으면 {@code
 * /auth/*} 와 {@code /me} 가 경로 자체가 없는 것이 되어 {@code 404 ENDPOINT_NOT_FOUND} 로 나간다 — 「명세에는 있는데 아직 안
 * 만들었다」 와 「그런 경로가 없다」 가 구분되지 않는다. 비어 있는 채로 {@link AuthApi} 를 구현해 두면 생성된 기본 메서드가 {@code 501} 을 낸다.
 *
 * <p>설계는 {@code docs/project/plans/social-login.md}.
 */
@RestController
class AuthController implements AuthApi {}
