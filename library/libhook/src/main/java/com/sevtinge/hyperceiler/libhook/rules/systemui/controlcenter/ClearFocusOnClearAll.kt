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
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectField
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook

/**
 * 通知栏「全部清除」时，一并清除所有焦点通知。
 *
 * OS4 反编译结论：
 * - 「全部清除」有两条路径，且都收口在同一个方法
 *   NotificationStackScrollLayout.onClearAllAnimationsEnd(int selectedRows, List viewsToRemove)：
 *     selectedRows == 0 → NotifCollection.dismissAllNotifications(userId)
 *     selectedRows != 0 → NotifCollection.dismissNotifications(List, false)
 *   只 hook dismissAllNotifications 会漏掉 selectedRows != 0 的那条路径（曾实测无日志），
 *   因此主 hook 放在收口点 onClearAllAnimationsEnd，dismissAllNotifications 仅作兜底。
 * - FocusCoordinator 维护三个焦点通知集合（均为 ArrayMap<通知key, NotificationEntry>）：
 *   mFocusNotifications / mStatusBarFocusNotifications / mAodFocusNotifications。
 * - 每个 NotificationEntry 的 mSbn（ExpandedNotification）携带 packageName/tag/id。
 *
 * 日志级别约定：正常流程（初始化、命中、清理条数）用 XposedLog.d，
 * 仅失败与异常路径用 XposedLog.e。HyperCeiler 日志级别默认 LEVEL_ERROR_ONLY，
 * 该级别下 d/w/i 不输出——因此正常使用时本模块保持静默，只有出问题才看到 e。
 */
object ClearFocusOnClearAll : BaseHook() {

    private const val TAG = "ClearFocusOnClearAll"
    private const val CLS_COORDINATOR =
        "com.android.systemui.statusbar.notification.focus.FocusCoordinator"
    private const val CLS_NOTIF_COLLECTION =
        "com.android.systemui.statusbar.notification.collection.NotifCollection"
    private const val CLS_NSSL =
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
    private const val CLS_ENTRY =
        "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    private const val CLS_NOTIF_LISTENER =
        "com.android.systemui.statusbar.notification.MiuiNotificationListener"

    private const val KEY_ENABLED = "system_ui_clear_focus_on_clear_all"
    private const val KEY_SCOPE = "system_ui_clear_focus_scope"
    private const val KEY_APP_LIST = "system_ui_clear_focus_app_list"

    /** 清除范围：全部应用 / 仅选中的应用 / 除选中的应用外全部。 */
    private const val SCOPE_ALL = "all"
    private const val SCOPE_INCLUDE = "include"
    private const val SCOPE_EXCLUDE = "exclude"

    /** 主 hook 与兜底 hook 可能在同一次清除里先后触发，用时间窗去重。 */
    private const val DEDUP_WINDOW_MS = 800L

    private val FOCUS_MAP_FIELDS = listOf(
        "mFocusNotifications",
        "mStatusBarFocusNotifications",
        "mAodFocusNotifications"
    )

    @Volatile
    private var sCoordinator: Any? = null

    @Volatile
    private var sEntryClass: Class<*>? = null

    @Volatile
    private var sLastClearAt = 0L

    /** NotificationListenerService 的 listener 代理（INotificationListener$Stub$Proxy）。 */
    @Volatile
    private var sListenerWrapper: Any? = null

