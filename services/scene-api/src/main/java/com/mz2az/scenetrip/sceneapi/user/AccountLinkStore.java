package com.mz2az.scenetrip.sceneapi.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 소셜 신분을 계정에 붙이고, 비회원 계정을 가입 계정으로 합친다 (MZ2AZ-256, 계획 §5).
 *
 * <p><b>이 클래스는 트랜잭션을 열지 않는다.</b> 로그인 한 번(찾기 → 붙이기 또는 합치기 → 토큰 발급)이 통째로 한 트랜잭션이어야 하므로, 그것을 여는 쪽
 * ({@code auth.SignInService})이 감싼다. 여기 메서드는 그 안에서 불린다는 전제다.
 *
 * <h2>합치기</h2>
 *
 * <p>비회원 G 를 가입 계정 X 로 합친다. 옮기는 표는 MZ2AZ-256 이 정한 넷이다.
 *
 * <ul>
 *   <li>{@code course} — 주인만 바꾼다. 아이템·직접 찍은 핀은 코스에 매달려 따라온다.
 *   <li>{@code saved_place} · {@code saved_content} — 「누가 무엇을」 이 기본키라 양쪽에 같은 것이 있으면 겹친다. X 에 없는 것만
 *       옮기고 G 쪽은 지운다.
 *   <li>{@code user_device} — G 를 가리키던 설치본이 X 를 가리키게 한다.
 * </ul>
 *
 * <p>마켓({@code market_course} · {@code market_like})은 옮기지 않는다. 비회원은 둘을 만들 수 없다({@code
 * SIGN_IN_REQUIRED}). 로컬 우회로 만든 것이 있어도 G 행이 남으므로 FK 는 깨지지 않는다 — 좋아요를 옮기면 겹친 것을 지울 때 {@code
 * like_count} 가 실제와 어긋나므로 손대지 않는 쪽이 낫다.
 *
 * <p>G 는 지우지 않고 {@code merged_into = X} 로 남긴다. 합치기 직후 늦게 들어온 쓰기가 G 에 떨어질 수 있는데(계획 §5 「알려진 틈」), 그것을
 * 나중에 찾아 쓸어 오려면 G 가 어디로 갔는지 알아야 한다 — {@link #sweepInto}.
 */
@Repository
public class AccountLinkStore {

  private static final Logger log = LoggerFactory.getLogger(AccountLinkStore.class);

  /**
   * 진행 중 여행이 둘이 되지 않게 한다.
   *
   * <p>DB 에는 「계정당 진행 중 코스 하나」 제약이 없지만 앱은 하나라고 가정한다(홈의 「여행 중」 카드, 길찾기). X 에 이미 진행 중인 코스가 있으면 G 쪽을
   * 「다가오는」 으로 내린다 — X 의 것을 살리는 이유는 합치기의 원칙과 같다(가입 계정 쪽을 살린다). {@code current_day_no} 는 진행 중에만 값이
   * 있어야 한다는 CHECK 가 있어 함께 비운다.
   */
  private static final String DEMOTE_ACTIVE_SQL =
      """
      UPDATE course SET status = 'upcoming', current_day_no = NULL, updated_at = now()
      WHERE user_id = CAST(:from AS UUID)
        AND status = 'active'
        AND EXISTS (SELECT 1 FROM course WHERE user_id = CAST(:to AS UUID) AND status = 'active')
      """;

  private static final String MOVE_COURSES_SQL =
      "UPDATE course SET user_id = CAST(:to AS UUID) WHERE user_id = CAST(:from AS UUID)";

  private static final String COPY_PLACES_SQL =
      """
      INSERT INTO saved_place (user_id, place_id, source_content_id, created_at)
      SELECT CAST(:to AS UUID), place_id, source_content_id, created_at
      FROM saved_place WHERE user_id = CAST(:from AS UUID)
      ON CONFLICT DO NOTHING
      """;

  private static final String COPY_CONTENTS_SQL =
      """
      INSERT INTO saved_content (user_id, content_id, created_at)
      SELECT CAST(:to AS UUID), content_id, created_at
      FROM saved_content WHERE user_id = CAST(:from AS UUID)
      ON CONFLICT DO NOTHING
      """;

  private final JdbcClient jdbc;

  public AccountLinkStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 이 설치본이 가리키는 계정을 잠그며 읽는다.
   *
   * <p>설치본 행과 계정 행을 둘 다 잠근다. 설치본 행을 잠가 같은 설치본의 로그인이 동시에 둘 진행되지 않게 하고, 계정 행을 잠가 그 계정이 동시에 다른 곳으로
   * 합쳐지지 않게 한다.
   *
   * @return 설치본이 아직 없으면 비어 있다 — 호출하는 쪽이 트랜잭션 밖에서 먼저 만들어 둔다
   */
  public Optional<UserStore.Account> lockInstallAccount(UUID installUuid) {
    return jdbc.sql(
            """
            SELECT a.id, a.registered_at IS NOT NULL AS registered
            FROM user_device d JOIN app_user a ON a.id = d.user_id
            WHERE d.install_uuid = CAST(:installUuid AS UUID)
            FOR UPDATE OF d, a
            """)
        .param("installUuid", installUuid.toString())
        .query(
            (rs, n) ->
                new UserStore.Account(rs.getObject("id", UUID.class), rs.getBoolean("registered")))
        .optional();
  }

  /** 이 소셜 신분이 붙은 계정. */
  public Optional<UUID> findIdentity(String provider, String subject) {
    return jdbc.sql(
            "SELECT user_id FROM user_identity WHERE provider = :provider AND subject = :subject")
        .param("provider", provider)
        .param("subject", subject)
        .query(UUID.class)
        .optional();
  }

  /**
   * 비회원 계정에 소셜 신분을 붙이고 가입 표시를 남긴다 — 가입.
   *
   * <p><b>데이터는 한 줄도 움직이지 않는다.</b> 그 비회원 행이 그대로 가입 계정이 된다 — V8 이 계정 id 를 설치 UUID 와 떼어 둔 이유다.
   *
   * <p>같은 소셜 신분이 이미 다른 계정에 붙어 있으면 {@code user_identity} 의 기본키가 막는다({@code DuplicateKeyException}).
   * 같은 계정으로 두 설치본이 동시에 첫 로그인할 때 늦은 쪽이 여기서 떨어지고, 호출하는 쪽이 트랜잭션을 처음부터 다시 돌려 합치기 경로로 간다.
   */
  public void register(
      UUID userId, String provider, String subject, String email, String displayName) {
    register(userId, provider, subject, email, displayName, null);
  }

  /**
   * {@link #register(UUID, String, String, String, String)} 에 애플 refresh token 암호문을 함께 둔다.
   *
   * @param appleRefreshTokenEnc 애플만. 구글은 {@code null}
   */
  public void register(
      UUID userId,
      String provider,
      String subject,
      String email,
      String displayName,
      byte[] appleRefreshTokenEnc) {
    jdbc.sql(
            """
            INSERT INTO user_identity (provider, subject, user_id, email, display_name, apple_refresh_token_enc)
            VALUES (:provider, :subject, CAST(:userId AS UUID), :email, :displayName, :appleToken)
            """)
        .param("provider", provider)
        .param("subject", subject)
        .param("userId", userId.toString())
        .param("email", email)
        .param("displayName", displayName)
        .param("appleToken", appleRefreshTokenEnc)
        .update();
    // 가입하는 순간 자동 닉네임을 붙인다(V21) — 리뷰 작성자로 보일 이름이 언제나 있게. 사용자가 정하면
    // nickname_confirmed 가 켜진다(PUT /me/nickname). 이미 있으면(다시 가입할 일은 없지만) 두지 않는다.
    jdbc.sql(
            "UPDATE app_user SET registered_at = now(),"
                + " nickname = COALESCE(nickname, '여행자' || nextval('app_user_nickname_seq'))"
                + " WHERE id = CAST(:id AS UUID) AND registered_at IS NULL")
        .param("id", userId.toString())
        .update();
  }

  /**
   * 로그인할 때마다 신분의 이메일·이름을 새 값으로 — 단 <b>비어 오면 덮어쓰지 않는다.</b>
   *
   * <p>애플은 이름을 첫 로그인에만 준다. 두 번째부터 빈 값으로 덮어쓰면 다시는 받을 수 없다. 구글은 매번 주므로 바뀐 이름이 따라온다.
   */
  public void refreshIdentity(String provider, String subject, String email, String displayName) {
    refreshIdentity(provider, subject, email, displayName, null);
  }

  /**
   * {@link #refreshIdentity(String, String, String, String)} 에 애플 refresh token 을 새것으로 — 로그인할 때마다
   * 애플이 새로 주므로 덮어쓴다. 비어 오면(구글) 그대로 둔다.
   */
  public void refreshIdentity(
      String provider,
      String subject,
      String email,
      String displayName,
      byte[] appleRefreshTokenEnc) {
    jdbc.sql(
            """
            UPDATE user_identity
            SET email = COALESCE(:email, email),
                display_name = COALESCE(:displayName, display_name),
                apple_refresh_token_enc = COALESCE(:appleToken, apple_refresh_token_enc)
            WHERE provider = :provider AND subject = :subject
            """)
        .param("provider", provider)
        .param("subject", subject)
        .param("email", email)
        .param("displayName", displayName)
        .param("appleToken", appleRefreshTokenEnc)
        .update();
  }

  /**
   * 이 계정의 애플 refresh token 암호문 — 탈퇴 때 연결을 끊는 데 쓴다.
   *
   * @return 애플 신분이 없거나 토큰을 받아 두지 못했으면 비어 있다
   */
  public Optional<byte[]> appleRefreshTokenEnc(UUID userId) {
    return jdbc.sql(
            "SELECT apple_refresh_token_enc FROM user_identity"
                + " WHERE user_id = CAST(:userId AS UUID) AND provider = 'apple'"
                + " AND apple_refresh_token_enc IS NOT NULL")
        .param("userId", userId.toString())
        .query((rs, n) -> rs.getBytes("apple_refresh_token_enc"))
        .optional();
  }

  /**
   * 비회원 {@code from} 을 가입 계정 {@code to} 로 합친다.
   *
   * <p>호출하는 쪽이 {@code from} 을 이미 잠갔다({@link #lockInstallAccount}). 순서: 진행 중 여행 정리 → 데이터 옮기기 → 설치본
   * 옮기기 → 합쳐진 표시.
   */
  public void merge(UUID from, UUID to) {
    jdbc.sql(DEMOTE_ACTIVE_SQL).param("from", from.toString()).param("to", to.toString()).update();
    int moved = moveData(from, to);
    jdbc.sql(
            "UPDATE user_device SET user_id = CAST(:to AS UUID), last_seen_at = now()"
                + " WHERE user_id = CAST(:from AS UUID)")
        .param("from", from.toString())
        .param("to", to.toString())
        .update();
    jdbc.sql("UPDATE app_user SET merged_into = CAST(:to AS UUID) WHERE id = CAST(:from AS UUID)")
        .param("from", from.toString())
        .param("to", to.toString())
        .update();
    log.info("합치기: {} → {} (옮긴 행 {})", from, to, moved);
  }

  /**
   * 이 설치본을 다른 계정으로 바꿔 단다 — 합치지 않고.
   *
   * <p>설치본이 가리키던 계정이 <b>가입 계정</b>일 때 쓴다. 가입 계정끼리는 합치지 않는다(각자 소셜 신분이 붙어 있다). 정상 경로로는 생기지 않는다 — 로그아웃이
   * 설치본을 새 비회원으로 바꾸기 때문이다.
   */
  public void repointInstall(UUID installUuid, UUID to) {
    jdbc.sql(
            "UPDATE user_device SET user_id = CAST(:to AS UUID), last_seen_at = now()"
                + " WHERE install_uuid = CAST(:installUuid AS UUID)")
        .param("installUuid", installUuid.toString())
        .param("to", to.toString())
        .update();
  }

  /**
   * 합쳐진 빈 행에 늦게 들어온 것을 다시 쓸어 온다 — 계획 §5 「알려진 틈」 의 복구.
   *
   * <p>합치기 직후, 합치기 전에 계정을 정한 같은 설치본의 쓰기가 G 에 떨어질 수 있다. 그것을 이 계정({@code to})으로 옮긴다. 합치기를 여러 번 돌려도 같은
   * 결과가 되도록 짰으므로 그대로 다시 부른다. 진행 중 여행 정리도 다시 한다.
   *
   * <p>토큰 갱신 때마다 부른다. 부분 인덱스({@code app_user_merged_into_idx}) 덕에 합쳐진 행이 없는 계정은 인덱스 한 번 보고 끝난다.
   *
   * @return 옮긴 행 수. 0 이 아니면 틈이 실제로 생긴 것이라 경고를 남긴다 — 잦으면 쓰기 경로에 잠금을 거는 방식으로 옮긴다(계획 §5)
   */
  public int sweepInto(UUID to) {
    List<UUID> shells =
        jdbc.sql("SELECT id FROM app_user WHERE merged_into = CAST(:to AS UUID)")
            .param("to", to.toString())
            .query(UUID.class)
            .list();
    int moved = 0;
    for (UUID shell : shells) {
      jdbc.sql(DEMOTE_ACTIVE_SQL)
          .param("from", shell.toString())
          .param("to", to.toString())
          .update();
      moved += moveData(shell, to);
    }
    if (moved > 0) {
      log.warn("합치기 뒤 늦게 들어온 행 {} 개를 {} 로 쓸어 왔습니다 — 합치기 틈이 실제로 생겼습니다", moved, to);
    }
    return moved;
  }

  /**
   * 코스·장바구니·찜을 옮긴다. 여러 번 불러도 같은 결과다.
   *
   * @return {@code from} 에서 빠진 행 수 — 옮겨졌든 {@code to} 에 이미 있어 버려졌든. 「빈 행에 무언가 있었다」 를 세는 것이라 복사된 수가
   *     아니라 지운 수로 센다
   */
  private int moveData(UUID from, UUID to) {
    String f = from.toString();
    String t = to.toString();
    int moved = jdbc.sql(MOVE_COURSES_SQL).param("from", f).param("to", t).update();
    jdbc.sql(COPY_PLACES_SQL).param("from", f).param("to", t).update();
    moved +=
        jdbc.sql("DELETE FROM saved_place WHERE user_id = CAST(:from AS UUID)")
            .param("from", f)
            .update();
    jdbc.sql(COPY_CONTENTS_SQL).param("from", f).param("to", t).update();
    moved +=
        jdbc.sql("DELETE FROM saved_content WHERE user_id = CAST(:from AS UUID)")
            .param("from", f)
            .update();
    return moved;
  }
}
