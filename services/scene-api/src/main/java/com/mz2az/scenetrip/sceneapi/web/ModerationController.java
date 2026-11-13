package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.ModerationApi;
import com.mz2az.scenetrip.sceneapi.api.model.BlockCreate;
import com.mz2az.scenetrip.sceneapi.api.model.BlockedUser;
import com.mz2az.scenetrip.sceneapi.api.model.BlockedUserList;
import com.mz2az.scenetrip.sceneapi.api.model.RemovalCreate;
import com.mz2az.scenetrip.sceneapi.api.model.ReportCreate;
import com.mz2az.scenetrip.sceneapi.api.model.ReportTargetType;
import com.mz2az.scenetrip.sceneapi.moderation.BlockStore;
import com.mz2az.scenetrip.sceneapi.moderation.OperatorDirectory;
import com.mz2az.scenetrip.sceneapi.moderation.RemovalStore;
import com.mz2az.scenetrip.sceneapi.moderation.ReportNotifier;
import com.mz2az.scenetrip.sceneapi.moderation.ReportStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 커뮤니티 신고·차단(계약 1.19.0 tag {@code moderation}, 계획 {@code community-moderation.md}, MZ2AZ-438).
 *
 * <p>신고는 로그인하지 않아도 된다 — 글은 누구나 읽는다. 비회원은 설치 id 로 찾은 계정이 신고한 사람이다. 차단은 로그인해야 한다 — 목록을 거를 때 보는 사람을
 * 토큰으로 안다.
 */
@RestController
class ModerationController implements ModerationApi {

  private static final Logger log = LoggerFactory.getLogger(ModerationController.class);

  private final ReportStore reports;
  private final BlockStore blocks;
  private final CurrentAccount accounts;
  private final RemovalStore removals;
  private final OperatorDirectory operators;
  private final ReportNotifier notifier;

  ModerationController(
      ReportStore reports,
      BlockStore blocks,
      CurrentAccount accounts,
      RemovalStore removals,
      OperatorDirectory operators,
      ReportNotifier notifier) {
    this.reports = reports;
    this.blocks = blocks;
    this.accounts = accounts;
    this.removals = removals;
    this.operators = operators;
    this.notifier = notifier;
  }

  @Override
  public ResponseEntity<Void> createReport(UUID xInstallId, ReportCreate in) {
    UUID reporter = accounts.resolve(xInstallId);
    ReportStore.Target target = target(in.getTargetType());
    String detail =
        in.getDetail() == null || in.getDetail().isBlank() ? null : in.getDetail().strip();
    String reason = in.getReason().getValue();

    switch (reports.report(reporter, target, in.getTargetId(), reason, detail)) {
      case NOT_FOUND ->
          throw ApiException.notFound(
              "REPORT_TARGET_NOT_FOUND",
              target.value + " " + in.getTargetId() + " 이(가) 없거나 이미 내려갔습니다");
      case OWN -> throw ApiException.badRequest("CANNOT_REPORT_OWN", "자기 글은 신고할 수 없습니다");
      case RECORDED -> {
        // 운영자 알림의 근거 — SigNoz 알람 규칙이 이 로그(「[신고] 접수」)를 센다(계획 §3). 모양을 바꾸면 알람도 고친다.
        log.warn("[신고] 접수 target={} id={} reason={}", target.value, in.getTargetId(), reason);
        // 무엇이 신고됐는지 담은 Slack 알림(MZ2AZ-569) — 기다리지 않는다.
        notifier.reportRecorded(target, in.getTargetId(), reason, detail);
      }
      case ALREADY -> {
        // 이미 신고한 글 — 한 번으로 센다. 알림도 다시 보내지 않는다.
      }
    }
    return ResponseEntity.noContent().build();
  }

  /** 운영자가 글·댓글·리뷰를 내린다(1.30.0, MZ2AZ-569, 계획 {@code moderation-operator.md} §2). */
  @Override
  public ResponseEntity<Void> removeContent(RemovalCreate in) {
    UUID operator = accounts.requireMember();
    if (!operators.isOperator(operator)) {
      throw ApiException.forbidden("OPERATOR_ONLY", "운영자만 내릴 수 있습니다");
    }
    ReportStore.Target target = target(in.getTargetType());
    switch (removals.remove(operator, target, in.getTargetId())) {
      case NOT_FOUND ->
          throw ApiException.notFound(
              "REMOVAL_TARGET_NOT_FOUND", target.value + " " + in.getTargetId() + " 이(가) 없습니다");
      case REMOVED ->
          log.warn(
              "[운영자] 내림 target={} id={} operator={}", target.value, in.getTargetId(), operator);
      case ALREADY -> {
        // 이미 내려 있다 — 같은 결과다.
      }
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<BlockedUser> createBlock(BlockCreate in) {
    UUID user = accounts.requireMember();
    return switch (blocks.block(user, target(in.getTargetType()), in.getTargetId())) {
      case BlockStore.Outcome.Created c ->
          ResponseEntity.status(HttpStatus.CREATED).body(view(c.block()));
      case BlockStore.Outcome.Existing e -> ResponseEntity.ok(view(e.block()));
      case BlockStore.Outcome.NotFound n ->
          throw ApiException.notFound(
              "BLOCK_TARGET_NOT_FOUND",
              in.getTargetType().getValue() + " " + in.getTargetId() + " 이(가) 없거나 이미 내려갔습니다");
      case BlockStore.Outcome.Self s ->
          throw ApiException.badRequest("CANNOT_BLOCK_SELF", "자기 자신은 차단할 수 없습니다");
      case BlockStore.Outcome.NoAuthor a ->
          throw ApiException.badRequest("BLOCK_TARGET_HAS_NO_AUTHOR", "작성자가 탈퇴한 글입니다");
    };
  }

  @Override
  public ResponseEntity<BlockedUserList> listMyBlocks() {
    UUID user = accounts.requireMember();
    return ResponseEntity.ok(
        new BlockedUserList(blocks.list(user).stream().map(ModerationController::view).toList()));
  }

  @Override
  public ResponseEntity<Void> deleteBlock(Long blockId) {
    UUID user = accounts.requireMember();
    if (!blocks.unblock(user, blockId)) {
      throw ApiException.notFound("BLOCK_NOT_FOUND", "차단 " + blockId + " 이(가) 없습니다");
    }
    return ResponseEntity.noContent().build();
  }

  private static ReportStore.Target target(ReportTargetType type) {
    return switch (type) {
      case REVIEW -> ReportStore.Target.REVIEW;
      case POST -> ReportStore.Target.POST;
      case COMMENT -> ReportStore.Target.COMMENT;
    };
  }

  private static BlockedUser view(BlockStore.Blocked b) {
    return new BlockedUser(b.id(), b.nickname(), b.blockedAt());
  }
}
