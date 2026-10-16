package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.auth.AuthAction
import com.mz2az.scenetrip.auth.AuthRules
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.sceneapi.client.api.ModerationApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.BlockCreate
import com.mz2az.scenetrip.sceneapi.client.model.BlockedUser
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.ReportCreate
import com.mz2az.scenetrip.sceneapi.client.model.ReportReason
import com.mz2az.scenetrip.sceneapi.client.model.ReportTargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/*
 * 신고·차단·차단 목록·금칙어 (MZ2AZ-461, 서버 MZ2AZ-438). iOS `Models/Moderation.swift` 를 옮긴 것이다 —
 * App Store 심사 지침 1.2: 사용자가 글을 올리는 앱에는 신고, 차단, 부적절한 글 거르기, 연락처 공개가 앱 안에 있어야 한다.
 */

/**
 * 신고·차단할 글. 계약은 **글을 기준으로** 받는다 — 앱에는 사용자 번호가 없고 닉네임은 바뀐다.
 * 차단도 「이 글의 작성자」 를 차단한다. iOS `ModerationTarget`.
 */
data class ModerationTarget(
    val type: ReportTargetType,
    val targetId: Long,
) {
    val key: String get() = "${type.value}-$targetId"

    companion object {
        fun review(id: Long) = ModerationTarget(ReportTargetType.review, id)

        fun post(id: Long) = ModerationTarget(ReportTargetType.post, id)
    }
}

/**
 * 신고·차단·금칙어의 규칙 — 값만 보는 순수 함수다. iOS `ModerationRules`.
 *
 * 오류는 **서버 문장이 아니라 code 로 가른다**(MZ2AZ-345) — 계약이 `ApiError.message` 를 화면 문구가 아니라고 못 박았다.
 */
object ModerationRules {
    /** 신고 이유 — 화면에 이 순서로 보인다. 계약 `ReportReason` 의 순서 그대로다. */
    val reasons: List<ReportReason> =
        listOf(
            ReportReason.spam,
            ReportReason.abuse,
            ReportReason.sexual,
            ReportReason.personal_info,
            ReportReason.inappropriate_nickname,
            ReportReason.other,
        )

    /** 덧붙이는 설명의 최대 길이(계약 `ReportCreate.detail` maxLength). */
    const val DETAIL_LIMIT = 500

    /** 금칙어 안내 — 어떤 말이 걸렸는지는 서버가 알려 주지 않는다. 한국어 원문이 번역표의 열쇠다. */
    const val TEXT_NOT_ALLOWED_MESSAGE = "쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요."

    fun label(reason: ReportReason): String =
        tr(
            when (reason) {
                ReportReason.spam -> "스팸·광고"
                ReportReason.abuse -> "욕설·혐오·괴롭힘"
                ReportReason.sexual -> "성적인 내용"
                ReportReason.personal_info -> "개인정보 노출"
                ReportReason.inappropriate_nickname -> "닉네임이 부적절함"
                ReportReason.other -> "기타"
            },
        )

    /** 보낼 설명 — 앞뒤 빈칸을 지우고, 비었으면 보내지 않고, 길면 자른다. */
    fun detail(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return capped(trimmed)
    }

    /** 입력창에 남길 글 — 최대 길이를 넘으면 자른다(서버는 UTF-16 길이로 센다). */
    fun capped(text: String): String {
        if (text.length <= DETAIL_LIMIT) return text
        val cut = DETAIL_LIMIT - if (text[DETAIL_LIMIT - 1].isHighSurrogate()) 1 else 0
        return text.substring(0, cut)
    }

    /** 「⋯」 메뉴를 둘 글인가 — 자기 글에는 두지 않는다(보내면 `CANNOT_REPORT_OWN`). 서버에 없는 기기 옛 글도 아니다. */
    fun showsMenu(
        isMine: Boolean,
        onServer: Boolean = true,
    ): Boolean = !isMine && onServer

    /** 차단을 둘 글인가 — 자기 글이 아니고 작성자가 있어야 한다(탈퇴한 작성자는 `BLOCK_TARGET_HAS_NO_AUTHOR`). */
    fun canBlock(
        isMine: Boolean,
        hasAuthor: Boolean,
    ): Boolean = !isMine && hasAuthor

    /** 오류 본문의 `code`. 서버가 답한 오류가 아니면(연결 실패 등) `null`. */
    fun apiCode(error: Throwable): String? = if (isApiError(error)) NetworkFailure.of(error).code else null

    private fun isApiError(error: Throwable) = error is ClientException || error is ServerException

    // --- 신고 결과 ---------------------------------------------------------------------------------

    enum class ReportOutcome {
        /** 접수됐다 — 같은 글을 다시 신고해도 이것이다(서버 204). */
        RECEIVED,