    override fun init() {
        XposedLog.d(TAG, "init() called, enabled=${PrefsBridge.getBoolean(KEY_ENABLED)}")

        // 缓存 FocusCoordinator 实例：构造器 + attach 双保险
        val coordinatorClass = loadClassOrNull(CLS_COORDINATOR)
        if (coordinatorClass == null) {
            XposedLog.e(TAG, "FocusCoordinator class not found")
        } else {
            runCatching {
                hookAllConstructors(coordinatorClass, object : IMethodHook {
                    override fun after(param: HookParam) {
                        sCoordinator = param.thisObject
                        XposedLog.d(TAG, "FocusCoordinator cached via <init>: ${param.thisObject}")
                    }
                })
            }.onFailure { XposedLog.e(TAG, "hook FocusCoordinator <init> failed: ${it.message}") }

            runCatching {
                coordinatorClass.findMethod { name("attach") }?.createAfterHook {
                    sCoordinator = it.thisObject
                    XposedLog.d(TAG, "FocusCoordinator cached via attach: ${it.thisObject}")
                }
            }.onFailure { XposedLog.e(TAG, "hook FocusCoordinator.attach failed: ${it.message}") }
        }

        sEntryClass = loadClassOrNull(CLS_ENTRY)
        XposedLog.d(TAG, "NotificationEntry class = $sEntryClass")

        // 缓存 listener 代理：SystemUI 的 MiuiNotificationListener 是 NotificationListenerService 子类，
        // onListenerConnected() 被调用时 mWrapper 已就绪，是拿 INotificationListener 代理的最佳时机。
        val listenerCls = loadClassOrNull(CLS_NOTIF_LISTENER)
        if (listenerCls == null) {
            XposedLog.e(TAG, "MiuiNotificationListener class not found")
        } else {
            runCatching {
                listenerCls.findMethod { name("onListenerConnected") }?.createAfterHook {
                    cacheListenerWrapper(it.thisObject)
                } ?: XposedLog.e(TAG, "onListenerConnected method not found")
            }.onFailure { XposedLog.e(TAG, "hook onListenerConnected failed: ${it.message}") }
        }

        // 主 hook：「全部清除」两条路径的共同收口点
        val nssl = loadClassOrNull(CLS_NSSL)
        if (nssl == null) {
            XposedLog.e(TAG, "NotificationStackScrollLayout class not found")
        } else {
            runCatching {
                nssl.findMethod { name("onClearAllAnimationsEnd") }?.createAfterHook { param ->
                    val args = param.args
                    val selectedRows = args.getOrNull(0)
                    val viewsSize = (args.getOrNull(1) as? Collection<*>)?.size
                    onClearAllTriggered("onClearAllAnimationsEnd(selectedRows=$selectedRows, views=$viewsSize)")
                } ?: XposedLog.e(TAG, "onClearAllAnimationsEnd method not found")
            }.onFailure { XposedLog.e(TAG, "hook onClearAllAnimationsEnd failed: ${it.message}") }
        }

        // 兜底 hook：selectedRows == 0 时收口点会再走这里
        val notifCollection = loadClassOrNull(CLS_NOTIF_COLLECTION)
        if (notifCollection == null) {
            XposedLog.e(TAG, "NotifCollection class not found")
            return
        }
        runCatching {
            notifCollection.findMethod { name("dismissAllNotifications") }?.createAfterHook {
                onClearAllTriggered("dismissAllNotifications")
            } ?: XposedLog.e(TAG, "dismissAllNotifications method not found")
        }.onFailure { XposedLog.e(TAG, "hook dismissAllNotifications failed: ${it.message}") }
    }

    /** 统一入口：开关判断 + 去重后清理焦点通知。 */
    private fun onClearAllTriggered(from: String) {
        val enabled = PrefsBridge.getBoolean(KEY_ENABLED)
        XposedLog.d(TAG, "$from triggered, enabled=$enabled")
        if (!enabled) return

        val now = SystemClock.elapsedRealtime()
        if (now - sLastClearAt < DEDUP_WINDOW_MS) {
            XposedLog.d(TAG, "$from skipped (dedup)")
            return
        }
        sLastClearAt = now
        clearAllFocusNotifications()
    }

    /**
     * 判断某包是否落在本次清除范围内。
     * all     → 全部应用
     * include → 仅选中的应用
     * exclude → 除选中的应用外全部
     */
    private fun inScope(pkg: String, scope: String, selected: Set<String>): Boolean = when (scope) {
        SCOPE_INCLUDE -> selected.contains(pkg)
        SCOPE_EXCLUDE -> !selected.contains(pkg)
        else -> true
    }

