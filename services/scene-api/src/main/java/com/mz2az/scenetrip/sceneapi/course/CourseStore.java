package com.mz2az.scenetrip.sceneapi.course;

import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDay;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemSource;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CoursePace;
import com.mz2az.scenetrip.sceneapi.api.model.CourseProgress;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CourseStatus;
import com.mz2az.scenetrip.sceneapi.api.model.CourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.TravelBasis;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 코스 — 만들고, 읽고, 통째로 바꾸고, 지운다.
 *
 * <p>주체는 {@code app_user.id} 다. {@code X-Device-Id} 를 계정으로 바꾸는 일은 {@link
 * com.mz2az.scenetrip.sceneapi.user.UserStore} 가 하고 여기는 이미 바뀐 값을 받는다.
 *
 * <p><b>일차는 표가 아니라 {@code course_item.day_no} 컬럼이다.</b> 며칠짜리인지는 {@code course.day_count} 가 들고, 항목이
 * 없는 일차는 그저 항목이 없는 번호다. 그래서 빈 일차를 만들려고 따로 넣는 행이 없다.
 */
@Repository
public class CourseStore {

  /**
   * 목록 — 여행 중이 위, 예정이 아래, 각각 최근에 만든 순.
   *
   * <p>{@code place_count} 와 {@code thumbnail_url} 은 상관 서브쿼리로 낸다. 코스마다 항목이 많아야 수십 개라 조인해 집계하는 것보다
   * 읽기 쉽고, 목록에 코스가 수백 개가 될 일도 없다.
   */
  private static final String LIST_SQL =
      """
      SELECT
          c.id, c.title, c.start_date, c.day_count, c.status, c.current_day_no,
          c.origin, c.pace, c.source_market_course_id, c.created_at, c.updated_at,
          (SELECT count(*) FROM course_item i WHERE i.course_id = c.id) AS place_count,
          (SELECT pim.url
             FROM course_item i
             JOIN place_image pim ON pim.place_id = i.place_id
            WHERE i.course_id = c.id
            ORDER BY i.day_no, i.sort_order, pim.sort_order, pim.id
            LIMIT 1) AS thumbnail_url
      FROM course c
      WHERE c.user_id = CAST(:userId AS UUID)
      ORDER BY (c.status = 'active') DESC, c.created_at DESC, c.id DESC
      """;

  private static final String FIND_SQL =
      """
      SELECT
          c.id, c.title, c.start_date, c.day_count, c.status, c.current_day_no,
          c.origin, c.pace, c.source_market_course_id, c.created_at, c.updated_at,
          (SELECT count(*) FROM course_item i WHERE i.course_id = c.id) AS place_count,
          (SELECT pim.url
             FROM course_item i
             JOIN place_image pim ON pim.place_id = i.place_id
            WHERE i.course_id = c.id
            ORDER BY i.day_no, i.sort_order, pim.sort_order, pim.id
            LIMIT 1) AS thumbnail_url
      FROM course c
      WHERE c.id = :courseId AND c.user_id = CAST(:userId AS UUID)
      """;