        /** 자기 글이다(`CANNOT_REPORT_OWN`). */
        OWN,

        /** 글이 없거나 이미 내려갔다(`REPORT_TARGET_NOT_FOUND`). */
        GONE,
        RATE_LIMITED,
        FAILED,
        ;

        /** 시트를 닫아도 되는가 — 다시 보내도 같은 결과면 닫는다. 실패·한도는 남겨 다시 보내게 한다. */
        val finishes: Boolean get() =
            when (this) {
                RECEIVED, OWN, GONE -> true
                RATE_LIMITED, FAILED -> false
            }
    }

    fun reportOutcome(error: Throwable?): ReportOutcome {
        if (error == null) return ReportOutcome.RECEIVED
        if (!isApiError(error)) return ReportOutcome.FAILED
        val failure = NetworkFailure.of(error)
        return when (failure.code) {
            "CANNOT_REPORT_OWN" -> ReportOutcome.OWN
            "REPORT_TARGET_NOT_FOUND" -> ReportOutcome.GONE
            "RATE_LIMITED" -> ReportOutcome.RATE_LIMITED
            else -> if (failure.status == 429) ReportOutcome.RATE_LIMITED else ReportOutcome.FAILED
        }
    }

    fun message(outcome: ReportOutcome): String =
        tr(
            when (outcome) {
                ReportOutcome.RECEIVED -> "신고가 접수됐어요. 24시간 안에 확인할게요."
                ReportOutcome.OWN -> "내 글은 신고할 수 없어요"
                ReportOutcome.GONE -> "이미 내려갔거나 없는 글이에요"
                ReportOutcome.RATE_LIMITED -> "요청이 많아요. 잠시 뒤 다시 시도해 주세요"
                ReportOutcome.FAILED -> "신고를 보내지 못했어요. 잠시 뒤 다시 해 주세요"
            },
        )

    // --- 차단 결과 ---------------------------------------------------------------------------------

    enum class BlockOutcome {
        /** 차단했다 — 이미 차단한 사람이어도 이것이다(서버 200). */
        BLOCKED,

        /** 비회원이다(`401 SIGN_IN_REQUIRED`) — 로그인 화면으로. */
        SIGN_IN_REQUIRED,

        /** 자기 글이다(`CANNOT_BLOCK_SELF`). */
        OWN,

        /** 작성자가 탈퇴했다(`BLOCK_TARGET_HAS_NO_AUTHOR`). */
        NO_AUTHOR,

        /** 글이 없거나 이미 내려갔다(`BLOCK_TARGET_NOT_FOUND`). */
        GONE,
        FAILED,
    }

    fun blockOutcome(error: Throwable?): BlockOutcome {
        if (error == null) return BlockOutcome.BLOCKED
        if (!isApiError(error)) return BlockOutcome.FAILED
        val failure = NetworkFailure.of(error)
        if (AuthRules.action(failure.status ?: 0, failure.code) == AuthAction.PROMPT_SIGN_IN) {
            return BlockOutcome.SIGN_IN_REQUIRED
        }
        return when (failure.code) {
            "CANNOT_BLOCK_SELF" -> BlockOutcome.OWN
            "BLOCK_TARGET_HAS_NO_AUTHOR" -> BlockOutcome.NO_AUTHOR
            "BLOCK_TARGET_NOT_FOUND" -> BlockOutcome.GONE
            else -> BlockOutcome.FAILED
        }
    }

    /** 차단하지 못했을 때의 안내. 차단했으면 글이 바로 사라지는 것이 안내다 — 문구를 따로 띄우지 않는다. */
    fun message(outcome: BlockOutcome): String? =
        when (outcome) {
            BlockOutcome.BLOCKED, BlockOutcome.SIGN_IN_REQUIRED -> null
            BlockOutcome.OWN -> tr("내 글의 작성자는 차단할 수 없어요")
            BlockOutcome.NO_AUTHOR -> tr("탈퇴한 사용자는 차단할 수 없어요")
            BlockOutcome.GONE -> tr("이미 내려갔거나 없는 글이에요")
            BlockOutcome.FAILED -> tr("차단하지 못했어요. 잠시 뒤 다시 해 주세요")
        }

    // --- 금칙어 ------------------------------------------------------------------------------------

    /** 리뷰·여행후기·닉네임·코스 이름 저장이 금칙어에 걸렸다(`400 TEXT_NOT_ALLOWED`). */
    fun isTextNotAllowed(error: Throwable): Boolean = isTextNotAllowed(apiCode(error))

    fun isTextNotAllowed(code: String?): Boolean = code == "TEXT_NOT_ALLOWED"

    val textNotAllowedMessage: String get() = tr(TEXT_NOT_ALLOWED_MESSAGE)

