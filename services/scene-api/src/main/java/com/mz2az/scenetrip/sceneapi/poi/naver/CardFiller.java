package com.mz2az.scenetrip.sceneapi.poi.naver;

import java.util.Collection;

/**
 * 뒤에서 채우는 줄. {@code GET /pois/cards} 가 표에 없는 id 를 여기 넣고 즉시 응답하면, 일꾼이 하나씩 꺼내 출처에 묻고 표에 쓴다.
 *
 * <p>서비스는 이 인터페이스만 안다. 구현({@code PoiCardFiller})은 메모리 큐와 스레드 하나다 — 나중에 파이썬 일꾼으로 뺀다면 큐가 표로 가고 이
 * 인터페이스의 구현만 바뀐다.
 */
public interface CardFiller {

  /** 줄에 넣는다. 이미 서 있거나 처리 중인 id 는 다시 넣지 않는다. 줄이 가득 차면 버린다 — 앱이 다시 묻는다. */
  void enqueue(Collection<Long> poiIds);

  /** 지금 줄 길이와 최근 처리 속도로 계산한 「이만큼 뒤에 다시 물어라」. 1~30 초. */
  int retryAfterSeconds();

  /** 출처가 막았다(403·429). 일꾼이 한동안 쉰다. */
  void noteBlocked();
}
