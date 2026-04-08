/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.room

import io.element.android.libraries.core.tasks.LongTaskManager
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.exception.ErrorKind
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.TimelineForDelete
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.LegacyCallInviteContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

data class DelayInfo(val value: Double, val unit: String) {
    val inMilliseconds: Double
        get() = when (unit) {
            "ms" -> value
            "s" -> value * 1000
            "m" -> value * 1000 * 60
            "h" -> value * 1000 * 60 * 60
            "d" -> value * 1000 * 60 * 60 * 24
            "w" -> value * 1000 * 60 * 60 * 24 * 7
            "M" -> value * 1000 * 60 * 60 * 24 * 30
            "y" -> value * 1000 * 60 * 60 * 24 * 365
            else -> value
        }

    override fun toString(): String = "$value$unit"

    companion object {
        /**
         * 从字符串创建 DelayInfo 对象，自动解析数值和单位
         * @param input 如 "941ms", "2s", "1.5h" 等格式
         */
        fun fromString(input: String): DelayInfo? {
            val pattern = Regex("""([\d.]+)([a-zA-Z]+)""")
            val matchResult = pattern.find(input) ?: return null
            val valueStr = matchResult.groupValues[1]
            val unit = matchResult.groupValues[2]
            val value = valueStr.toDoubleOrNull() ?: return null
            return DelayInfo(value, unit)
        }
    }
}

/**
 * 解析 429 M_LIMIT_EXCEEDED异常消息。
 * ClientException.Generic.details=RedactError(HttpError(Api(Server(ClientApi(Error { status_code: 429, body: Standard(StandardErrorBody { kind: LimitExceeded { retry_after: Some(Delay(941ms)) }, message: "Too Many Requests" }) })))))
 */
fun extractDelayTime(errorMessage: String): DelayInfo? {
    val pattern = """Delay\(([\d.]+)(s|ms)\)""".toRegex()
    val matchResult = pattern.find(errorMessage) ?: return null
    val value = matchResult.groupValues[1].toDoubleOrNull() ?: return null
    val unit = matchResult.groupValues[2]
    return DelayInfo(value, unit)
}

suspend fun JoinedRoom.safeRedact(eventId: EventId) {
    while (true) {
        try {
            Timber.d("clearMessagesUnlimitedHelper")
            this.redact(eventId.toString(), null).getOrThrow()
            /* 首次等待2000，等报错了再继续等待？这会消耗5秒
            只等3000，等报错了再继续等待？这会消耗4.8秒
            只等4000，等报错了再继续等待？这会消耗4.8秒。但我还是愿意首次等待2000，因为可能有更优秀的服务器
            */
            delay(2000)
            return
        } catch (e: CancellationException) {
            // 绝不能吞，必须throw，否则会导致协程无法取消
            throw e
        } catch (e: ClientException.MatrixApi) {
            //几乎执行不到这里，已知服务器会返回的是ClientException.Generic，但这能兼容其他服务器
            when (val kind = e.kind) {
                is ErrorKind.LimitExceeded -> {
                    val retry = (kind.retryAfterMs ?: 1500).coerceAtLeast(200) // 给一个最小值避免0
                    Timber.w("Rate limited (MatrixApi), retrying after $retry ms")
                    delay(retry)
                }

                else -> {
                    Timber.e(e)
                    return
                }
            }
        } catch (e: ClientException.Generic) {
            // 再次 fallback，万一服务器返回结构奇怪，Rust层没识别为 MatrixApi
            if (e.message?.contains("M_LIMIT_EXCEEDED", ignoreCase = true) == true
                || e.details?.contains("kind: LimitExceeded", ignoreCase = true) == true
            ) {
                val retry = e.details?.let { extractDelayTime(it)?.inMilliseconds } ?: 100
                Timber.w("Rate limited (generic), retrying after %s ms, details: %s", retry, e.details)
                delay(retry.toLong())
            } else {
                Timber.e(e)
                return
            }
        } catch (e: Exception) {
            Timber.e(e)
            return
        }
    }
}

fun clearProgressIsRunning(roomStr: String) = LongTaskManager.progress.value[roomStr]?.isRunning ?: false

//data class UserInfo(var permitRecipient: Boolean = true)
data class RoomRuntimeState(
    var isRead: Boolean = false,
    var permitRecipient: Boolean = true,
    var members: List<RoomMember> = emptyList(),
    //var userInfoMap: MutableMap<UserId, UserInfo> = emptyMap<UserId, UserInfo>().toMutableMap(),
    var redactMsgJob: Job? = null,
    @Volatile
    var lastAccessAt: Long = System.currentTimeMillis()
)