    // --- 차단 목록 ---------------------------------------------------------------------------------

    /** 「2026년 10월 15일 차단」 / 「Blocked on Oct 15, 2026」. iOS `.dateTime.year().month().day()`. */
    fun blockedOn(
        user: BlockedUser,
        lang: Lang = AppLanguage.current,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val korean = lang == Lang.ko
        val formatter =
            DateTimeFormatter
                .ofLocalizedDate(if (korean) FormatStyle.LONG else FormatStyle.MEDIUM)
                .withLocale(if (korean) Locale.KOREAN else Locale.ENGLISH)
        val day = user.blockedAt.atZoneSameInstant(zone).format(formatter)
        return tr("%s 차단").format(day)
    }

    /** 문의 메일 (App Store 1.2 — 연락처 공개). 마이페이지·스토어 설명·처리방침이 같은 주소를 쓴다. */
    const val SUPPORT_EMAIL = "support@scenetrip.io"
    const val SUPPORT_MAIL_URI = "mailto:$SUPPORT_EMAIL"
}

/** 신고·차단 창구 — 생성 클라이언트(`ModerationApi`)만 부른다. 시험에서 가짜로 바꾼다. iOS `ModerationClient`. */
interface ModerationClient {
    suspend fun report(
        target: ModerationTarget,
        reason: ReportReason,
        detail: String?,
    )

    suspend fun block(target: ModerationTarget): BlockedUser

    suspend fun blocks(): List<BlockedUser>

    suspend fun unblock(blockId: Long)
}

class ModerationServerClient(
    private val installId: UUID,
) : ModerationClient {
    private val api by lazy { ModerationApi(API_BASE) }

    /** 비회원도 신고한다 — 설치 id 로 찾은 계정이 신고한 사람이 된다. 로그인했으면 공통 장치가 토큰을 싣는다. */
    override suspend fun report(
        target: ModerationTarget,
        reason: ReportReason,
        detail: String?,
    ) {
        withContext(Dispatchers.IO) {
            api.createReport(installId, ReportCreate(target.type, target.targetId, reason, detail))
        }
    }

    override suspend fun block(target: ModerationTarget): BlockedUser =
        withContext(Dispatchers.IO) { api.createBlock(BlockCreate(target.type, target.targetId)) }

    override suspend fun blocks(): List<BlockedUser> = withContext(Dispatchers.IO) { api.listMyBlocks().items }

    override suspend fun unblock(blockId: Long) {
        withContext(Dispatchers.IO) { api.deleteBlock(blockId) }
    }
}

/**
 * 차단·풀기가 일어났다는 앱 전체 신호 (MZ2AZ-461). 화면마다 받아 둔 리뷰·방문자 사진에 그 사람의 것이 남아 있을 수 있다
 * (검색 탭의 촬영지 상세는 탭을 옮겨도 살아 있다). 리뷰·사진을 쥔 화면은 이 값이 바뀌면 방문자 사진을 먼저 비운 뒤
 * 다시 받는다. iOS `ModerationEvents`.
 */
class ModerationEvents {
    private val counter = MutableStateFlow(0)

    /** 바뀔 때마다 오른다. */
    val version: StateFlow<Int> = counter.asStateFlow()

    fun changed() {
        counter.update { it + 1 }
    }

    companion object {
        val shared = ModerationEvents()
    }
}

/** 신고 시트·차단 확인 창·안내의 상태. */
data class ModerationUi(
    /** 신고 시트에 띄운 글. */
    val reporting: ModerationTarget? = null,
    /** 차단 확인 창에 띄운 글. */
    val confirmingBlock: ModerationTarget? = null,
    /** 차단하지 못한 까닭. */
    val notice: String? = null,
    val sending: Boolean = false,
)

/**
 * 목록 화면 하나가 들고, 줄의 「⋯」 가 부른다 — 신고 시트·차단 확인 창·안내를 화면 한 곳에 단다(`ModerationSheets`).
 * 지연 목록의 줄마다 시트를 달면 줄이 사라질 때 같이 사라진다. iOS `ModerationController`.
 */
