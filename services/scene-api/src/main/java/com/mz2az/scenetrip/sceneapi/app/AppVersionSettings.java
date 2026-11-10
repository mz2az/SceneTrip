package com.mz2az.scenetrip.sceneapi.app;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 앱 최소·최신 버전(계약 1.27.0 {@code GET /app/versions}, MZ2AZ-546). 값은 {@code scenetrip.app-versions} 설정
 * — 새 앱을 내면 숫자를 고쳐 배포한다. 틀린 숫자로 모든 사용자를 막지 않게 시작할 때 형식과 {@code minimum ≤ latest} 를 검사하고, 틀리면 뜨지
 * 않는다.
 */
@Component
@ConfigurationProperties(prefix = "scenetrip.app-versions")
public class AppVersionSettings {

  /** 한 플랫폼의 규칙. {@code storeUrl} 은 비워 둘 수 있다(스토어 번호를 아직 모를 때). */
  public static class Rule {
    private String minimum;
    private String latest;
    private String storeUrl;

    public String getMinimum() {
      return minimum;
    }

    public void setMinimum(String minimum) {
      this.minimum = minimum;
    }

    public String getLatest() {
      return latest;
    }

    public void setLatest(String latest) {
      this.latest = latest;
    }

    public String getStoreUrl() {
      return storeUrl;
    }

    public void setStoreUrl(String storeUrl) {
      this.storeUrl = storeUrl;
    }
  }

  private Rule ios = new Rule();

  /** 아직 내지 않으면 null — 응답에서 빠진다. */
  private Rule android;

  public Rule getIos() {
    return ios;
  }

  public void setIos(Rule ios) {
    this.ios = ios;
  }

  public Rule getAndroid() {
    return android;
  }

  public void setAndroid(Rule android) {
    this.android = android;
  }

  /** 시작할 때 검사한다 — 형식이 틀리거나 minimum 이 latest 보다 크면 {@link IllegalStateException}. */
  @PostConstruct
  public void validate() {
    if (ios == null) {
      throw new IllegalStateException("scenetrip.app-versions.ios 가 없다");
    }
    check("ios", ios);
    if (android != null) {
      check("android", android);
    }
  }

  private static void check(String platform, Rule rule) {
    if (!AppVersion.valid(rule.getMinimum()) || !AppVersion.valid(rule.getLatest())) {
      throw new IllegalStateException(
          "scenetrip.app-versions." + platform + " 의 minimum·latest 는 1.2.3 같은 숫자여야 한다");
    }
    if (AppVersion.compare(rule.getMinimum(), rule.getLatest()) > 0) {
      throw new IllegalStateException(
          "scenetrip.app-versions." + platform + " 의 minimum 이 latest 보다 크다 — 모든 사용자가 막힌다");
    }
  }
}
