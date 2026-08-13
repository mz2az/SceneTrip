package com.mz2az.scenetrip.sceneapi.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 설치 UUID 를 계정으로 바꾼다.
 *
 * <p>앱은 {@code X-Device-Id} 헤더로 <b>설치 UUID</b> 를 보낸다. 저장은 전부 {@code app_user.id} 를 주체로 하므로 그 사이를
 * 여기서 잇는다. 계약은 그대로 두고 변환을 서버 안에 가둔 것이라 <b>앱은 이 변화를 모른다.</b>
 *
 * <p>둘을 같은 값으로 두지 않은 이유 — 설치 UUID 는 사람이 아니라 그 설치본을 가리킨다. 앱을 지웠다 깔면 새로 생기는데 그것이 주체이면 그 사람의 장바구니와 코스가
 * 통째로 끊긴다. 로그인이 붙으면 {@code user_device} 가 가리키는 곳만 계정 쪽으로 바꿔 달면 되고, 데이터는 한 줄도 움직이지 않는다.
 *
 * <p>지금은 처음 본 설치본마다 비회원 계정을 하나씩 만든다. 로그인 스토리(8/23 주차)가 붙으면 그 행에 {@code registered_at} 이 채워질 뿐 id 는
 * 바뀌지 않는다.
 */
@Repository
public class UserStore {

  /**
   * 조회와 방문 기록을 한 문장으로 합쳤다.
   *
   * <p>{@code last_seen_at} 은 방치된 비회원 계정을 정리할 때 쓸 값인데 소급 수집이 안 되므로 처음부터 받아 둔다. 조회 따로 갱신 따로 하면 왕복이
   * 둘이 되므로 CTE 로 묶었다 — 기기 쪽을 갱신하면서 그 계정을 함께 갱신하고 id 를 돌려준다.
   */
  private static final String TOUCH_SQL =
      """
      WITH touched_device AS (
          UPDATE user_device SET last_seen_at = now()
          WHERE install_uuid = CAST(:installUuid AS UUID)
          RETURNING user_id
      )
      UPDATE app_user SET last_seen_at = now()
      FROM touched_device
      WHERE app_user.id = touched_device.user_id
      RETURNING app_user.id
      """;

  /**
   * 계정과 기기를 함께 만든다.
   *
   * <p><b>한 문장인 것이 핵심이다.</b> PostgreSQL 에서 문장 하나는 원자적이라, 같은 설치 UUID 가 동시에 두 번 들어와 {@code
   * user_device} 쪽이 기본키 위반으로 실패하면 앞의 {@code app_user} 삽입까지 함께 되돌아간다. 둘로 나눠 쓰면 주인 없는 계정 행이 남는다.
   */
  private static final String CREATE_SQL =
      """
      WITH new_user AS (
          INSERT INTO app_user (id, last_seen_at)
          VALUES (CAST(:userId AS UUID), now())
          RETURNING id
      )
      INSERT INTO user_device (install_uuid, user_id, last_seen_at)
      SELECT CAST(:installUuid AS UUID), id, now() FROM new_user
      RETURNING user_id
      """;

  private final JdbcClient jdbc;

  /** 다른 패키지의 통합 테스트가 직접 만들 수 있어야 해서 public 이다. */
  public UserStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 이 설치본의 계정 id. 처음 보는 설치본이면 비회원 계정을 만들어 준다.
   *
   * @param installUuid {@code X-Device-Id} 헤더로 온 값
   */
  public UUID resolve(UUID installUuid) {
    return find(installUuid).orElseGet(() -> create(installUuid));
  }

  /**
   * 가입한 사용자인가.
   *
   * <p>비회원과 가입 사용자가 같은 표를 쓴다. 가입해도 행이 새로 생기지 않고 {@code registered_at} 이 채워질 뿐이라, 그 칸 하나가 판정의 전부다.
   *
   * <p>이 값이 <b>마켓과 길찾기의 문</b>이다. 비회원이 코스를 올린 뒤 앱을 지우면 그것을 아무도 내릴 수 없고, 길찾기는 호출마다 돈이 나가는데 계정이 없으면 누가
   * 얼마나 썼는지 셀 수 없다.
   *
   * <p><b>지금은 언제나 {@code false} 다.</b> 가입시키는 경로가 아직 없다 — 로그인 스토리는 8/23 주차다. 그래서 마켓 API 는 계약대로 401 을
   * 낸다. 로그인이 붙으면 이 메서드는 그대로 두고 {@code registered_at} 을 채우는 쪽만 생기면 된다.
   */
  public boolean isRegistered(UUID userId) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT registered_at IS NOT NULL FROM app_user WHERE id = CAST(:id AS UUID)")
            .param("id", userId.toString())
            .query(Boolean.class)
            .optional()
            .orElse(false));
  }

  private Optional<UUID> find(UUID installUuid) {
    return jdbc.sql(TOUCH_SQL)
        .param("installUuid", installUuid.toString())
        .query(UUID.class)
        .optional();
  }

  private UUID create(UUID installUuid) {
    // id 를 애플리케이션이 만든다. 설계는 UUIDv7 을 권하지만 uuidv7() 은 PostgreSQL 18
    // 부터이고 우리는 17 이다 — 그래서 컬럼에 기본값을 두지 않았다(V8 주석).
    // v4 를 쓰는 대가는 인덱스 지역성뿐이고, 계정 수가 그것을 문제 삼을 규모가 아니다.
    UUID userId = UUID.randomUUID();
    try {
      return jdbc.sql(CREATE_SQL)
          .param("userId", userId.toString())
          .param("installUuid", installUuid.toString())
          .query(UUID.class)
          .single();
    } catch (DuplicateKeyException race) {
      // 같은 설치본의 첫 요청이 동시에 둘 들어왔다. 위 문장이 통째로 되돌아갔으므로
      // 먼저 끝난 쪽이 만든 계정을 그대로 쓴다.
      return find(installUuid)
          .orElseThrow(() -> new IllegalStateException("설치 UUID 등록이 경합 뒤에도 실패했습니다", race));
    }
  }
}
