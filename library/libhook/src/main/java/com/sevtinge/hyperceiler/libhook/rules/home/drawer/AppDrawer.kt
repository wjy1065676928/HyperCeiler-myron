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
*/
package com.sevtinge.hyperceiler.libhook.rules.home.drawer

import android.view.View
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldAs

object AppDrawer : BaseHook() {

    // ---- 类名常量（7.0.0 ENH 反编译确认）----
    private const val CLS_CATEGORY_TITLE_ADAPTER =
        "com.miui.home.launcher.allapps.category.CategoryTitleAdapter"
    private const val CLS_CATEGORY_LIST_CONTAINER =
        "com.miui.home.launcher.allapps.category.AllAppsCategoryListContainer"
    private const val CLS_ALL_APPS_GRID_ADAPTER =
        "com.miui.home.launcher.allapps.AllAppsGridAdapter"
    private const val CLS_ALL_APPS_GRID_VIEW_HOLDER =
        "com.miui.home.launcher.allapps.AllAppsGridAdapter\$ViewHolder"

    /** 抽屉编辑入口的 itemViewType（6.9/7.0 均为 64，0x40）。 */
    private const val EDIT_ENTRY_ITEM_VIEW_TYPE = 64

    private var settingContainerId = 0

    override fun init() {

        if (PrefsBridge.getBoolean("home_drawer_all")) {
            hookRemoveAllTab()
        }

        if (PrefsBridge.getBoolean("home_drawer_editor")) {
            hookHideSettingButton()
            hookHideEditEntry()
        }
    }

    // ---- 移除「全部」分页标签 ----

    /**
     * CategoryTitleAdapter.getTitleView(Context, int) 返回 SimplePagerTitleView（View 子类）。
     * index==0 即「全部」标签，隐藏并收窄到 0 宽。
     */
    private fun hookRemoveAllTab() {
        loadClassOrNull(CLS_CATEGORY_TITLE_ADAPTER)?.findMethod {
            name("getTitleView")
        }?.createAfterHook {
            val index = it.args[1] as? Int ?: return@createAfterHook
            if (index != 0) return@createAfterHook
            val view = it.result as? View ?: return@createAfterHook
            view.visibility = View.GONE
            // layoutParams 可能为 null（新创建未布局），判空避免 NPE
            view.layoutParams?.let { lp ->
                lp.width = 0
                view.layoutParams = lp
            }
        } ?: XposedLog.w(TAG, "CategoryTitleAdapter.getTitleView not found")
    }

    // ---- 隐藏右上角设置小齿轮 ----

    /**
     * AllAppsCategoryListContainer.onFinishInflate 时隐藏 all_apps_setting_container。
     * 布局 all_apps_category_tab.xml 反编译确认该 id 存在。
     */
    private fun hookHideSettingButton() {
        loadClassOrNull(CLS_CATEGORY_LIST_CONTAINER)?.findMethod {
            name("onFinishInflate")
        }?.createAfterHook {
            try {
                val root = it.thisObject as? View ?: return@createAfterHook
                // 缓存资源 id，避免每次 getIdentifier 反射查资源
                if (settingContainerId == 0) {
                    settingContainerId = root.resources.getIdentifier(
                        "all_apps_setting_container", "id", "com.miui.home")
                }
                if (settingContainerId != 0) {
                    root.findViewById<View>(settingContainerId)?.visibility = View.GONE
                }
            } catch (e: Exception) {
                XposedLog.w(TAG, "hide setting button failed: ${e.message}")
            }
        } ?: XposedLog.w(TAG, "AllAppsCategoryListContainer.onFinishInflate not found")
    }

    // ---- 隐藏抽屉编辑入口 ----

    /**
     * AllAppsGridAdapter.onBindViewHolder(ViewHolder, int)：
     * 该类有两个 onBindViewHolder 重载（bridge synthetic + 真实），
     * 必须用参数类型精确匹配真实方法，否则 hook 到 bridge 不生效。
     * itemViewType==64 时隐藏 itemView。
     */
    private fun hookHideEditEntry() {
        val viewHolderClass = loadClassOrNull(CLS_ALL_APPS_GRID_VIEW_HOLDER) ?: run {
            XposedLog.w(TAG, "AllAppsGridAdapter\$ViewHolder not found")
            return
        }
        loadClassOrNull(CLS_ALL_APPS_GRID_ADAPTER)?.findMethod {
            name("onBindViewHolder")
            parameterTypes(viewHolderClass, Int::class.javaPrimitiveType!!)
        }?.createAfterHook {
            if ((it.args[0]?.callMethod("getItemViewType") as? Int) == EDIT_ENTRY_ITEM_VIEW_TYPE) {
                it.args[0]
                    ?.getObjectFieldAs<View>("itemView")
                    ?.visibility = View.INVISIBLE
            }
        } ?: XposedLog.w(TAG, "AllAppsGridAdapter.onBindViewHolder not found")
    }
}
