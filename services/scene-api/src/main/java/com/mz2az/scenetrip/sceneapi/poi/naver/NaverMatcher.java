package com.mz2az.scenetrip.sceneapi.poi.naver;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 네이버 검색 결과 중 우리 POI 와 <b>같은 곳</b>을 고른다. 순수 함수 — 네트워크도 DB 도 없다.
 *
 * <p>프로토타입 {@code server.py} 의 {@code match_ok} 를 옮긴 것이다. 규칙은 8,797 건 실측으로 만들어졌고, TMAP 좌표가 입구에서 건물로
 * 바뀐 뒤 2,000 건으로 다시 확인했다(볼트 「2026-09-03 네이버 매칭 거리 실측」). 요지 — <b>거리와 이름을 함께 본다.</b> 가까울수록 이름을 느슨하게,
 * 멀수록 엄격하게. 100 m 까지는 「이름 다름」이 10~15% 로 일정하다가 100~200 m 에서 31% 로 뛴다.
 *
 * <p>규칙을 고치면 {@link #RULE_VERSION} 을 올린다. 표({@code poi_naver.rule_version})는 현재 판만 조회하므로 옛 판정이 저절로
 * 무효가 된다.
 *
 * <p>프로토타입과 다른 점 둘 — 가장 가까운 후보 하나만 판정하지 않고 <b>가까운 순으로 첫 통과 후보</b>를 고른다(같은 건물의 다른 가게가 1 m 더 가까운 경우를
 * 놓치지 않는다). 좌표 없는 후보는 이름만으로 받지 않고 탈락시킨다.
 */
public final class NaverMatcher {

  /** 판정 규칙의 판. 규칙을 고치면 올린다 — 같은 파일에서. */
  public static final String RULE_VERSION = "v3";

  /**
   * 네이버 검색 결과 한 건.
   *
   * @param id 출처의 장소 id
   * @param name 출처가 부르는 이름
   * @param lat 위도. 없을 수 있다
   * @param lng 경도. 없을 수 있다
   */
  public record Candidate(String id, String name, Double lat, Double lng) {}

  /**
   * 고른 결과.
   *
   * @param candidate 통과한 후보. 없으면 null
   * @param distanceMeters 통과한 후보까지의 거리. 없으면 null
   * @param nearestMeters 통과 여부와 무관하게 가장 가까운 후보까지의 거리. 후보가 없거나 전부 좌표가 없으면 null. 「far 밖이면 검색어를 바꿔 다시
   *     찾는」 결정에 쓴다
   * @param why 통과한 것이 없을 때 이유 한 줄 — 가장 가까운 후보의 탈락 사유
   */
  public record Match(
      Candidate candidate, Integer distanceMeters, Integer nearestMeters, String why) {
    public boolean found() {
      return candidate != null;
    }

    static Match none(Integer nearestMeters, String why) {
      return new Match(null, null, nearestMeters, why);
    }
  }

  /**
   * 판정 하나.
   *
   * @param ok 같은 곳으로 본다
   * @param why 아니라면 이유
   */
  record Verdict(boolean ok, String why) {
    static final Verdict OK = new Verdict(true, null);

    static Verdict no(String why) {
      return new Verdict(false, why);
    }
  }

  /** 거리 계단. 이 안이면 부분 일치까지, 그 위 {@code far} 까지는 정확 일치만, 그 밖은 탈락. */
  private record Tiers(int mid, int far) {}

  /** 가게(음식·숙박). 100 m 를 넘으면 「이름 다름」이 급증한다. */
  private static final Tiers SHOP = new Tiers(80, 150);

  /** 명소·역. 궁·공원·역은 좌표가 넓은 곳의 어느 점이라 수백 m 어긋나도 같은 곳이다. */
  private static final Tiers WIDE = new Tiers(400, 800);

  /** 짧은 이름은 부분 일치를 믿지 않는다 — 「커피」가 「노크 커피바 선릉」에 붙어 리뷰 852 개를 가져간 사고(2026-08-25). */
  private static final int SHORT_NAME = 3;

  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
  private static final Pattern BRACKETED = Pattern.compile("\\[[^\\]]*\\]");

  /**
   * 글자·숫자·밑줄만 남긴다. {@code UNICODE_CHARACTER_CLASS} 가 없으면 자바의 {@code \w} 는 영문·숫자뿐이라 한글이 전부 지워진다 —
   * 파이썬의 {@code \w} 와 같게 맞춘 것이다.
   */
  private static final Pattern NOT_WORD =
      Pattern.compile("[^\\w]", Pattern.UNICODE_CHARACTER_CLASS);

  private NaverMatcher() {}

  /** 띄어쓰기·괄호·꼬리표를 없앤다. 「온정 국밥 집」=「온정국밥집」, 「안국역[3호선]」=「안국역」. */
  static String nameKey(String s) {
    if (s == null) {
      return "";
    }
    String t = HTML_TAG.matcher(s).replaceAll("");
    t = BRACKETED.matcher(t).replaceAll("");
    return NOT_WORD.matcher(t).replaceAll("").toLowerCase(Locale.ROOT);
  }

  /**
   * 괄호 <b>내용은 살리고</b> 기호만 없앤다. 「안국역[3호선]」=「안국역 3호선」.
   *
   * <p>TMAP 은 노선을 대괄호에 넣고 네이버는 띄어 쓴다. {@link #nameKey} 가 대괄호를 꼬리표로 보고 지우면 「안국역」 3 글자만 남아 짧은 이름 가드에
   * 걸려 모든 역이 차단됐다(2026-08-28).
   */
  static String nameKeyFull(String s) {
    if (s == null) {
      return "";
    }
    String t = HTML_TAG.matcher(s).replaceAll("");
    return NOT_WORD.matcher(t).replaceAll("").toLowerCase(Locale.ROOT);
  }

  /**
   * 같은 가게로 볼 것인가.
   *
   * @param tmapName 우리 이름
   * @param naverName 후보의 이름
   * @param distanceMeters 둘 사이 거리. 후보에 좌표가 없으면 null
   * @param group 우리 갈래 — 명소·교통은 계단이 넓다
   */
  static Verdict matches(
      String tmapName, String naverName, Integer distanceMeters, PoiCategoryGroup group) {
    String a = nameKey(tmapName);
    String b = nameKey(naverName);
    boolean exact = a.equals(b) || nameKeyFull(tmapName).equals(nameKeyFull(naverName));
    boolean part = !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));

    if (a.length() <= SHORT_NAME && !exact) {
      return Verdict.no("이름이 짧아 「" + naverName + "」 과 같은 곳인지 확신할 수 없다");
    }
    if (distanceMeters == null) {
      return Verdict.no("「" + naverName + "」 에 좌표가 없어 견줄 수 없다");
    }
    Tiers tiers = tiersFor(group);
    if (distanceMeters <= tiers.mid()) {
      return (exact || part)
          ? Verdict.OK
          : Verdict.no(distanceMeters + " m 인데 이름이 「" + naverName + "」 으로 다르다");
    }
    if (distanceMeters <= tiers.far()) {
      return exact
          ? Verdict.OK
          : Verdict.no(distanceMeters + " m 떨어져 이름이 정확히 같아야 하는데 「" + naverName + "」 이다");
    }
    return Verdict.no("가장 가까운 것도 " + distanceMeters + " m 떨어졌다");
  }

  /**
   * 후보들 중 같은 곳을 고른다 — <b>가까운 순으로 보며 첫 번째로 통과하는 것.</b>
   *
   * <p>좌표 없는 후보는 맨 뒤로 가고 통과하지 못한다. {@code why} 는 가장 가까운 후보의 탈락 사유다 — 사용자가 보게 될 「왜 못 찾았나」로는 그것이 가장
   * 그럴듯하다.
   */
  public static Match pick(
      String tmapName, double lat, double lng, PoiCategoryGroup group, List<Candidate> candidates) {
    if (candidates.isEmpty()) {
      return Match.none(null, "일치하는 장소가 없다");
    }
    List<Candidate> ordered =
        candidates.stream().sorted(Comparator.comparing(c -> distanceOrFar(lat, lng, c))).toList();

    Integer nearest = null;
    String firstWhy = null;
    for (Candidate c : ordered) {
      Integer d = distance(lat, lng, c);
      if (nearest == null && d != null) {
        nearest = d;
      }
      Verdict v = matches(tmapName, c.name(), d, group);
      if (v.ok()) {
        return new Match(c, d, nearest, null);
      }
      if (firstWhy == null) {
        firstWhy = v.why();
      }
    }
    return Match.none(nearest, firstWhy);
  }

  private static Tiers tiersFor(PoiCategoryGroup group) {
    return group == PoiCategoryGroup.SIGHT || group == PoiCategoryGroup.TRANSIT ? WIDE : SHOP;
  }

  private static double distanceOrFar(double lat, double lng, Candidate c) {
    Integer d = distance(lat, lng, c);
    return d == null ? Double.MAX_VALUE : d;
  }

  private static Integer distance(double lat, double lng, Candidate c) {
    if (c.lat() == null || c.lng() == null) {
      return null;
    }
    return (int) Math.round(haversineMeters(lat, lng, c.lat(), c.lng()));
  }

  /**
   * 구면 거리. 길찾기의 {@code navigation.Coordinate#distanceMetersTo} 와 같은 식이다 — 그쪽은 다른 PR(MZ2AZ-296)에 있어
   * 합쳐진 뒤 공용으로 뺀다. 세 번째로 쓰는 곳이 생긴 시점이다.
   */
  private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
    double earthRadius = 6_371_000.0;
    double deltaLat = Math.toRadians(lat2 - lat1);
    double deltaLng = Math.toRadians(lng2 - lng1);
    double h =
        Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(deltaLng / 2)
                * Math.sin(deltaLng / 2);
    return 2 * earthRadius * Math.asin(Math.sqrt(h));
  }
}
