package com.mz2az.scenetrip.sceneapi.user;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 설치 UUID 를 계정으로 바꾼다.
 *
 * <p>앱은 {@code X-Install-Id} 헤더로 <b>설치 UUID</b> 를 보낸다. 저장은 전부 {@code app_user.id} 를 주체로 하므로 그 사이를
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
   *
   * <p>가입 여부도 같이 돌려준다. 가입 계정은 설치 UUID 만으로 열리지 않으므로(SESSION_REQUIRED) 요청마다 그것을 물어야 하는데, 따로 물으면 왕복이
   * 하나 더 생긴다.
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
      RETURNING app_user.id, app_user.registered_at IS NOT NULL AS registered
      """;

  /**
   * 토큰으로 온 계정의 방문 기록 — 그 계정이 아직 살아 있는지도 함께 본다.
   *
   * <p>토큰은 서명만 맞으면 만료까지 유효하다. 그 사이 탈퇴했거나(행이 없다) 다른 계정으로 합쳐졌으면({@code merged_into}) 행이 갱신되지 않아 빈 결과가
   * 된다 — 호출하는 쪽이 그것을 「믿을 수 없는 토큰」 으로 다룬다. 토큰은 가입 계정에만 발급하므로 가입 표시가 없는 행도 같은 취급이다.
   */
  private static final String TOUCH_SIGNED_IN_SQL =
      """
      UPDATE app_user SET last_seen_at = now()
      WHERE id = CAST(:userId AS UUID)
        AND merged_into IS NULL
        AND registered_at IS NOT NULL
      RETURNING id
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

  /**
   * 로그아웃한 설치본을 새 비회원 계정으로 — {@link #detachInstall}.
   *
   * <p>CTE 의 INSERT 가 조건부다. 그 설치본이 아직 그 계정을 가리킬 때만 새 계정이 생기고, 생긴 계정으로 짝을 바꾼다.
   */
  private static final String DETACH_SQL =
      """
      WITH new_user AS (
          INSERT INTO app_user (id, last_seen_at)
          SELECT CAST(:newUserId AS UUID), now()
          WHERE EXISTS (
              SELECT 1 FROM user_device
              WHERE install_uuid = CAST(:installUuid AS UUID)
                AND user_id = CAST(:userId AS UUID)
          )
          RETURNING id
      )
      UPDATE user_device SET user_id = new_user.id, last_seen_at = now()
      FROM new_user
      WHERE user_device.install_uuid = CAST(:installUuid AS UUID)
      """;

  private static final Logger log = LoggerFactory.getLogger(UserStore.class);

  private final JdbcClient jdbc;

  /**
   * 가입 판정을 켤 것인가. 기본은 켬이고, <b>끄는 것은 로컬 kind 뿐이다.</b>
   *
   * <p>로그인 스토리가 붙기 전에는 아무도 가입할 수 없어 마켓과 길찾기가 전부 401 이다. 시뮬레이터에서 그 두 화면을 실제 서버로 보려면 벽을 잠시 치워야 하는데,
   * 앱에 우회 코드를 넣으면 그것이 배포본에 실려 나간다. 서버 설정 하나로 두면 어느 환경에서 꺼져 있는지가 매니페스트에 그대로 보인다
   * (platform/kubernetes/scene-api/configmap.yaml). 로그인이 붙으면 이 설정과 함께 지운다.
   */
  private final boolean requireRegistration;

  /** 다른 패키지의 통합 테스트가 직접 만들 수 있어야 해서 public 이다. 가입 판정은 켠 채다 — 운영과 같은 조건으로 검사한다. */
  public UserStore(JdbcClient jdbc) {
    this(jdbc, true);
  }

  @Autowired
  public UserStore(
      JdbcClient jdbc,
      @Value("${scenetrip.auth.require-registration:true}") boolean requireRegistration) {
    this.jdbc = jdbc;
    this.requireRegistration = requireRegistration;
    if (!requireRegistration) {
      log.warn(
          "가입 판정이 꺼져 있습니다 (scenetrip.auth.require-registration=false) — 마켓·길찾기의 401 이 나지 않습니다. 로컬"
              + " 검증 전용입니다.");
    }
  }

  /**
   * 이 설치본의 계정 id. 처음 보는 설치본이면 비회원 계정을 만들어 준다.
   *
   * @param installUuid {@code X-Install-Id} 헤더로 온 값
   */
  public UUID resolve(UUID installUuid) {
    return lookup(installUuid).id();
  }

  /**
   * 이 설치본의 계정과 그 계정이 가입했는지. 처음 보는 설치본이면 비회원 계정을 만들어 준다.
   *
   * <p>{@link #resolve} 와 같은 일을 하고 가입 여부를 더 돌려준다 — 쿼리는 한 번이다. 요청마다 계정을 정하는 쪽({@code
   * web.CurrentAccount})이 쓴다.
   *
   * <p>가입 여부는 {@link #isRegistered} 와 달리 <b>설정({@link #requireRegistration})을 따르지 않는다.</b> 그 설정은
   * 마켓·길찾기의 벽을 로컬에서 치우는 우회이고, 이 값은 「설치 UUID 만으로 열어도 되는 계정인가」 라는 보안 판정이다. 우회가 보안 판정까지 끄면 안 된다.
   *
   * @param installUuid {@code X-Install-Id} 헤더로 온 값
   */
  public Account lookup(UUID installUuid) {
    return find(installUuid).orElseGet(() -> new Account(create(installUuid), false));
  }

  /**
   * 토큰으로 온 계정의 방문을 남기고, 그 계정이 살아 있는가.
   *
   * @return 가입한 채로 살아 있으면 {@code true}. 탈퇴했거나 합쳐져 사라졌으면 {@code false}
   */
  public boolean touchSignedIn(UUID userId) {
    return jdbc.sql(TOUCH_SIGNED_IN_SQL)
        .param("userId", userId.toString())
        .query(UUID.class)
        .optional()
        .isPresent();
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
   *
   * <p>판정이 꺼져 있으면({@link #requireRegistration}) DB 를 보지 않고 {@code true} 다. 로컬 검증용이고, 켜진 환경의 동작은 한
   * 줄도 바뀌지 않는다.
   */
  public boolean isRegistered(UUID userId) {
    if (!requireRegistration) {
      return true;
    }
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT registered_at IS NOT NULL FROM app_user WHERE id = CAST(:id AS UUID)")
            .param("id", userId.toString())
            .query(Boolean.class)
            .optional()
            .orElse(false));
  }

  private Optional<Account> find(UUID installUuid) {
    return jdbc.sql(TOUCH_SQL)
        .param("installUuid", installUuid.toString())
        .query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getBoolean("registered")))
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
          .map(Account::id)
          .orElseThrow(() -> new IllegalStateException("설치 UUID 등록이 경합 뒤에도 실패했습니다", race));
    }
  }

  /**
   * 로그아웃한 설치본을 새 비회원 계정에 짝지어 준다 — 그 설치본이 아직 그 계정을 가리킬 때만.
   *
   * <p>로그아웃한 폰이 가입 계정을 계속 가리키면 설치 UUID 만으로 그 계정에 닿으려 하게 되고, 그것은 {@code SESSION_REQUIRED} 로 막히는 길이다.
   * 새 비회원으로 시작하는 것이 맞다(계약 {@code /auth/sign-out}).
   *
   * <p><b>「그 계정을 가리킬 때만」 이 조건이다.</b> 설치 UUID 는 비밀이 아니므로, 남의 설치 UUID 와 아무 토큰으로 로그아웃을 불러 그 설치본을 떼어 낼
   * 수 있으면 안 된다. 호출하는 쪽은 리프레시 토큰으로 확인한 계정을 넘긴다.
   *
   * <p>계정 만들기와 짝 바꾸기가 <b>한 문장</b>이다. 조건이 맞지 않으면 계정 행도 생기지 않는다 — {@link #CREATE_SQL} 과 같은 이유다.
   *
   * @return 떼어 냈으면 {@code true}. 이미 다른 계정을 가리키고 있었으면 {@code false}
   */
  public boolean detachInstall(UUID installUuid, UUID userId) {
    return jdbc.sql(DETACH_SQL)
            .param("newUserId", UUID.randomUUID().toString())
            .param("installUuid", installUuid.toString())
            .param("userId", userId.toString())
            .update()
        > 0;
  }

  /**
   * 내 계정 — {@code GET /me} 와 토큰 묶음의 {@code user}.
   *
   * <p>이름과 이메일은 연결된 소셜 신분에서 가져온다. 지금은 신분이 하나지만 여럿이 될 자리라, 먼저 연결한 것부터 보아 처음으로 값이 있는 것을 쓴다.
   *
   * @return 살아 있는 가입 계정이면 그 모습. 없거나 비회원이거나 합쳐졌으면 비어 있다
   */
  public Optional<Profile> profile(UUID userId) {
    record Head(OffsetDateTime registeredAt, String nickname, boolean nicknameConfirmed) {}
    Optional<Head> head =
        jdbc.sql(
                "SELECT registered_at, nickname, nickname_confirmed FROM app_user"
                    + " WHERE id = CAST(:id AS UUID) AND merged_into IS NULL"
                    + " AND registered_at IS NOT NULL")
            .param("id", userId.toString())
            .query(
                (rs, n) ->
                    new Head(
                        rs.getObject("registered_at", OffsetDateTime.class),
                        rs.getString("nickname"),
                        rs.getBoolean("nickname_confirmed")))
            .optional();
    if (head.isEmpty()) {
      return Optional.empty();
    }
    List<Identity> identities =
        jdbc.sql(
                "SELECT provider, email, display_name FROM user_identity"
                    + " WHERE user_id = CAST(:id AS UUID) ORDER BY created_at, provider")
            .param("id", userId.toString())
            .query(
                (rs, n) ->
                    new Identity(
                        rs.getString("provider"),
                        rs.getString("email"),
                        rs.getString("display_name")))
            .list();
    return Optional.of(
        new Profile(
            userId,
            head.get().registeredAt(),
            identities,
            head.get().nickname(),
            head.get().nicknameConfirmed()));
  }

  /** 닉네임 정하기의 결과. */
  public enum NicknameResult {
    SET,
    TAKEN,
    GONE
  }

  /**
   * 닉네임을 정한다 — {@code nickname_confirmed} 도 켠다.
   *
   * <p>겹침은 V21 의 {@code lower(nickname)} 유일 색인이 판정한다. 먼저 SELECT 로 묻고 쓰면 그 사이에 같은 이름을 고른 사람과 경합한다 —
   * 색인이 둘 중 하나를 막는다. 그 실패를 {@code TAKEN} 으로 돌려준다. 자기 닉네임을 대소문자만 바꿔 다시 정하는 것은 자기 행이라 겹침이 아니다.
   */
  public NicknameResult setNickname(UUID userId, String nickname) {
    try {
      int n =
          jdbc.sql(
                  "UPDATE app_user SET nickname = :nickname, nickname_confirmed = true"
                      + " WHERE id = CAST(:id AS UUID) AND registered_at IS NOT NULL"
                      + " AND merged_into IS NULL")
              .param("nickname", nickname)
              .param("id", userId.toString())
              .update();
      return n > 0 ? NicknameResult.SET : NicknameResult.GONE;
    } catch (DuplicateKeyException e) {
      return NicknameResult.TAKEN;
    }
  }

  /**
   * 탈퇴 — 계정 행을 지운다.
   *
   * <p>작성한 리뷰는 남는다 — {@code review.user_id} 가 {@code ON DELETE SET NULL} 이라 작성자만 끊긴다(V21, 계약). 그 밖의
   * 모든 것은 {@code ON DELETE CASCADE} 로 함께 사라진다: 설치본 연결·장바구니·코스(아이템·핀)·찜·마켓에 올린 코스와 좋아요·소셜 신분·리프레시
   * 토큰. 이 계정으로 합쳐진 빈 비회원 행들은 {@code merged_into} 가 이 행을 가리키는데 그 FK 에는 CASCADE 가 없으므로, 먼저 끊는다 — 그
   * 행들은 이미 비어 있으니 함께 지운다.
   *
   * @return 지웠으면 {@code true}. 이미 없었으면 {@code false}
   */
  public boolean delete(UUID userId) {
    jdbc.sql("DELETE FROM app_user WHERE merged_into = CAST(:id AS UUID)")
        .param("id", userId.toString())
        .update();
    return jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
            .param("id", userId.toString())
            .update()
        > 0;
  }

  /**
   * 가입 계정의 모습.
   *
   * @param identities 먼저 연결한 것부터
   */
  public record Profile(
      UUID id,
      OffsetDateTime registeredAt,
      List<Identity> identities,
      String nickname,
      boolean nicknameConfirmed) {

    /** 닉네임 없이 — 닉네임이 생기기 전의 모습(V21 이전)을 다루는 곳과 시험이 쓴다. */
    public Profile(UUID id, OffsetDateTime registeredAt, List<Identity> identities) {
      this(id, registeredAt, identities, null, false);
    }

    /** 화면에 보일 이름 — 먼저 연결한 신분부터 보아 처음으로 값이 있는 것. 없을 수 있다. */
    public String displayName() {
      return identities.stream()
          .map(Identity::displayName)
          .filter(n -> n != null && !n.isBlank())
          .findFirst()
          .orElse(null);
    }

    /** 참고용 이메일 — 이름과 같은 규칙. 없을 수 있다. */
    public String email() {
      return identities.stream()
          .map(Identity::email)
          .filter(e -> e != null && !e.isBlank())
          .findFirst()
          .orElse(null);
    }
  }

  /** 계정에 붙은 소셜 신분 하나. {@code provider} 는 {@code google} · {@code apple}. */
  public record Identity(String provider, String email, String displayName) {}

  /**
   * 설치본이 가리키는 계정.
   *
   * @param id 계정 id
   * @param registered 가입했는가 — {@code registered_at} 이 채워졌는가
   */
  public record Account(UUID id, boolean registered) {}
}