    /** 遍历三个焦点集合，收集 pkg/tag/id 后统一 cancel。 */
    private fun clearAllFocusNotifications() {
        val coordinator = sCoordinator ?: run {
            XposedLog.e(TAG, "FocusCoordinator not cached (attach/<init> not hooked yet)")
            return
        }
        val entryClass = sEntryClass ?: run {
            XposedLog.e(TAG, "NotificationEntry class not found")
            return
        }
        val context = runCatching { coordinator.getObjectField("mContext") as Context }
            .getOrNull() ?: run {
                XposedLog.e(TAG, "FocusCoordinator.mContext is null")
                return
            }
        val nm = context.getSystemService(NotificationManager::class.java) ?: run {
            XposedLog.e(TAG, "NotificationManager is null")
            return
        }

        // 读取清除范围与选中的应用列表。
        val scope = PrefsBridge.getString(KEY_SCOPE, SCOPE_ALL)
        val selected = PrefsBridge.getStringSet(KEY_APP_LIST) ?: emptySet()
        XposedLog.d(TAG, "scope=$scope, selectedApps=${selected.size}")

        // 先收集，迭代结束后再 cancel：避免 cancel 回调在主线程改动 map 触发并发修改。
        // 用 LinkedHashSet 去重：三个字段实测是三个不同的 ArrayMap（identity 各不同），
        // 但 key 有重叠，不去重会重复取消同一批通知。
        val seenKeys = LinkedHashSet<String>()
        val targets = ArrayList<Target>()
        for (fieldName in FOCUS_MAP_FIELDS) {
            val mapObj = runCatching { coordinator.getObjectField(fieldName) }.getOrNull()
            if (mapObj !is java.util.Map<*, *>) {
                XposedLog.d(TAG, "field $fieldName is not a Map: ${mapObj?.javaClass?.name}")
                continue
            }
            val mapSize = mapObj.entrySet().size
            XposedLog.d(
                TAG,
                "field $fieldName size=$mapSize identity=${System.identityHashCode(mapObj)}"
            )
            val iter = mapObj.entrySet().iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val value = entry.value
                if (value == null || !entryClass.isInstance(value)) {
                    XposedLog.d(TAG, "  value not NotificationEntry: ${value?.javaClass?.name}")
                    continue
                }
                val mapKey = entry.key?.toString()
                if (mapKey != null && !seenKeys.add(mapKey)) {
                    XposedLog.d(TAG, "  dup entry already collected: $mapKey")
                    continue
                }
                val sbn = runCatching { value.getObjectField("mSbn") }.getOrNull()
                if (sbn == null) {
                    XposedLog.d(TAG, "  mSbn is null for $mapKey")
                    continue
                }
                // 注意：不能用 Class.forName(CLS_SBN)——它走模块自身 classloader，
                // 加载不到宿主 SystemUI 的类，会抛 ClassNotFoundException（message 恰好是类名）。
                // 直接用 mSbn 运行时的真实类。
                val sbnClass = sbn.javaClass
                val key = invokeString(sbnClass, sbn, "getKey")
                // 只清可见的普通焦点通知：常驻（音乐/下载等）与前台服务通知保持不动。
                // 方法缺失时按 false 处理（继续清理），避免整条被丢弃。
                val ongoing = invokeBool(sbnClass, sbn, "isOngoing")
                val fgs = invokeBool(sbnClass, sbn, "isForegroundService")
                if (ongoing || fgs) {
                    XposedLog.d(TAG, "  skip ongoing=$ongoing fgs=$fgs: $key")
                    continue
                }
                val pkg = invokeString(sbnClass, sbn, "getPackageName")
                if (pkg == null) {
                    XposedLog.d(TAG, "  getPackageName failed: $key")
                    continue
                }
                if (!inScope(pkg, scope, selected)) {
                    XposedLog.d(TAG, "  out of scope (scope=$scope): $pkg")
                    continue
                }
                val tag = invokeString(sbnClass, sbn, "getTag")
                val id = (runCatching { sbnClass.getMethod("getId").invoke(sbn) as? Int }.getOrNull())
                if (id == null) {
                    XposedLog.d(TAG, "  getId failed: $key")
                    continue
                }
                val userId = invokeUserId(sbnClass, sbn)
                XposedLog.d(TAG, "  target pkg=$pkg tag=$tag id=$id userId=$userId key=$key")
                targets.add(Target(pkg, tag, id, userId, key))
            }
        }

        // 关键：Android 17 的 NotificationManager 只暴露 cancel(int) 与 cancel(String,int)，
        // 二者都只作用于调用方自己的通知（跨包调用实测直接 NoSuchMethodException）。
        // 而 INotificationManager.cancelNotificationWithTag 对跨包通知会被服务端
        // checkCallerIsSystemOrSameApp 拒绝（实测只有 pkg=com.android.systemui 自己能成功，1/3）。
        // 因此优先走 listener 通道：
        //   INotificationManager.cancelNotificationsFromListener(INotificationListener, String[] keys)
        // SystemUI 自身就是 NotificationListenerService，有权取消任意包的通知。
        val inm = getNotificationManagerService(nm) ?: run {
            XposedLog.e(TAG, "INotificationManager not available, abort")
            return
        }
        val keys = targets.mapNotNull { it.key }
        val viaListener = cancelViaListener(inm, keys)
        if (viaListener) {
            XposedLog.d(TAG, "Cleared ${keys.size}/${targets.size} focus notifications (listener)")
            return
        }