/**带有自动销毁功能的全局对象，防止内存泄露 */
object RoomRuntimeRegistry {
    private const val EXPIRE_MS = 24 * 60 * 60_000L // 24小时
    private const val CHECK_INTERVAL_MS = 20 * 60_000L // 20 分钟
    private val states = mutableMapOf<String, RoomRuntimeState>()
    private val lock = Any()

    // 👇 Registry 自己的协程
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    init {
        scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                cleanupExpired()
            }
        }
    }

    /** 获取或创建，并自动续命 */
    fun get(roomId: String): RoomRuntimeState = synchronized(lock) {
        val state = states.getOrPut(roomId) { RoomRuntimeState() }
        state.lastAccessAt = System.currentTimeMillis()
        state
    }

    /** 主动移除（room 退出、autoDelete 关闭等） */
    fun remove(roomId: String) = synchronized(lock) { states.remove(roomId)?.redactMsgJob?.cancel() }

    /** 定期清理 */
    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = mutableListOf<String>()
        synchronized(lock) {
            for ((roomId, state) in states) {
                if (now - state.lastAccessAt > EXPIRE_MS) {
                    expired += roomId
                }
            }
            expired.forEach { roomId -> states.remove(roomId)?.redactMsgJob?.cancel() }
        }
    }
}

const val MAX_DELAY_TIME: Long = 10 * 60 * 1000
fun needClear(event: MatrixTimelineItem.Event): Boolean {
    return when (event.event.content) {
        is MessageContent,
        is StickerContent,
        is LegacyCallInviteContent,
        is CallNotifyContent,
        is PollContent -> true

        else -> false
    }
}

fun needClearAll(event: MatrixTimelineItem.Event): Boolean {
    return when (event.event.content) {
        is RedactedContent -> false
        else -> true
    }
}

/**
 * 清空房间的消息
 */