  /**
   * 한 코스의 항목 전부. 일차·순서대로.
   *
   * <p>두 갈래(등록된 촬영지·직접 찍은 핀)를 {@code COALESCE} 로 한 모양으로 합친다. 화면은 둘을 같은 카드로 그리므로 응답도 같은 모양이어야 한다 —
   * 어느 쪽인지는 {@code source} 가 말한다.
   *
   * <p>앞 장소로부터의 거리는 <b>창 함수</b>로 낸다. {@code lag()} 가 같은 일차 안에서 순서상 바로 앞 행의 좌표를 주므로, 항목을 자바로 돌며 짝지을
   * 필요가 없다. 첫 항목은 앞이 없어 {@code NULL} 이고 그대로 응답에서 빠진다.
   */
  private static final String ITEMS_SQL =
      """
      WITH place_display AS (
          SELECT DISTINCT ON (pi.place_id) pi.place_id, pi.name, pi.address
          FROM place_i18n pi
          WHERE pi.lang IN (:lang, 'ko')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC
      ),
      content_display AS (
          SELECT DISTINCT ON (ci.content_id) ci.content_id, ci.title
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      ),
      item AS (
          SELECT
              i.id, i.day_no, i.sort_order, i.dwell_min, i.visited_at,
              i.place_id, i.custom_pin_id, i.source_content_id,
              COALESCE(pd.name, cp.name)    AS name,
              pd.address                    AS address,
              COALESCE(p.type, cp.category) AS category,
              COALESCE(p.geom, cp.geom)     AS geom,
              cd.title                      AS source_content_title,
              (SELECT pim.url FROM place_image pim
                WHERE pim.place_id = p.id
                ORDER BY pim.sort_order, pim.id LIMIT 1) AS image_url
          FROM course_item i
          LEFT JOIN place p ON p.id = i.place_id
          LEFT JOIN place_display pd ON pd.place_id = p.id
          LEFT JOIN custom_pin cp ON cp.id = i.custom_pin_id
          LEFT JOIN content_display cd ON cd.content_id = i.source_content_id
          WHERE i.course_id = :courseId
      )
      SELECT
          id, day_no, dwell_min, visited_at, place_id, custom_pin_id,
          source_content_id, source_content_title, name, address, category, image_url,
          ST_Y(geom::geometry) AS latitude,
          ST_X(geom::geometry) AS longitude,
          ST_Distance(geom, lag(geom) OVER (PARTITION BY day_no ORDER BY sort_order))
              AS distance_from_previous
      FROM item
      ORDER BY day_no, sort_order
      """;

  private final JdbcClient jdbc;
  private final TravelEstimator travel;
  private final DwellDefaults dwellDefaults;
  private final TransactionTemplate transactions;

  /** 다른 패키지의 통합 테스트가 직접 만들 수 있어야 해서 public 이다. */
  public CourseStore(
      JdbcClient jdbc,
      TravelEstimator travel,
      DwellDefaults dwellDefaults,
      TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.travel = travel;
    this.dwellDefaults = dwellDefaults;
    this.transactions = transactions;
  }

  /**
   * 만든다.
   *
   * <p>받는 것은 기간과 만든 방식뿐이다. 일차는 행으로 만들지 않는다 — {@code day_count} 하나가 기간의 전부이고 빈 일차는 항목이 없는 번호로 저절로
   * 표현된다.
   */
  public long create(UUID userId, CourseCreate req) {
    return jdbc.sql(
            """
            INSERT INTO course (user_id, title, day_count, origin, pace)
            VALUES (CAST(:userId AS UUID), :title, :dayCount, :origin, :pace)
            RETURNING id
            """)
        .param("userId", userId.toString())
        .param("title", title(req.getTitle()))
        .param("dayCount", req.getDayCount())
        .param("origin", req.getOrigin().getValue())
        .param("pace", req.getPace() == null ? null : req.getPace().getValue())
        .query(Long.class)
        .single();
  }

  public List<CourseSummary> list(UUID userId) {
    return jdbc.sql(LIST_SQL)
        .param("userId", userId.toString())
        .query(CourseStore::mapSummary)
        .list();
  }

  public Optional<CourseDetail> find(UUID userId, long courseId, Lang lang) {
    Optional<CourseSummary> summary =
        jdbc.sql(FIND_SQL)
            .param("courseId", courseId)
            .param("userId", userId.toString())
            .query(CourseStore::mapSummary)
            .optional();

    return summary.map(s -> detail(s, days(courseId, s.getDayCount(), lang)));
  }

