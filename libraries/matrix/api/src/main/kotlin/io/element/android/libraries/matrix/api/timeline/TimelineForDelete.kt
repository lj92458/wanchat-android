/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.timeline

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize

interface TimelineForDelete : AutoCloseable {
    data class PaginationStatus(
        val isPaginating: Boolean,
        val hasMoreToLoad: Boolean,
    ) {
        val canPaginate: Boolean = !isPaginating && hasMoreToLoad
    }

    enum class PaginationDirection {
        BACKWARDS,
        FORWARDS
    }

    @Parcelize
    @Immutable
    sealed interface Mode : Parcelable {
        data object Live : Mode
        data class FocusedOnEvent(val eventId: EventId) : Mode
        data object PinnedEvents : Mode
        data object Media : Mode
        data class Thread(val threadRootId: ThreadId) : Mode
    }

    val mode: Mode
    suspend fun paginate(direction: PaginationDirection): Result<Boolean>

    val backwardPaginationStatus: StateFlow<PaginationStatus>
    val forwardPaginationStatus: StateFlow<PaginationStatus>

    val timelineItems: Flow<List<MatrixTimelineItem>>
    suspend fun redactEvent(eventOrTransactionId: EventOrTransactionId, reason: String?): Result<Unit>
}
