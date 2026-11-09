package com.mz2az.scenetrip.sceneapi.app;

import java.util.regex.Pattern;

/** 앱 버전 문자열 — {@code 1.2.3} 처럼 점으로 나눈 숫자(한 칸에서 세 칸). */
public final class AppVersion {

  private static final Pattern FORM = Pattern.compile("\\d+(\\.\\d+){0,2}");

  private AppVersion() {}

  /** 형식이 맞는가 — 숫자 한 칸~세 칸을 점으로. */
  public static boolean valid(String version) {
    return version != null && FORM.matcher(version).matches();
  }

  /** 칸을 앞에서부터 숫자로 비교한다. 없는 칸은 0({@code 1.2 = 1.2.0}). 음수·0·양수. */
  public static int compare(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      long p = i < x.length ? Long.parseLong(x[i]) : 0;
      long q = i < y.length ? Long.parseLong(y[i]) : 0;
      if (p != q) {
        return Long.compare(p, q);
      }
    }
    return 0;
  }
}
