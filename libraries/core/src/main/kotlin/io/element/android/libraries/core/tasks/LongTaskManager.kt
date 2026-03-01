/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object LongTaskManager {
    object AppScope {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
    }

    data class ClearTaskProgress(
        val roomId: String,
        val clearType: ClearType = ClearType.DELETE,
        val totalPages: Int = 0,
        val loadedPages: Int = 0,
        val collectedItems: Int = 0,
        val deletedItems: Int = 0,
        val isRunning: Boolean = false,
        val error: Throwable? = null
    )

    enum class ClearType {
        /** 清空*/
        CLEAR,

        /** 自动删除一部分*/
        DELETE
    }

    private val job = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.IO + job)

    private val _progress = MutableStateFlow<Map<String, ClearTaskProgress>>(emptyMap())
    val progress = _progress.asStateFlow()

    fun update(roomId: String, block: (ClearTaskProgress) -> ClearTaskProgress) {
        val old = _progress.value[roomId] ?: ClearTaskProgress(roomId)
        _progress.update { it + (roomId to block(old)) }
    }

    fun runTask(
        before: suspend () -> Unit,
        task: suspend () -> Unit,
        after: suspend () -> Unit,
        roomId: String,
        clearType: ClearType
    ) {
        scope.launch {
            try {
                update(roomId) { it.copy(clearType = clearType, isRunning = true, deletedItems = 0, loadedPages = 0, totalPages = 0) }
                before()
                task()
            } finally {
                after()
                update(roomId) { it.copy(isRunning = false) }
                //_progress.update { it - roomId }
            }
        }
    }
}
