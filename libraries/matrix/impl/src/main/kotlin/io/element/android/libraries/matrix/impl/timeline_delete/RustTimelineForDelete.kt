/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline_delete

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.TimelineException
import io.element.android.libraries.matrix.api.timeline.TimelineForDelete
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.impl.exception.mapClientException
import io.element.android.libraries.matrix.impl.timeline.item.virtual.VirtualTimelineItemMapper
import io.element.android.libraries.matrix.impl.timeline.liveBackPaginationStatus
import io.element.android.libraries.matrix.impl.timeline.toRustEventOrTransactionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import timber.log.Timber
import uniffi.matrix_sdk.RoomPaginationStatus
import org.matrix.rustcomponents.sdk.Timeline as InnerTimeline

private const val PAGINATION_SIZE = 50

/**
 * 为了删除大量聊天记录时不撑爆内存，就改写RustTimeline。timelineItems不再全量返回，而是增量返回
 */
open class RustTimelineForDelete(
    private val inner: InnerTimeline,
    override val mode: TimelineForDelete.Mode,
    private val joinedRoom: JoinedRoom,
    private val coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    onNewSyncedEvent: () -> Unit,
) : TimelineForDelete {
    private val _timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)

    private val timelineEventContentMapper = TimelineEventContentMapperForDelete() //ForDelete
    private val timelineItemMapper = MatrixTimelineItemMapperForDelete( //ForDelete
        fetchDetailsForEvent = this::fetchDetailsForEvent,
        coroutineScope = coroutineScope,
        virtualTimelineItemMapper = VirtualTimelineItemMapper(),
        eventTimelineItemMapper = EventTimelineItemMapperForDelete( //ForDelete
            contentMapper = timelineEventContentMapper
        )
    )
    private val timelineDiffProcessor = MatrixTimelineDiffProcessorForDelete(//ForDelete
        timelineItems = _timelineItems,
        timelineItemFactory = timelineItemMapper,
    )
    private val timelineItemsSubscriber = TimelineItemsSubscriberForDelete(//ForDelete
        timeline = inner,
        timelineCoroutineScope = coroutineScope,
        timelineDiffProcessor = timelineDiffProcessor,
        dispatcher = dispatcher,
        onNewSyncedEvent = onNewSyncedEvent,
    )
    override val backwardPaginationStatus = MutableStateFlow(
        TimelineForDelete.PaginationStatus(
            isPaginating = false,
            hasMoreToLoad = mode != TimelineForDelete.Mode.PinnedEvents
        )
    )

    override val forwardPaginationStatus = MutableStateFlow(
        TimelineForDelete.PaginationStatus(
            isPaginating = false,
            hasMoreToLoad = mode is TimelineForDelete.Mode.FocusedOnEvent
        )
    )

    init {

        if (mode == TimelineForDelete.Mode.Live) {
            // When timeline is live, we need to listen to the back pagination status as
            // sdk can automatically paginate backwards.
            coroutineScope.registerBackPaginationStatusListener()
        }
    }

    private fun CoroutineScope.registerBackPaginationStatusListener() {
        inner.liveBackPaginationStatus()
            .onEach { backPaginationStatus ->
                updatePaginationStatus(TimelineForDelete.PaginationDirection.BACKWARDS) {
                    when (backPaginationStatus) {
                        is RoomPaginationStatus.Idle -> it.copy(isPaginating = false, hasMoreToLoad = !backPaginationStatus.hitTimelineStart)
                        is RoomPaginationStatus.Paginating -> it.copy(isPaginating = true, hasMoreToLoad = true)
                    }
                }
            }
            .launchIn(this)
    }

    private fun updatePaginationStatus(direction: TimelineForDelete.PaginationDirection, update: (TimelineForDelete.PaginationStatus) -> TimelineForDelete.PaginationStatus) {
        when (direction) {
            TimelineForDelete.PaginationDirection.BACKWARDS -> backwardPaginationStatus.getAndUpdate(update)
            TimelineForDelete.PaginationDirection.FORWARDS -> forwardPaginationStatus.getAndUpdate(update)
        }
    }

    // Use NonCancellable to avoid breaking the timeline when the coroutine is cancelled.
    override suspend fun paginate(direction: TimelineForDelete.PaginationDirection): Result<Boolean> =
        withContext(NonCancellable) {
            withContext(dispatcher) {
                runCatchingExceptions {
                    if (!canPaginate(direction)) throw TimelineException.CannotPaginate
                    updatePaginationStatus(direction) { it.copy(isPaginating = true) }
                    when (direction) {
                        TimelineForDelete.PaginationDirection.BACKWARDS -> inner.paginateBackwards(
                            PAGINATION_SIZE.toUShort()
                        )
                        TimelineForDelete.PaginationDirection.FORWARDS -> inner.paginateForwards(
                            PAGINATION_SIZE.toUShort()
                        )
                    }
                }.onFailure { error ->
                    if (error is TimelineException.CannotPaginate) {
                        Timber.Forest.d("Can't paginate $direction on room ${joinedRoom.roomId} with paginationStatus: ${backwardPaginationStatus.value}")
                    } else {
                        updatePaginationStatus(direction) { it.copy(isPaginating = false) }
                        Timber.Forest.e(
                            error,
                            "Error paginating $direction on room ${joinedRoom.roomId}"
                        )
                    }
                }.onSuccess { hasReachedEnd ->
                    updatePaginationStatus(direction) {
                        it.copy(
                            isPaginating = false,
                            hasMoreToLoad = !hasReachedEnd
                        )
                    }
                }
            }
        }

    private fun canPaginate(direction: TimelineForDelete.PaginationDirection): Boolean {
        return when (direction) {
            TimelineForDelete.PaginationDirection.BACKWARDS -> backwardPaginationStatus.value.canPaginate
            TimelineForDelete.PaginationDirection.FORWARDS -> forwardPaginationStatus.value.canPaginate
        }
    }

    override val timelineItems: Flow<List<MatrixTimelineItem>> = _timelineItems
        .onStart {
            timelineItemsSubscriber.subscribeIfNeeded()
        }.onCompletion {
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }

    override suspend fun redactEvent(eventOrTransactionId: EventOrTransactionId, reason: String?): Result<Unit> = withContext(dispatcher) {
        // 先用原来的 runCatchingExceptions 包住 inner.redactEvent
        val raw = runCatchingExceptions {
            inner.redactEvent(
                eventOrTransactionId = eventOrTransactionId.toRustEventOrTransactionId(),
                reason = reason,
            )
        }

        // 无论成功失败，都在这里把异常 map 到 ElementX 的 ClientException
        raw.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { throwable ->
                // 转换 Rust 异常成 ElementX 异常
                val mapped = throwable.mapClientException()
                Result.failure(mapped)
            }
        )
    }

    override fun close() {
        coroutineScope.cancel()
        inner.close()
    }

    private suspend fun fetchDetailsForEvent(eventId: EventId): Result<Unit> =
        withContext(dispatcher) {
            runCatchingExceptions {
                inner.fetchDetailsForEvent(eventId.value)
            }
        }
}