  /** 코스가 그 사람 것으로 존재하는가. 404 와 403 을 가르는 데 쓴다. */
  public boolean exists(UUID userId, long courseId) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM course"
                    + " WHERE id = :courseId AND user_id = CAST(:userId AS UUID))")
            .param("courseId", courseId)
            .param("userId", userId.toString())
            .query(Boolean.class)
            .single());
  }

  /** 여행 중이면 지금 몇 일차인지. 예정이거나 코스가 없으면 비어 있다. */
  public Optional<Integer> currentDayNo(UUID userId, long courseId) {
    return jdbc.sql(
            "SELECT current_day_no FROM course"
                + " WHERE id = :courseId AND user_id = CAST(:userId AS UUID)")
        .param("courseId", courseId)
        .param("userId", userId.toString())
        .query(Integer.class)
        .optional();
  }

  /**
   * 편집 완료 — 코스를 통째로 바꾼다.
   *
   * <p><b>보낸 것이 전부다.</b> {@code id} 가 있는 항목은 원래 있던 것이라 그대로 옮기고(방문 체크가 보존된다), 없는 것은 새로 넣고, 요청에 없는
   * 항목은 지운다. 그 항목이 직접 찍은 핀이었다면 핀도 함께 사라진다.
   *
   * <p><b>일차 번호와 순서는 배열 위치에서 나온다.</b> 요청에 그것을 적는 칸이 없으므로 클라이언트가 잘못 지목할 수 없다. 기간도 {@code days} 배열의
   * 길이가 그대로 된다 — 5일 코스에 3개를 보내면 4·5일차 항목이 "요청에 없는 항목" 이 되어 지워진다.
   *
   * <p>한 트랜잭션이라 전부 되거나 전부 안 된다. 순서를 통째로 다시 매기는 중간에는 {@code (course_id, day_no, sort_order)} 가 잠깐
   * 겹치는데, 그 제약이 {@code DEFERRABLE INITIALLY DEFERRED} 라 트랜잭션 끝에 한 번만 검사한다.
   *
   * <p><b>{@code @Transactional} 이 아니라 {@link TransactionTemplate} 을 쓴다.</b> 애노테이션은 스프링이 이 객체를 프록시로
   * 감쌌을 때만 도는데, 통합 테스트는 스프링 없이 Store 를 직접 만들어 쓴다 — 그러면 트랜잭션 없이 돌아 지연 검사가 걸리지 않고, <b>정작 검증해야 할 것이
   * 테스트에서 빠진다.</b> 실제로 그렇게 만들었다가 순서 뒤집기 테스트 셋이 유니크 제약 위반으로 떨어졌다. 트랜잭션 경계가 이 메서드 correctness 의 전제이므로
   * 코드에 드러나 있어야 한다.
   */
  public void replace(long courseId, CourseReplace req) {
    transactions.executeWithoutResult(status -> replaceInTransaction(courseId, req));
  }

  private void replaceInTransaction(long courseId, CourseReplace req) {
    List<Long> keep = new ArrayList<>();

    int dayNo = 0;
    for (CourseDayInput day : req.getDays()) {
      dayNo++;
      int sortOrder = 0;
      for (CourseItemInput in : day.getItems()) {
        sortOrder += SORT_STEP;
        keep.add(
            in.getId() == null
                ? insertItem(courseId, dayNo, sortOrder, in)
                : moveItem(courseId, dayNo, sortOrder, in));
      }
    }

    deleteItemsExcept(courseId, keep);

    // 아무 항목도 가리키지 않게 된 핀을 거둔다. 핀은 항목이 아니라 코스에 매달려 있어
    // 항목을 지운다고 딸려 가지 않는다.
    jdbc.sql(
            """
            DELETE FROM custom_pin cp
            WHERE cp.course_id = :courseId
              AND NOT EXISTS (SELECT 1 FROM course_item i WHERE i.custom_pin_id = cp.id)
            """)
        .param("courseId", courseId)
        .update();

    jdbc.sql(
            """
            UPDATE course
               SET title = :title, start_date = :startDate,
                   day_count = :dayCount, updated_at = now()
             WHERE id = :courseId
            """)
        .param("title", title(req.getTitle()))
        .param("startDate", req.getStartDate())
        .param("dayCount", req.getDays().size())
        .param("courseId", courseId)
        .update();
  }

  /** 코스를 시작하거나 일차를 넘긴다. 편집이 아니라 여행 중 동작이라 즉시 반영된다. */
  public void updateProgress(long courseId, CourseProgress req) {
    boolean active = req.getStatus() == CourseStatus.ACTIVE;
    jdbc.sql(
            """
            UPDATE course
               SET status = :status,
                   current_day_no = CAST(:currentDayNo AS INT),
                   updated_at = now()
             WHERE id = :courseId
            """)
        .param("status", req.getStatus().getValue())
        // 예정으로 되돌리면 지금 몇 일차인지는 뜻이 없다. V9 의 CHECK 도 그것을 요구한다.
        .param("currentDayNo", active ? req.getCurrentDayNo() : null)
        .param("courseId", courseId)
        .update();
  }

  /**
   * 방문 체크. 여행 중에만 부른다.
   *
   * <p>토글이라 이미 체크된 것을 또 체크해도 오류가 아니다. <b>시각은 서버가 찍는다</b> — 클라이언트 시계는 틀어져 있을 수 있고, 방문 시각은 나중에 "어느
   * 순서로 돌았나" 를 보는 값이라 한 기준으로 모여야 한다.
   *
   * <p>{@code course_id} 를 조건에 넣어 남의 코스 항목을 건드릴 수 없게 한다.
   *
   * @return 그 코스에 그 항목이 있었으면 {@code true}
   */
  public boolean markVisited(long courseId, long itemId, boolean visited) {
    return jdbc.sql(
                """
                UPDATE course_item
                   SET visited_at = CASE WHEN :visited THEN now() ELSE NULL END
                 WHERE id = :itemId AND course_id = :courseId
                """)
            .param("visited", visited)
            .param("itemId", itemId)
            .param("courseId", courseId)
            .update()
        > 0;
  }

  /** 지금 여행 중인가. 방문 체크를 열어 줄지 정한다. */
  public boolean isActive(UUID userId, long courseId) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                "SELECT status = 'active' FROM course"
                    + " WHERE id = :courseId AND user_id = CAST(:userId AS UUID)")
            .param("courseId", courseId)
            .param("userId", userId.toString())
            .query(Boolean.class)
            .optional()
            .orElse(false));
  }

  public boolean delete(UUID userId, long courseId) {
    return jdbc.sql("DELETE FROM course WHERE id = :courseId AND user_id = CAST(:userId AS UUID)")
            .param("courseId", courseId)
            .param("userId", userId.toString())
            .update()
        > 0;
  }

  // ───────────── 안쪽 ─────────────

  /** {@code sort_order} 를 10 씩 띄운다. 중간 삽입에 대비한 관례다(V9 · place_image 와 같다). */
  private static final int SORT_STEP = 10;

  /**
   * 채워 넣을 체류시간.
   *
   * <p>클라이언트가 비워 보내면 장소 유형에 맞는 기본값을 서버가 고른다 — 그 표가 iOS·Android·서버 세 곳에 흩어지지 않게 하려는 것이다({@link
   * DwellDefaults}). 직접 찍은 핀은 유형이 사용자가 고른 다섯 갈래라 수집 유형과 값 범위가 달라, 표를 태우지 않고 기본값으로 간다.
   */
  private int dwellMinutes(CourseItemInput in) {
    if (in.getDwellMinutes() != null) {
      return in.getDwellMinutes();
    }
    if (in.getPlaceId() == null) {
      return dwellDefaults.forPlaceType(null);
    }
    String placeType =
        jdbc.sql("SELECT type FROM place WHERE id = :placeId")
            .param("placeId", in.getPlaceId())
            .query(String.class)
            .optional()
            .orElse(null);
    return dwellDefaults.forPlaceType(placeType);
  }

  private long insertItem(long courseId, int dayNo, int sortOrder, CourseItemInput in) {
    Long pinId = in.getCustomPin() == null ? null : insertPin(courseId, in.getCustomPin());
    return jdbc.sql(
            """
            INSERT INTO course_item
                (course_id, day_no, place_id, custom_pin_id, sort_order,
                 dwell_min, source_content_id)
            VALUES (:courseId, :dayNo, CAST(:placeId AS BIGINT), CAST(:pinId AS BIGINT),
                    :sortOrder, :dwellMin, CAST(:sourceContentId AS BIGINT))
            RETURNING id
            """)
        .param("courseId", courseId)
        .param("dayNo", dayNo)
        .param("placeId", in.getPlaceId())
        .param("pinId", pinId)
        .param("sortOrder", sortOrder)
        .param("dwellMin", dwellMinutes(in))
        .param("sourceContentId", in.getSourceContentId())
        .query(Long.class)
        .single();
  }

  /**
   * 원래 있던 항목을 새 자리로 옮긴다.
   *
   * <p>{@code visited_at} 을 건드리지 않는 것이 이 메서드의 존재 이유다. 지우고 다시 넣으면 여행 중에 찍은 방문 기록이 사라진다.
   *
   * <p>{@code course_id} 를 조건에 넣어 남의 코스 항목 id 를 넣어도 아무것도 바뀌지 않게 한다.
   */
  private long moveItem(long courseId, int dayNo, int sortOrder, CourseItemInput in) {
    if (in.getCustomPin() != null) {
      jdbc.sql(
              """
              UPDATE custom_pin
                 SET name = :name, category = :category,
                     geom = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
               WHERE id = (SELECT custom_pin_id FROM course_item
                            WHERE id = :itemId AND course_id = :courseId)
              """)
          .param("name", in.getCustomPin().getName())
          .param("category", in.getCustomPin().getCategory().getValue())
          .param("lng", in.getCustomPin().getLongitude())
          .param("lat", in.getCustomPin().getLatitude())
          .param("itemId", in.getId())
          .param("courseId", courseId)
          .update();
    }

    int moved =
        jdbc.sql(
                """
                UPDATE course_item
                   SET day_no = :dayNo, sort_order = :sortOrder, dwell_min = :dwellMin,
                       source_content_id = CAST(:sourceContentId AS BIGINT)
                 WHERE id = :itemId AND course_id = :courseId
                """)
            .param("dayNo", dayNo)
            .param("sortOrder", sortOrder)
            .param("dwellMin", dwellMinutes(in))
            .param("sourceContentId", in.getSourceContentId())
            .param("itemId", in.getId())
            .param("courseId", courseId)
            .update();

    if (moved == 0) {
      throw new UnknownItemException(in.getId());
    }
    return in.getId();
  }

  private long insertPin(long courseId, CustomPinInput pin) {
    return jdbc.sql(
            """
            INSERT INTO custom_pin (course_id, name, category, geom)
            VALUES (:courseId, :name, :category,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
            RETURNING id
            """)
        .param("courseId", courseId)
        .param("name", pin.getName())
        .param("category", pin.getCategory().getValue())
        .param("lng", pin.getLongitude())
        .param("lat", pin.getLatitude())
        .query(Long.class)
        .single();
  }

  private void deleteItemsExcept(long courseId, List<Long> keep) {
    if (keep.isEmpty()) {
      // NOT IN () 은 문법 오류다. 남길 것이 없으면 조건 없이 지운다.
      jdbc.sql("DELETE FROM course_item WHERE course_id = :courseId")
          .param("courseId", courseId)
          .update();
      return;
    }
    jdbc.sql("DELETE FROM course_item WHERE course_id = :courseId AND id NOT IN (:keep)")
        .param("courseId", courseId)
        .param("keep", keep)
        .update();
  }

  /** 요청이 남의 항목 id 를 가리켰다. 400 으로 바꾸는 것은 컨트롤러 몫이다. */
  public static class UnknownItemException extends RuntimeException {
    private final long itemId;

    /** 단위 테스트가 이 상황을 흉내 낼 수 있어야 해서 public 이다. */
    public UnknownItemException(long itemId) {
      super("이 코스에 항목 " + itemId + " 이(가) 없습니다");
      this.itemId = itemId;
    }

    public long itemId() {
      return itemId;
    }
  }

  /**
   * 일차를 조립한다.
   *
   * <p>항목이 없는 일차도 빠뜨리지 않는다 — 배열 길이가 곧 기간이라 비면 기간이 줄어 보인다. 그래서 {@code dayCount} 만큼 미리 칸을 만들고 읽어 온
   * 항목을 그 자리에 넣는다.
   */
  private List<CourseDay> days(long courseId, int dayCount, Lang lang) {
    Map<Integer, List<CourseItem>> byDay = new LinkedHashMap<>();
    Map<Integer, Integer> travelMetres = new LinkedHashMap<>();
    for (int i = 1; i <= dayCount; i++) {
      byDay.put(i, new ArrayList<>());
      travelMetres.put(i, 0);
    }

    jdbc.sql(ITEMS_SQL)
        .param("courseId", courseId)
        .param("lang", lang.getValue())
        .query(
            (ResultSet rs, int rowNum) -> {
              int dayNo = rs.getInt("day_no");
              // day_count 를 넘는 항목은 API 로 만들어지지 않는다(V9 주석). 그래도 psql 로
              // 직접 넣은 것이 있으면 조용히 감추지 않고 마지막 일차에 붙여 눈에 띄게 한다.
              int slot = Math.min(dayNo, dayCount);
              byDay.get(slot).add(mapItem(rs));
              travelMetres.merge(slot, intOrZero(rs, "distance_from_previous"), Integer::sum);
              return null;
            })
        .list();

    List<CourseDay> days = new ArrayList<>();
    for (Map.Entry<Integer, List<CourseItem>> e : byDay.entrySet()) {
      List<CourseItem> items = e.getValue();
      int dwell = items.stream().mapToInt(CourseItem::getDwellMinutes).sum();
      int travelMinutes = travel.minutes(travelMetres.get(e.getKey()));
      days.add(
          new CourseDay(
              e.getKey(),
              items,
              dwell,
              travelMinutes,
              dwell + travelMinutes,
              TravelBasis.STRAIGHT_LINE));
    }
    return days;
  }

  private static CourseDetail detail(CourseSummary s, List<CourseDay> days) {
    return new CourseDetail(
            s.getId(),
            s.getTitle(),
            s.getDayCount(),
            s.getStatus(),
            s.getOrigin(),
            s.getPlaceCount(),
            s.getCreatedAt(),
            s.getUpdatedAt(),
            days)
        .startDate(s.getStartDate())
        .currentDayNo(s.getCurrentDayNo())
        .pace(s.getPace())
        .sourceMarketCourseId(s.getSourceMarketCourseId())
        .thumbnailUrl(s.getThumbnailUrl());
  }

  private static CourseSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
    return new CourseSummary(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getInt("day_count"),
            CourseStatus.fromValue(rs.getString("status")),
            CourseOrigin.fromValue(rs.getString("origin")),
            rs.getInt("place_count"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class))
        .startDate(rs.getObject("start_date", LocalDate.class))
        .currentDayNo(integerOrNull(rs, "current_day_no"))
        .pace(pace(rs.getString("pace")))
        .sourceMarketCourseId(longOrNull(rs, "source_market_course_id"))
        .thumbnailUrl(uri(rs.getString("thumbnail_url")));
  }

  private static CourseItem mapItem(ResultSet rs) throws SQLException {
    Long placeId = longOrNull(rs, "place_id");
    return new CourseItem(
            rs.getLong("id"),
            placeId == null ? CourseItemSource.CUSTOM_PIN : CourseItemSource.PLACE,
            rs.getString("name"),
            rs.getDouble("latitude"),
            rs.getDouble("longitude"),
            rs.getInt("dwell_min"))
        .placeId(placeId)
        .customPinId(longOrNull(rs, "custom_pin_id"))
        .address(rs.getString("address"))
        .category(rs.getString("category"))
        .imageUrl(uri(rs.getString("image_url")))
        .distanceMetersFromPrevious(integerOrNull(rs, "distance_from_previous"))
        .sourceContentId(longOrNull(rs, "source_content_id"))
        .sourceContentTitle(rs.getString("source_content_title"))
        .visitedAt(rs.getObject("visited_at", OffsetDateTime.class));
  }

  /** 이름은 비울 수 없다. 화면마다 따로 처리하지 않도록 채우는 자리를 서버 한 곳으로 모은다. */
  private static String title(String given) {
    return given == null || given.isBlank() ? "이름 없는 코스" : given;
  }

  private static CoursePace pace(String value) {
    return value == null ? null : CoursePace.fromValue(value);
  }

  private static URI uri(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** {@code getLong} 은 NULL 을 0 으로 바꾼다. id 0 과 "값이 없다" 는 다르다. */
  private static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
    int value = (int) Math.round(rs.getDouble(column));
    return rs.wasNull() ? null : value;
  }

  private static int intOrZero(ResultSet rs, String column) throws SQLException {
    int value = (int) Math.round(rs.getDouble(column));
    return rs.wasNull() ? 0 : value;
  }
}