class ModerationController(
    private val client: ModerationClient,
    private val signedIn: () -> Boolean = { AuthStore.signedIn },
    private val promptSignIn: () -> Unit = { AuthStore.promptSignIn() },
    private val events: ModerationEvents = ModerationEvents.shared,
) {
    private val state = MutableStateFlow(ModerationUi())
    val ui: StateFlow<ModerationUi> = state.asStateFlow()

    fun askReport(target: ModerationTarget) {
        state.update { it.copy(reporting = target) }
    }

    /** 차단은 가입한 사람만 — 비회원이면 확인 창 대신 로그인 화면을 올린다. */
    fun askBlock(target: ModerationTarget) {
        if (!signedIn()) {
            promptSignIn()
            return
        }
        state.update { it.copy(confirmingBlock = target) }
    }

    fun closeReport() {
        state.update { it.copy(reporting = null) }
    }

    fun closeBlockConfirm() {
        state.update { it.copy(confirmingBlock = null) }
    }

    fun clearNotice() {
        state.update { it.copy(notice = null) }
    }

    suspend fun report(
        target: ModerationTarget,
        reason: ReportReason,
        detail: String,
    ): ModerationRules.ReportOutcome {
        if (state.value.sending) return ModerationRules.ReportOutcome.FAILED
        state.update { it.copy(sending = true) }
        try {
            client.report(target, reason, ModerationRules.detail(detail))
            return ModerationRules.ReportOutcome.RECEIVED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ModerationRules.reportOutcome(error)
        } finally {
            state.update { it.copy(sending = false) }
        }
    }

    /** 차단했으면 `true` — 부른 화면이 그 글을 지우고 목록을 다시 받는다. */
    suspend fun block(target: ModerationTarget): Boolean {
        state.update { it.copy(confirmingBlock = null) }
        if (!signedIn()) {
            promptSignIn()
            return false
        }
        if (state.value.sending) return false
        state.update { it.copy(sending = true) }
        val outcome =
            try {
                client.block(target)
                ModerationRules.BlockOutcome.BLOCKED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ModerationRules.blockOutcome(error)
            } finally {
                state.update { it.copy(sending = false) }
            }
        if (outcome == ModerationRules.BlockOutcome.SIGN_IN_REQUIRED) promptSignIn()
        state.update { it.copy(notice = ModerationRules.message(outcome)) }
        if (outcome == ModerationRules.BlockOutcome.BLOCKED) events.changed()
        return outcome == ModerationRules.BlockOutcome.BLOCKED
    }
}

/** 차단 목록 화면의 상태. */
data class BlockedUsersUi(
    val items: List<BlockedUser> = emptyList(),
    val phase: Phase = Phase.LOADING,
    /** 풀고 있는 줄 — 단추를 잠근다. */
    val unblocking: Set<Long> = emptySet(),
    val message: String? = null,
) {
    enum class Phase { LOADING, LOADED, FAILED }
}

/** 마이페이지 「차단한 사용자」 — 목록 받기와 풀기. iOS `BlockedUsersStore`. */
class BlockedUsersStore(
    private val client: ModerationClient,
    private val events: ModerationEvents = ModerationEvents.shared,
) {
    private val state = MutableStateFlow(BlockedUsersUi())
    val ui: StateFlow<BlockedUsersUi> = state.asStateFlow()

    /** 이 화면에서 푼 줄을 푼 차례대로 — 풀기 전에 떠난 목록 응답이 늦게 와서 푼 줄을 되살리지 않게 한다. */
    private val unblockedLog = mutableListOf<Long>()

    suspend fun reload() {
        if (state.value.items.isEmpty()) state.update { it.copy(phase = BlockedUsersUi.Phase.LOADING) }
        val mark = unblockedLog.size
        try {
            val fresh = client.blocks()
            // 받는 사이 푼 줄은 늦은 응답에 남아 있어도 빼 둔다.
            val gone = unblockedLog.drop(mark).toSet()
            state.update { it.copy(items = fresh.filterNot { user -> user.id in gone }, phase = BlockedUsersUi.Phase.LOADED) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 받아 둔 목록이 있으면 그대로 둔다.
            state.update { if (it.phase != BlockedUsersUi.Phase.LOADED) it.copy(phase = BlockedUsersUi.Phase.FAILED) else it }
        }
    }

    fun clearMessage() {
        state.update { it.copy(message = null) }
    }

    /** 풀었으면 `true`. 이미 없는 줄(`BLOCK_NOT_FOUND`)도 풀린 것이다 — 목록에서 뺀다. */
    suspend fun unblock(user: BlockedUser): Boolean {
        if (user.id in state.value.unblocking) return false
        state.update { it.copy(unblocking = it.unblocking + user.id) }
        try {
            try {
                client.unblock(user.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 다른 기기에서 이미 풀었다면 풀린 것으로 본다.
                if (ModerationRules.apiCode(error) != "BLOCK_NOT_FOUND") {
                    state.update { it.copy(message = tr("차단을 풀지 못했어요. 잠시 뒤 다시 해 주세요")) }
                    return false
                }
            }
            unblockedLog.add(user.id)
            state.update { it.copy(items = it.items.filterNot { row -> row.id == user.id }) }
            events.changed()
            return true
        } finally {
            state.update { it.copy(unblocking = it.unblocking - user.id) }
        }
    }
}
