package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.AppApi;
import com.mz2az.scenetrip.sceneapi.api.model.AppVersionRule;
import com.mz2az.scenetrip.sceneapi.api.model.AppVersions;
import com.mz2az.scenetrip.sceneapi.app.AppVersionSettings;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 앱 최소·최신 버전(1.27.0, MZ2AZ-546). 로그인 없이 부른다. 숫자는 배포 때만 바뀌어 5분 캐시를 허락한다. */
@RestController
class AppVersionsController implements AppApi {

  private final AppVersionSettings settings;

  AppVersionsController(AppVersionSettings settings) {
    this.settings = settings;
  }

  @Override
  public ResponseEntity<AppVersions> getAppVersions() {
    AppVersions body = new AppVersions(rule(settings.getIos()));
    if (settings.getAndroid() != null) {
      body.setAndroid(rule(settings.getAndroid()));
    }
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=300").body(body);
  }

  private static AppVersionRule rule(AppVersionSettings.Rule r) {
    AppVersionRule out = new AppVersionRule(r.getMinimum(), r.getLatest());
    String store = r.getStoreUrl();
    if (store != null && !store.isBlank()) {
      out.setStoreUrl(URI.create(store.strip()));
    }
    return out;
  }
}