        // 回退：cancelNotificationWithTag（仅同包有效）
        val cancelMethod = runCatching {
            inm.javaClass.getMethod(
                "cancelNotificationWithTag",
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull() ?: run {
            XposedLog.e(TAG, "INotificationManager.cancelNotificationWithTag not found")
            return
        }

        var cleared = 0
        for (t in targets) {
            // opPkg 传 pkg 自身，表示"代表该包"执行取消。
            val result = runCatching { cancelMethod.invoke(inm, t.pkg, t.pkg, t.tag, t.id, t.userId) }
            if (result.isSuccess) {
                cleared++
            } else {
                XposedLog.e(TAG, "  cancel failed ${t.pkg} id=${t.id}: ${describe(result)}")
            }
        }
        XposedLog.d(TAG, "Cleared $cleared/${targets.size} focus notifications (tag)")
    }

    /**
     * 走 NotificationListener 通道批量取消。
     * SystemUI 的 MiuiNotificationListener 是 NotificationListenerService 子类，
     * 其实例字段 mWrapper（INotificationListener$Stub$Proxy）即服务端需要的 listener 代理。
     */
    private fun cancelViaListener(inm: Any, keys: List<String>): Boolean {
        if (keys.isEmpty()) return false
        val listener = sListenerWrapper ?: run {
            XposedLog.e(TAG, "INotificationListener wrapper not cached, fallback to cancelNotificationWithTag")
            return false
        }
        // 同样别用 Class.forName：走模块 classloader 加载不到宿主类。
        // listener 代理已实现 INotificationListener，从其接口取即可。
        val listenerInterface = listener.javaClass.interfaces
            .firstOrNull { it.name == "android.service.notification.INotificationListener" }
            ?: loadClassOrNull("android.service.notification.INotificationListener")
        if (listenerInterface == null) {
            XposedLog.e(TAG, "INotificationListener interface not found")
            return false
        }
        val method = runCatching {
            inm.javaClass.getMethod(
                "cancelNotificationsFromListener",
                listenerInterface,
                Array<String>::class.java
            )
        }.getOrNull() ?: run {
            XposedLog.e(TAG, "cancelNotificationsFromListener not found")
            return false
        }
        val result = runCatching { method.invoke(inm, listener, keys.toTypedArray()) }
        if (result.isFailure) {
            XposedLog.e(TAG, "cancelNotificationsFromListener failed: ${describe(result)}")
            return false
        }
        return true
    }

    /**
     * 从 NotificationListenerService 实例上取 mWrapper（INotificationListener 代理）。
     * 该字段定义在 NotificationListenerService 中，需沿父类链查找；且是实例字段而非 static。
     */
    private fun cacheListenerWrapper(service: Any?) {
        if (service == null) return
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            for (name in listOf("mWrapper", "mNotificationListener")) {
                val field = runCatching { cls.getDeclaredField(name) }.getOrNull() ?: continue
                field.isAccessible = true
                val value = runCatching { field.get(service) }.getOrNull() ?: continue
                sListenerWrapper = value
                XposedLog.d(
                    TAG,
                    "listener wrapper cached via ${cls.simpleName}.$name = ${value.javaClass.name}"
                )
                return
            }
            cls = cls.superclass
        }
        XposedLog.e(TAG, "mWrapper not found on ${service.javaClass.name}")
    }

    /** 把反射异常展开成可读信息（含 cause）。 */
    private fun describe(result: Result<*>): String {
        var t = result.exceptionOrNull()
        val sb = StringBuilder()
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) sb.append(" <- ")
            sb.append(t.javaClass.simpleName).append(": ").append(t.message)
            t = t.cause
            depth++
        }
        return sb.toString()
    }

    /** 从 NotificationManager 取 INotificationManager：实例字段 mService 优先，其次静态 sService。 */
    private fun getNotificationManagerService(nm: Any): Any? {
        val cls = nm.javaClass
        for (name in listOf("mService", "sService")) {
            val field = runCatching { cls.getDeclaredField(name) }.getOrNull() ?: continue
            field.isAccessible = true
            val isStatic = java.lang.reflect.Modifier.isStatic(field.modifiers)
            val value = runCatching { field.get(if (isStatic) null else nm) }.getOrNull() ?: continue
            XposedLog.d(TAG, "INotificationManager via $name = ${value.javaClass.name}")
            return value
        }
        return null
    }

    /** 取 StatusBarNotification 所属 userId，失败回落 0（当前用户）。 */
    private fun invokeUserId(sbnClass: Class<*>, sbn: Any): Int = runCatching {
        val user = sbnClass.getMethod("getUser").invoke(sbn)
        user?.javaClass?.getMethod("getIdentifier")?.invoke(user) as? Int
    }.getOrNull() ?: 0

    /** 一条待清理的焦点通知。 */
    private data class Target(
        val pkg: String,
        val tag: String?,
        val id: Int,
        val userId: Int,
        val key: String?
    )

    /** 反射调用无参方法并取 String，失败返回 null。 */
    private fun invokeString(clazz: Class<*>, target: Any, name: String): String? =
        runCatching { clazz.getMethod(name).invoke(target) as? String }.getOrNull()

    /** 反射调用无参方法并取 boolean，失败（含方法不存在）按 false 处理。 */
    private fun invokeBool(clazz: Class<*>, target: Any, name: String): Boolean =
        runCatching { clazz.getMethod(name).invoke(target) as? Boolean ?: false }.getOrDefault(false)
}
