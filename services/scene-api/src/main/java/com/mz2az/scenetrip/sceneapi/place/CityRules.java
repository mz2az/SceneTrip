package com.mz2az.scenetrip.sceneapi.place;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주소로 도시를 정하는 규칙 — 홈 도시 칸(계약 1.26.0 {@code GET /places/cities}, MZ2AZ-545).
 *
 * <p>웹 {@code apps/scenetrip-web/src/features/home/homeCities.ts}(MZ2AZ-531)의 규칙을 그대로 옮겼다. 열쇠는
 * <b>한국어 주소</b>에서 얻는다 — 영어 주소를 한국어 도시로 되돌리려면 전국 시·군 로마자 표가 필요해서다.
 *
 * <ul>
 *   <li>특별시·광역시·특별자치시·제주특별자치도 → 통째로 한 도시(서울·부산·…·세종·제주)
 *   <li>도(경기도·경상북도·강원특별자치도 …) → 그 아래 시·군(수원·경주 …). 이름이 겹치는 광주·고성은 도를 붙인다(「경기 광주」)
 *   <li>전남광주통합특별시 → 도처럼 시·군, 구로 시작하면 옛 광주광역시 자리라 「광주」
 * </ul>
 */
public final class CityRules {

  /** 줄의 맨 앞에 이 순서로 고정한다(사용자 결정 2026-11-07). 0곳이면 빠진다. */
  public static final List<String> FIXED_CITIES = List.of("서울", "부산", "경주", "인천", "제주");

  /** 언어와 무관한 열쇠(한국어 이름, 겹치는 곳만 도를 붙인다)와 한국어로 보이는 이름. */
  public record CityRef(String key, String ko) {}

  /** 통째로 한 도시인 광역 단위의 영어 이름. 열쇠 = 한국어 이름. */
  private static final Map<String, String> WHOLE_EN =
      Map.of(
          "서울", "Seoul", "부산", "Busan", "인천", "Incheon", "대구", "Daegu", "대전", "Daejeon", "울산",
          "Ulsan", "광주", "Gwangju", "세종", "Sejong", "제주", "Jeju");

  /** 도 이름 → 짧은 이름(열쇠가 겹칠 때 앞에 붙인다). */
  private static final Map<String, String> PROVINCE_SHORT =
      Map.ofEntries(
          Map.entry("경기도", "경기"),
          Map.entry("강원도", "강원"),
          Map.entry("강원특별자치도", "강원"),
          Map.entry("충청북도", "충북"),
          Map.entry("충청남도", "충남"),
          Map.entry("전라북도", "전북"),
          Map.entry("전북특별자치도", "전북"),
          Map.entry("전라남도", "전남"),
          Map.entry("경상북도", "경북"),
          Map.entry("경상남도", "경남"),
          Map.entry("전남광주통합특별시", "전남"));

  /** 도 짧은 이름의 영어 — 겹치는 시·군의 영어 이름 뒤에 붙인다(「Gwangju (Gyeonggi)」). */
  private static final Map<String, String> PROVINCE_EN =
      Map.of(
          "경기",
          "Gyeonggi",
          "강원",
          "Gangwon",
          "충북",
          "Chungbuk",
          "충남",
          "Chungnam",
          "전북",
          "Jeonbuk",
          "전남",
          "Jeonnam",
          "경북",
          "Gyeongbuk",
          "경남",
          "Gyeongnam");

  /** 이름만으로는 다른 곳과 겹치는 시·군 — 열쇠·이름에 도를 붙인다(경기 광주 ≠ 광주광역시, 고성은 강원·경남 둘). */
  private static final Set<String> AMBIGUOUS = Set.of("광주", "고성");

  private static final Pattern CITY_OR_COUNTY = Pattern.compile("^(.+?)(시|군)$");
  private static final Pattern METRO = Pattern.compile("^(.+?)(특별시|광역시|특별자치시)$");
  private static final Pattern EN_CITY_OR_COUNTY =
      Pattern.compile("^([A-Za-z][A-Za-z' ]*?)-(si|gun)$");

  private CityRules() {}

  /** 한국어 주소 → 도시. 모르는 모양이면 빈 값. */
  public static Optional<CityRef> cityOf(String koreanAddress) {
    String[] words = koreanAddress == null ? new String[0] : koreanAddress.strip().split("\\s+");
    String first = words.length > 0 ? words[0] : "";
    String second = words.length > 1 ? words[1] : null;
    if (first.isEmpty()) {
      return Optional.empty();
    }
    if (first.equals("제주특별자치도") || first.equals("제주도")) {
      return Optional.of(whole("제주"));
    }
    // 통합특별시는 도처럼 나눈다 — 통째로 묶으면 전라남도 전체가 한 칩이 된다.
    if (first.endsWith("통합특별시")) {
      if (second != null && second.endsWith("구")) {
        return Optional.of(whole("광주"));
      }
      return doCity(first, second);
    }
    Matcher metro = METRO.matcher(first);
    if (metro.matches()) {
      return Optional.of(whole(metro.group(1)));
    }
    if (first.endsWith("도")) {
      return doCity(first, second);
    }
    return Optional.empty();
  }

  /** 영어 이름 — 광역은 표, 시·군은 영어 주소에서 {@code …-si}·{@code …-gun} 을 뗀다. 못 찾으면 한국어 이름. */
  public static String englishName(CityRef city, String englishAddress) {
    String fixed = WHOLE_EN.get(city.key());
    if (fixed != null) {
      return fixed;
    }
    // 도를 붙인 열쇠(「경기 광주」)는 영어에서도 도를 붙여 광역 도시·다른 도의 같은 이름과 구분한다.
    String province = city.key().contains(" ") ? PROVINCE_EN.get(city.key().split(" ")[0]) : null;
    String[] parts = englishAddress == null ? new String[0] : englishAddress.split(",");
    for (int i = parts.length - 1; i >= 0; i--) {
      Matcher m = EN_CITY_OR_COUNTY.matcher(parts[i].strip());
      if (m.matches()) {
        return province != null ? m.group(1) + " (" + province + ")" : m.group(1);
      }
    }
    return city.ko();
  }

  private static CityRef whole(String name) {
    return new CityRef(name, name);
  }

  private static Optional<CityRef> doCity(String province, String word) {
    Matcher m = word == null ? null : CITY_OR_COUNTY.matcher(word);
    if (m == null || !m.matches()) {
      return Optional.empty();
    }
    String name = m.group(1);
    if (!AMBIGUOUS.contains(name)) {
      return Optional.of(new CityRef(name, name));
    }
    String label = PROVINCE_SHORT.getOrDefault(province, province) + " " + name;
    return Optional.of(new CityRef(label, label));
  }
}
