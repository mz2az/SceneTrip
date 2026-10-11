package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.EventsApi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.UsageEntityType;
import com.mz2az.scenetrip.sceneapi.api.model.UsageEvent;
import com.mz2az.scenetrip.sceneapi.api.model.UsageEventType;
import com.mz2az.scenetrip.sceneapi.popularity.UsageEventStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용 기록(계약 1.11.0 {@code POST /events}, 계획 {@code popularity.md}, MZ2AZ-407).
 *
 * <p><b>보내고 잊는 창구다.</b> 모양이 틀리면 앱의 버그라 400 을 주지만, 적다가 실패하면 로그만 남기고 204 — 앱은 다시 보내지 않고 사용자가 알 이유도
 * 없다. 비회원도 보낸다. 설치 UUID 로 비회원 계정을 만들지 않는다 — 기록 하나 때문에 계정이 생기면 안 된다. 토큰이 있을 때만 사용자를 적는다.
 */
@RestController
class EventsController implements EventsApi {

  private static final Logger log = LoggerFactory.getLogger(EventsController.class);

  private final UsageEventStore store;
  private final CurrentAccount accounts;

  EventsController(UsageEventStore store, CurrentAccount accounts) {
    this.store = store;
    this.accounts = accounts;
  }

  @Override
  public ResponseEntity<Void> recordEvent(UUID xInstallId, UsageEvent event, Lang acceptLanguage) {
    String lang = (acceptLanguage == null ? Lang.EN : acceptLanguage).getValue();
    if (event.getType() == UsageEventType.VIEW) {
      UsageEventStore.Entity entity = entity(event.getEntityType());
      Long id = event.getEntityId();
      if (entity == null || id == null) {
        throw ApiException.badRequest(
            "INVALID_PARAMETER", "view 에는 entityType 과 entityId 가 있어야 합니다");
      }
      UUID user = accounts.signedInOrNull();
      write(() -> store.recordView(entity, id, xInstallId, user, lang));
    } else {
      String query = event.getQuery() == null ? "" : event.getQuery().strip();
      Integer count = event.getResultCount();
      if (query.isEmpty() || count == null) {
        throw ApiException.badRequest(
            "INVALID_PARAMETER", "search 에는 query 와 resultCount 가 있어야 합니다");
      }
      write(() -> store.recordSearch(query, count, lang));
    }
    return ResponseEntity.noContent().build();
  }

  private static UsageEventStore.Entity entity(UsageEntityType type) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case CONTENT -> UsageEventStore.Entity.CONTENT;
      case PLACE -> UsageEventStore.Entity.PLACE;
      case POI -> UsageEventStore.Entity.POI;
    };
  }

  /** 기록 실패는 응답을 바꾸지 않는다(위). */
  private static void write(Runnable write) {
    try {
      write.run();
    } catch (DataAccessException e) {
      log.warn("사용 기록을 적지 못했다", e);
    }
  }
}
