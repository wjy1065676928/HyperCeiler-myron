/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions

 * ---------------------------------------------------------------------------
 * 移植说明 / Ported from XiaomiHelper
 *
 *   上游文件: AppDetailClickOpen.kt
 *   上游仓库: https://github.com/HowieHChen/XiaomiHelper
 *   上游路径: app/src/main/kotlin/dev/lackluster/mihelper/hook/rules/securitycenter/AppDetailClickOpen.kt
 *   原作者  : HowieHChen
 *   上游许可: SPDX-License-Identifier: GPL-3.0-or-later
 *
 *   上游原始版权声明（照录）:
 *     This file is part of XiaomiHelper project
 *     This file references YukiVoyager
 *     Copyright (C) 2023 hosizoraru
 *
 *   许可兼容性: 上游 GPL-3.0-or-later 可并入本项目的 AGPL-3.0。
 *
 *   移植差异: 上游使用 KavaRef 的 resolve()/extraOf()/hook {} 写法，
 *   此处改写为 HyperCeiler 的 ezHookTool + Java 反射写法，
 *   功能保持不变 —— 在「应用详情」页点击应用图标即可打开该应用。
 * ---------------------------------------------------------------------------
 */
package com.sevtinge.hyperceiler.libhook.rules.securitycenter.app

import android.content.pm.PackageInfo
import android.widget.ImageView
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * 应用详情页点击应用图标打开该应用。
 *
 * 上游（XiaomiHelper）实现要点：
 * - `ApplicationsDetailsFragment.initView()` 之后，从 fragment 的 PackageInfo 字段
 *   取出包名，存到页面标题的 AppDetailTitlePreference 上；
 * - `AppDetailTitlePreference.onBindViewHolder()` 之后，取出该包名，
 *   为其中的 ImageView 设置点击监听，点击即用 getLaunchIntentForPackage 启动应用。
 *
 * 移植注意：上游类名写作 `ApplicantionsDetailsFragment`（拼写笔误）。
 * 已在本机（myron / OS4）实测确认真名为 `ApplicationsDetailsFragment`：
 * 从 /product/priv-app/MIUISecurityCenter/MIUISecurityCenter.apk 反编译后，
 * 4 个 dex 中 `ApplicationsDetailsFragment` 命中 1 处、`Applicantions...` 命中 0 处。
 * 注意该类名前缀虽为 com.miui.appmanager，但代码实际打包在手机管家
 * （com.miui.securitycenter，`pm path com.miui.appmanager` 为空）。
 */
object AppDetailClickOpen : BaseHook() {

    private const val TAG = "AppDetailClickOpen"

    /** 已实测确认（见文件头说明）。 */
    private const val CLS_DETAILS_FRAGMENT =
        "com.miui.appmanager.fragment.ApplicationsDetailsFragment"

    private const val CLS_TITLE_PREF =
        "com.miui.appmanager.widget.AppDetailTitlePreference"

    /**
     * 页面标题 preference 实例 -> 该页面对应的包名。
     * 上游用 KavaRef 的 extraOf("KEY_PKG_NAME") 动态附加字段实现；
     * 这里用 WeakHashMap 承载，避免往系统对象里塞额外字段。
     * 全部在主线程读写，无需额外同步。
     */
    private val pkgNames = WeakHashMap<Any, String>()

    /**
     * 已经挂过点击监听的 title preference -> 当时的包名。
     * onBindViewHolder 会被 RecyclerView 反复调用，借此跳过重复挂载。
     */
    private val boundPkgs = WeakHashMap<Any, String>()

    override fun init() {
        val fragmentCls = loadClassOrNull(CLS_DETAILS_FRAGMENT)
        if (fragmentCls == null) {
            XposedLog.e(TAG, "$CLS_DETAILS_FRAGMENT not found")
            return
        }

        val titlePrefCls = loadClassOrNull(CLS_TITLE_PREF)
        if (titlePrefCls == null) {
            XposedLog.e(TAG, "$CLS_TITLE_PREF not found")
            return
        }

        val piField = findFieldByType(fragmentCls, PackageInfo::class.java)
        val titlePrefField = findFieldByType(fragmentCls, titlePrefCls)
        XposedLog.d(TAG, "packageInfoField=$piField, titlePrefField=$titlePrefField")
        if (piField == null || titlePrefField == null) {
            XposedLog.e(TAG, "required fields not found, skip")
            return
        }

        // 1) initView() 之后：记录当前页面的包名
        runCatching {
            fragmentCls.findMethod { name("initView") }?.createAfterHook { param ->
                runCatching {
                    val packageInfo = piField.get(param.thisObject) as? PackageInfo ?: return@runCatching
                    val titlePref = titlePrefField.get(param.thisObject) ?: return@runCatching
                    pkgNames[titlePref] = packageInfo.packageName
                    XposedLog.d(TAG, "captured pkg=${packageInfo.packageName}")
                    // 触发一次刷新，使 onBindViewHolder 立即拿到包名（对齐上游行为）
                    runCatching {
                        titlePref.javaClass.methods
                            .firstOrNull { it.name == "notifyChanged" && it.parameterCount == 0 }
                            ?.invoke(titlePref)
                    }
                }.onFailure { XposedLog.e(TAG, "initView after failed: ${it.message}") }
            } ?: XposedLog.e(TAG, "initView method not found")
        }.onFailure { XposedLog.e(TAG, "hook initView failed: ${it.message}") }

        // 2) 标题 preference 绑定之后：给图标加点击打开
        runCatching {
            titlePrefCls.findMethod { name("onBindViewHolder") }?.createAfterHook { param ->
                runCatching {
                    val titlePref = param.thisObject ?: return@runCatching
                    val pkg = pkgNames[titlePref] ?: return@runCatching
                    // onBindViewHolder 会被 RecyclerView 反复调用（首次绑定、滚动复用、主动刷新）。
                    // 同一 titlePref 同一包名只需挂一次监听，重复挂载是纯浪费。
                    if (boundPkgs[titlePref] == pkg) return@runCatching
                    val imageView = findFieldByType(titlePref.javaClass, ImageView::class.java)
                        ?.get(titlePref) as? ImageView ?: return@runCatching
                    imageView.setOnClickListener { view ->
                        runCatching {
                            val context = view.context
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                                context.startActivity(it)
                            }
                        }.onFailure { XposedLog.e(TAG, "launch $pkg failed: ${it.message}") }
                    }
                    boundPkgs[titlePref] = pkg
                    XposedLog.d(TAG, "click listener set for pkg=$pkg")
                }.onFailure { XposedLog.e(TAG, "onBindViewHolder after failed: ${it.message}") }
            } ?: XposedLog.e(TAG, "onBindViewHolder method not found")
        }.onFailure { XposedLog.e(TAG, "hook onBindViewHolder failed: ${it.message}") }
    }

    /** 沿类与父类链查找第一个类型匹配的字段（含子类型），并置为可访问。 */
    private fun findFieldByType(cls: Class<*>, type: Class<*>): Field? {
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (type.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }
}
