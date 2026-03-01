/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.permissions.api

import io.element.android.libraries.architecture.Presenter

/**
 * 只能处理运行时权限(app弹窗请求)。如果索要特殊权限(跳转到系统设置界面)，请使用SystemUtils中的函数
 */
interface PermissionsPresenter : Presenter<PermissionsState> {
    interface Factory {
        fun create(permission: String): PermissionsPresenter
    }
}