suspend fun JoinedRoom.clearRoom() {
    val room = this
    supervisorScope {
        val timeline = room.timelineForDelete
        val roomId = room.roomId.value
        try {
            val items = ConcurrentHashMap<EventId, MatrixTimelineItem.Event>()
            var pageCount = 0
            val emptyPageNum = AtomicInteger(0)
            // Step 1: collect timeline
            val jobCollect = launch {
                try {
                    //先翻到最新，再向后翻
                    do {
                        Timber.d("paginate in while0, ")
                    } while (!timeline.paginate(TimelineForDelete.PaginationDirection.FORWARDS).getOrElse { true })

                    timeline.timelineItems.collect { list ->
                        for (item in list) {
                            (item as? MatrixTimelineItem.Event)?.let { event ->
                                if (needClear(event)) event.eventId?.let { items[it] = event }
                            }
                        }
                        LongTaskManager.update(roomId) {
                            // 比较两次更新的数量,如果没变，就说明上面的分页全都是已经删除过的，就停止工作。
                            if (it.collectedItems == items.size) {
                                emptyPageNum.addAndGet(1)
                                it
                            } else {
                                emptyPageNum.set(0)
                                it.copy(collectedItems = items.size)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Timber.e(e, "timeline collect failed")
                }
            }

            // Step 2: paginate backwards
            Timber.d("in while1,pageCount= %s", pageCount)
            do {
                pageCount++
                LongTaskManager.update(roomId) { it.copy(loadedPages = pageCount) }
                Timber.d("in while1,pageCount= %s", pageCount)
            } while (
                clearProgressIsRunning(roomId)
                && emptyPageNum.get() <= 10
                && !timeline.paginate(TimelineForDelete.PaginationDirection.BACKWARDS).getOrElse { true })
            // 等待 timelineItems 最终稳定
            var last = -1
            var stable = 0
            while (stable < 5) {
                if (items.size == last) stable++ else stable = 0
                last = items.size
                delay(100)
            }
            jobCollect.cancelAndJoin()
            LongTaskManager.update(roomId) { it.copy(totalPages = pageCount) }

            // Step 3: 删除消息
            var deleted = 0
            suspend fun doDeleteItem(item: MatrixTimelineItem.Event) {
                item.eventId?.let { eventId ->
                    Timber.d("Redacting $eventId")
                    room.safeRedact(eventId)
                    LongTaskManager.update(roomId) { it.copy(deletedItems = deleted) }
                    deleted++
                }
            }
            for (item in items.values.toList().sortedByDescending { it.event.timestamp }) {
                if (!clearProgressIsRunning(roomId)) break
                doDeleteItem(item)
            }// end for
        } catch (e: Throwable) {
            LongTaskManager.update(roomId) { it.copy(error = e) }
        }
    } //end coroutineScope
}

/**
 * 智能删除、阅后即焚（只扫描一遍，需要事件驱动，或while驱动）
 */
suspend fun JoinedRoom.deleteSmartly(stopTime: Long, waitingTime: Long, roomRuntimeState: RoomRuntimeState): Int {
    val room = this
    var willDeleteNum = 0
    supervisorScope {
        val timeline = room.timelineForDelete
        val roomId = room.roomId.value
        try {
            val items = ConcurrentHashMap<EventId, MatrixTimelineItem.Event>()
            var pageCount = 0
            val emptyPageNum = AtomicInteger(0)
            // Step 1: collect timeline
            val jobCollect = launch {
                try {
                    do {//先翻到最新，再向后翻
                        Timber.d("paginate in while0, ")
                    } while (!timeline.paginate(TimelineForDelete.PaginationDirection.FORWARDS).getOrElse { true })
                    timeline.timelineItems.collect { list ->
                        for (item in list) {
                            (item as? MatrixTimelineItem.Event)?.let { event ->
                                //动态计算应该获取的数量，确保在waitingTime内，能处理完
                                if (needClear(event)) event.eventId?.let { items[it] = event }
                            }
                        }
                        LongTaskManager.update(roomId) {
                            // 比较两次更新的数量,如果没变，就说明上面的分页全都是已经删除过的，就停止工作。
                            if (it.collectedItems == items.size) {
                                emptyPageNum.addAndGet(1)
                                it
                            } else {
                                emptyPageNum.set(0)
                                it.copy(collectedItems = items.size)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Timber.e(e, "timeline collect failed")
                }
            }

            // Step 2: paginate backwards
            Timber.d("in while1,pageCount= %s", pageCount)
            do {
                pageCount++
                LongTaskManager.update(roomId) { it.copy(loadedPages = pageCount) }
                Timber.d("in while1,pageCount= %s", pageCount)
            } while (
                clearProgressIsRunning(roomId)
                && emptyPageNum.get() <= 3
                && items.size < waitingTime / 5000
                && !timeline.paginate(TimelineForDelete.PaginationDirection.BACKWARDS).getOrElse { true })
            // 等待 timelineItems 最终稳定
            var last = -1
            var stable = 0
            while (stable < 5) {
                if (items.size == last) stable++ else stable = 0
                last = items.size
                delay(100)
            }
            jobCollect.cancelAndJoin()
            LongTaskManager.update(roomId) { it.copy(totalPages = pageCount) }

            // Step 3: 删除消息

            var deleted = 0

            suspend fun doDeleteItem(item: MatrixTimelineItem.Event) {
                item.eventId?.let { eventId ->
                    Timber.d("Redacting $eventId")
                    room.safeRedact(eventId)
                    LongTaskManager.update(roomId) { it.copy(deletedItems = deleted) }
                    deleted++
                }
            }

            val delayList = mutableListOf<MatrixTimelineItem.Event>()
            for (item in items.values.toList().sortedByDescending { it.event.timestamp }) {
                if (!clearProgressIsRunning(roomId)) break

                // 不论是否应该处理这条消息，都读取它携带的回执信息
                item.event.receipts.forEach { it -> roomRuntimeState.isRead = true }

                //定时删除。多人房间和两人房间策略不同：多人房间不能依赖 read receipt，只应该根据消息发送时间决定删除；
                // 两人房间，放过没阅读的消息，且把要删除的消息加入 5 分钟延迟队列。
                if (System.currentTimeMillis() > item.event.timestamp + waitingTime) {
                    if (roomRuntimeState.members.size >= 2) { //两人
                        // 某人发的消息，需要等对方已读，或对方禁止发送已读回执，或对方不存在，才能删除。
                        if (roomRuntimeState.isRead || !roomRuntimeState.permitRecipient) {
                            delayList.add(item)
                        }
                    } else { //多人，是否也应该加入延迟队列？
                        //doDeleteItem(item)
                        delayList.add(item)
                    }
                }
            }// end for
            //如果for循环完了，都没发现已读标记，就说明对方禁止发送已读标记(这个判断不准确，但没办法)，那么下次就直接删除。

            if (!roomRuntimeState.isRead && items.size > 20) roomRuntimeState.permitRecipient = false
            else if (roomRuntimeState.isRead) roomRuntimeState.permitRecipient = true   // 自动恢复

            //创建线程，延迟10分钟后删除
            if (delayList.isNotEmpty()) {
                willDeleteNum = delayList.size
                val snapshot = delayList.toList()
                roomRuntimeState.redactMsgJob = LongTaskManager.AppScope.scope.launch {
                    //delay(MAX_DELAY_TIME / 2) // 独立计时
                    delay(30 * 1000)
                    snapshot.forEach { doDeleteItem(it) }
                }
            }
        } catch (e: Throwable) {
            LongTaskManager.update(roomId) { it.copy(error = e) }
        }
    } //end coroutineScope
    return willDeleteNum
}


