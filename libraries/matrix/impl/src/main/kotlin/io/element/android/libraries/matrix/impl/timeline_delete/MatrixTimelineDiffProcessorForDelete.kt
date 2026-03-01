/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline_delete

import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.RoomMembershipContent
import io.element.android.libraries.matrix.impl.timeline.MatrixTimelineItemMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import timber.log.Timber

/**
 * 处理来自 Rust SDK的TimelineDiff。只返回增量。用来删除历史记录。
 */
internal class MatrixTimelineDiffProcessorForDelete(
    private val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>>,
    private val timelineItemFactory: MatrixTimelineItemMapperForDelete,
) {
    private val mutex = Mutex()

    private val _membershipChangeEventReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val membershipChangeEventReceived: Flow<Unit> = _membershipChangeEventReceived

    suspend fun postDiffs(diffs: List<TimelineDiff>) {
        updateTimelineItems {
            Timber.v("Update timeline items from postDiffs (with ${diffs.size} items) on ${Thread.currentThread()}")
            diffs.forEach { diff ->
                applyDiff(diff)
            }
        }
    }

    private suspend fun updateTimelineItems(block: MutableList<MatrixTimelineItem>.() -> Unit) =
        mutex.withLock {
            // 这里总是创建空列表？那就不能依赖diff.index，否则会错乱！！！
            val mutableTimelineItems: MutableList<MatrixTimelineItem> = mutableListOf()
            block(mutableTimelineItems)
            timelineItems.tryEmit(mutableTimelineItems)
        }

    /**
     * 由于每个page过来时，updateTimelineItems函数都创建空list，因此这里对list的操作，不能依赖index，否则会错乱！！！
     * 向后翻页，得到的Diff类型没有Append，PushBack，Remove；有PushFront，Set，Insert，而且insert是最多的。
     */
    private fun MutableList<MatrixTimelineItem>.applyDiff(diff: TimelineDiff) {
        when (diff) {
            is TimelineDiff.Append -> {
                val items = diff.values.map { it.asMatrixTimelineItem() }
                addAll(items)
            }
            is TimelineDiff.PushBack -> {
                val item = diff.value.asMatrixTimelineItem()
                if (item is MatrixTimelineItem.Event && item.event.content is RoomMembershipContent) {
                    // TODO - This is a temporary solution to notify the room screen about membership changes
                    // Ideally, this should be implemented by the Rust SDK
                    _membershipChangeEventReceived.tryEmit(Unit)
                }
                add(item)
            }
            is TimelineDiff.PushFront -> {
                val item = diff.value.asMatrixTimelineItem()
                add(0, item)
            }
            is TimelineDiff.Set -> {
                val item = diff.value.asMatrixTimelineItem()
                //set(diff.index.toInt(), item)
                add(0, item) //index错乱，因此不能用index。
            }
            is TimelineDiff.Insert -> {
                val item = diff.value.asMatrixTimelineItem()
                //add(diff.index.toInt(), item)
                add(0, item) //index错乱，因此不能用index
            }
            is TimelineDiff.Remove -> {
                //removeAt(diff.index.toInt()) //index错乱，因此不能用index
                // 不能删除，以免伤及无辜
            }
            is TimelineDiff.Reset -> {
                clear()
                val items = diff.values.map { it.asMatrixTimelineItem() }
                addAll(items)
            }
            TimelineDiff.PopFront -> {
                removeFirstOrNull()
            }
            TimelineDiff.PopBack -> {
                removeLastOrNull()
            }
            TimelineDiff.Clear -> {
                clear()
            }
            is TimelineDiff.Truncate -> {
                subList(diff.length.toInt(), size).clear()
            }
        }
    }

    private fun TimelineItem.asMatrixTimelineItem(): MatrixTimelineItem {
        return timelineItemFactory.map(this)
    }
}

