package com.mz2az.scenetrip.sceneapi.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 가입 판정 스위치.
 *
 * <p>켜진 쪽의 SQL 결과는 통합 테스트가 실제 DB 로 본다. 여기서 못 박는 것은 둘이다 — <b>꺼졌을 때 DB 를 아예 보지 않는다</b>, 그리고 <b>한 인자
 * 생성자는 켠 채다</b>(로컬 검증용 스위치가 운영 경로에 새어 들어오지 않았는지).
 */
class UserStoreTest {

  private static final UUID ANYONE = UUID.randomUUID();

  @Test
  @DisplayName("판정을 끄면 누구든 가입자다 — DB 를 보지 않는다")
  void disabledGateSkipsDatabase() {
    JdbcClient jdbc = mock(JdbcClient.class);
    UserStore users = new UserStore(jdbc, false);

    assertThat(users.isRegistered(ANYONE)).isTrue();
    verifyNoInteractions(jdbc);
  }

  @Test
  @DisplayName("한 인자 생성자는 판정을 켠 채다 — registered_at 을 묻는 SQL 로 간다")
  void singleArgConstructorKeepsGateOn() {
    JdbcClient jdbc = mock(JdbcClient.class);
    // 진짜 DB 가 없으니 SQL 에 닿았다는 사실만 잡는다 — 닿으면 이 예외가 그대로 올라온다.
    when(jdbc.sql(contains("registered_at"))).thenThrow(new IllegalStateException("db reached"));
    UserStore users = new UserStore(jdbc);

    assertThatThrownBy(() -> users.isRegistered(ANYONE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("db reached");
  }
}
