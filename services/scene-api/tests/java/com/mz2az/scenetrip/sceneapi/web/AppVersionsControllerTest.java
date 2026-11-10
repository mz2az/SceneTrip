package com.mz2az.scenetrip.sceneapi.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.app.AppVersionSettings;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 앱 최소·최신 버전 {@code GET /app/versions} 의 HTTP 계층(계약 1.27.0, 계획 {@code app-update-prompt.md} §3·§4).
 *
 * <p>설정은 진짜 {@link AppVersionSettings} 를 올리고 {@code scenetrip.app-versions.*} 를 이 시험의 값으로 덮는다.
 * Android 는 주지 않는다 — 아직 내지 않은 플랫폼은 응답에서 빠져야 한다. 두 플랫폼이 다 있는 경우는 {@link
 * AppVersionsControllerWithAndroidTest}.
 */
@WebMvcTest(
    controllers = AppVersionsController.class,
    properties = {
      "scenetrip.app-versions.ios.minimum=1.2.0",
      "scenetrip.app-versions.ios.latest=1.4.1",
      "scenetrip.app-versions.ios.store-url=https://apps.apple.com/app/id1234567890"
    })
@Import({LanguageConfiguration.class, AppVersionSettings.class})
@DisplayName("GET /app/versions — iOS 만")
class AppVersionsControllerTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("200 과 iOS 규칙 — minimum·latest·storeUrl")
  void returnsIosRule() throws Exception {
    mvc.perform(get("/app/versions"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.ios.minimum").value("1.2.0"))
        .andExpect(jsonPath("$.ios.latest").value("1.4.1"))
        .andExpect(jsonPath("$.ios.storeUrl").value("https://apps.apple.com/app/id1234567890"));
  }

  @Test
  @DisplayName("아직 내지 않은 Android 는 응답에서 빠진다(null 이 아니라 필드가 없음)")
  void omitsAndroidWhenAbsent() throws Exception {
    mvc.perform(get("/app/versions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ios").exists())
        .andExpect(jsonPath("$.android").doesNotExist());
  }

  @Test
  @DisplayName("Cache-Control: public, max-age=300 — 숫자는 배포 때만 바뀐다")
  void allowsFiveMinuteCache() throws Exception {
    mvc.perform(get("/app/versions"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "public, max-age=300"));
  }

  @Test
  @DisplayName("로그인·설치 번호 없이 200 — 깨진 Authorization 을 달아도 200")
  void needsNoAuthOrInstallId() throws Exception {
    // 헤더를 하나도 달지 않은 요청(앱이 켜지자마자 부른다 — 아직 설치 번호·토큰이 없을 수 있다).
    mvc.perform(get("/app/versions")).andExpect(status().isOk());
    for (String authorization : List.of("Bearer garbage", "Basic dXNlcjpwYXNz", "Bearer ", "")) {
      mvc.perform(get("/app/versions").header("Authorization", authorization))
          .andExpect(status().isOk());
    }
  }
}
