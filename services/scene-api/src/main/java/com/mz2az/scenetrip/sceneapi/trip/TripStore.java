package com.mz2az.scenetrip.sceneapi.trip;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 정한 여행(도시·며칠·속도) — 계약 1.25.0 {@code /me/trip}, 계획 {@code trip-memory.md}, MZ2AZ-542. 계정 하나에 여행 하나.
 */
@Repository
public class TripStore {

  /**
   * 정한 여행.
   *
   * @param pace 계약 {@code TripPace} 의 값
   * @param courseId 이 여행으로 만든 내 코스. 없거나 지웠으면 {@code null}
   */
  public record Trip(String city, int days, String pace, Long courseId, OffsetDateTime updatedAt) {}

  /** {@code courseId} 가 이 사람의 코스가 아니다. */
  public static final class CourseRejectedException extends RuntimeException {
    public CourseRejectedException(long courseId) {
      super("코스 " + courseId + " 은(는) 내 코스가 아닙니다");
    }
  }

  private final JdbcClient jdbc;

  public TripStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 정한 여행. 없으면 비어 있다. */
  public Optional<Trip> find(UUID user) {
    return jdbc.sql(
            "SELECT city, days, pace, course_id, updated_at FROM user_trip"
                + " WHERE user_id = CAST(:user AS UUID)")
        .param("user", user.toString())
        .query(TripStore::map)
        .optional();
  }

  /**
   * 통째로 바꾼다(없으면 만든다). {@code courseId} 는 이 사람의 코스여야 한다 — 확인과 쓰기가 한 문장이라 그 사이 코스가 지워지면 외래키가 막는다.
   *
   * @throws CourseRejectedException 남의 코스·없는 코스
   */
  public Trip put(UUID user, String city, int days, String pace, Long courseId) {
    if (courseId != null
        && jdbc.sql("SELECT 1 FROM course WHERE id = :id AND user_id = CAST(:user AS UUID)")
            .param("id", courseId)
            .param("user", user.toString())
            .query(Integer.class)
            .optional()
            .isEmpty()) {
      throw new CourseRejectedException(courseId);
    }
    return jdbc.sql(
            """
            INSERT INTO user_trip (user_id, city, days, pace, course_id, updated_at)
            VALUES (CAST(:user AS UUID), :city, :days, :pace, :courseId, now())
            ON CONFLICT (user_id) DO UPDATE
               SET city = EXCLUDED.city, days = EXCLUDED.days, pace = EXCLUDED.pace,
                   course_id = EXCLUDED.course_id, updated_at = now()
            RETURNING city, days, pace, course_id, updated_at
            """)
        .param("user", user.toString())
        .param("city", city)
        .param("days", days)
        .param("pace", pace)
        .param("courseId", courseId)
        .query(TripStore::map)
        .single();
  }

  /** 지운다. 없어도 된다(멱등). */
  public void delete(UUID user) {
    jdbc.sql("DELETE FROM user_trip WHERE user_id = CAST(:user AS UUID)")
        .param("user", user.toString())
        .update();
  }

  private static Trip map(ResultSet rs, int rowNum) throws SQLException {
    // wasNull 은 바로 앞에 읽은 칸의 것이다 — 코스를 읽자마자 본다(뒤의 칸을 읽고 나서 보면 그 칸의 답이 된다).
    long courseValue = rs.getLong("course_id");
    Long course = rs.wasNull() ? null : courseValue;
    return new Trip(
        rs.getString("city"),
        rs.getInt("days"),
        rs.getString("pace"),
        course,
        rs.getObject("updated_at", OffsetDateTime.class));
  }
}
