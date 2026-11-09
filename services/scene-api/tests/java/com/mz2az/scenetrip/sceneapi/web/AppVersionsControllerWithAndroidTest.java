package com.mz2az.scenetrip.sceneapi.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.app.AppVersionSettings;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /app/versions} — 두 플랫폼이 다 있고, iOS 스토어 주소는 비워 둔 경우(계약 1.27.0). 비운 {@code storeUrl} 은 빈
 * 문자열로 나가지 않고 필드째 빠져야 한다 — 앱은 그때 자기 스토어 주소를 쓴다.
 */
@WebMvcTest(
    controllers = AppVersionsController.class,
    properties = {
      "scenetrip.app-versions.ios.minimum=1.0.0",
      "scenetrip.app-versions.ios.latest=1.0.0",
      "scenetrip.app-versions.ios.store-url=",
      "scenetrip.app-versions.android.minimum=1.1",
      "scenetrip.app-versions.android.latest=1.10.0",
      "scenetrip.app-versions.android.store-url=https://play.google.com/store/apps/details?id=com.mz2az.scenetrip"
    })
@Import({LanguageConfiguration.class, AppVersionSettings.class})
@DisplayName("GET /app/versions — iOS·Android, iOS 스토어 주소 비움")
class AppVersionsControllerWithAndroidTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("Android 규칙이 함께 나간다")
  void returnsAndroidRule() throws Exception {
    mvc.perform(get("/app/versions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ios.minimum").value("1.0.0"))
        .andExpect(jsonPath("$.ios.latest").value("1.0.0"))
        .andExpect(jsonPath("$.android.minimum").value("1.1"))
        .andExpect(jsonPath("$.android.latest").value("1.10.0"))
        .andExpect(
            jsonPath("$.android.storeUrl")
                .value("https://play.google.com/store/apps/details?id=com.mz2az.scenetrip"))
        .andExpect(header().string("Cache-Control", "public, max-age=300"));
  }

  @Test
  @DisplayName("비워 둔 iOS storeUrl 은 필드째 빠진다(빈 문자열이 아니다)")
  void omitsBlankStoreUrl() throws Exception {
    mvc.perform(get("/app/versions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ios.storeUrl").doesNotExist());
  }
}
