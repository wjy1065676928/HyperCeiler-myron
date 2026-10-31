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
package com.sevtinge.hyperceiler.libhook.rules.migameservice

import android.util.Log
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.common.utils.api.ProjectApi
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.setBooleanField

/**
 * 修复 AI 游戏服务（venus.gameai）与 Joyose 连接状态的竞态
 *
 * 原始逻辑（BaseGameAdapter）：
 * ```java
 * public void connectSuccess() {
 *     if (isReady()) {                     // 连接回调早于 ready 时，这里被跳过
 *         joyoseConnectState = true;
 *         updateRunningState();
 *     }
 * }
 *
 * private void updateRunningState() {
 *     if (isReady()) {
 *         if (runTarget && !runState && hasSpPermission && joyoseConnectState) run();
 *     }
 * }
 * ```
 *
 * connectSuccess 只会回调一次。一旦它在 readyState 变为 true 之前触发，
 * joyoseConnectState 就永远停在 false；而 changeSpPermission() 这条路径
 * 虽然会重新调用 updateRunningState()，却没有重置 joyoseConnectState，
 * 于是 runState 永远起不来。
 *
 * 表现：服务已加载、截图引擎每秒正常工作，但 onScreenChange() 首句
 * `if (!isRunning()) return;` 把每一帧丢弃，自动拾取 / 自动奔跑全部失效。
 *
 * 修复：在 updateRunningState() 执行前把 joyoseConnectState 视为已就绪。
 * YuanshenGameAdapter 在多处回调中都会调用 changeSpPermission()
 * -> updateRunningState()，因此这一处 hook 即可覆盖整个生命周期。
 */
object GameAiJoyoseConnectFix : BaseHook() {

    private const val ADAPTER = "com.xiaomi.venus.gameaibaselib.base.game.BaseGameAdapter"
    private const val YUANSHEN_ADAPTER = "com.xiaomi.venus.gameaiyuanshenlib.YuanshenGameAdapter"
    private const val JOYOSE_CONNECT_STATE = "joyoseConnectState"
    private const val UPDATE_RUNNING_STATE = "updateRunningState"

    // 拾取识别间隔（原值 1000ms），由用户在设置里调节。
    // 单次推理仅 3~4ms，压到 200ms 的 CPU 代价可忽略。
    private const val PREF_PICK_INTERVAL = "migameservice_ai_pick_interval"
    private const val DEFAULT_PICK_INTERVAL = 250
    private const val AUTO_PICK_ADAPTER =
        "com.xiaomi.venus.gameaiyuanshenlib.gamestepadapter.YuanshenAutoPickStepAdapter"
    private const val MODEL_CONTROL =
        "com.xiaomi.venus.gameaiyuanshensolib.YuanshenAiModelStaticControl"
    private const val BITMAP_DATA = "com.xiaomi.venus.gameaibasesolib.bean.BitmapData"

    // 模型白名单中与原生 2608*1200 最接近的一项（宽高比 2.166 vs 2.173）
    private const val MODEL_W = 2772
    private const val MODEL_H = 1280

    // 调试用：AI 服务的日志默认只写文件（LogUtils 第三个参数 true），logcat 不可见。
    private const val LOG_UTILS = "com.xiaomi.venus.gameaibaselib.utils.LogUtils"
    private const val DEBUG_TAG = "VGameAI"

    override fun init() {
        // 1. 分辨率白名单
        //    YuanshenGameAdapter 里是一组硬编码值：
        //      2400*1080 / 2670*1200 / 2712*1220 / 2772*1280 / 3008*1880 / 3200*1440
        //    不在列表里的机型（例如 25102RK69C 的 2608*1200）会在
        //    MScreenShortCallback.onScreenChange() 首部被判为“不支持的分辨率”，
        //    直接 return，导致后续识别与拾取全部不执行。
        findClass(YUANSHEN_ADAPTER)
            .findMethod { name("checkSupportScreenShortResolution") }
            .createHook { returnConstant(true) }

        // 2. 状态刷新兜底：进入判定前把 Joyose 连接标记为已就绪
        findClass(ADAPTER)
            .findMethod { name(UPDATE_RUNNING_STATE) }
            .createHook {
                before {
                    it.thisObject.setBooleanField(JOYOSE_CONNECT_STATE, true)
                }
            }

        // 3. 分辨率适配：只缩放喂给模型的位图，不改显示分辨率。
        //
        //    模型（libgenshin.so）内置分辨率白名单，只认
        //      2400*1080 / 2670*1200 / 2712*1220 / 2772*1280 / 3008*1880 / 3200*1440
        //    原生 2608*1200 不在其中，会直接返回 "Don't have this resolution!"
        //    （耗时 1ms，不做任何推理），导致自动拾取 / 自动奔跑完全不可用。
        //
        //    改动显示分辨率（setForcedDisplaySize）虽然可行，但会带来一连串副作用：
        //    手势导航判定区被缩放（快速上滑返回桌面失效）、画面整体重采样、
        //    退出残留等。因此改为在调用模型前把位图缩放到 2772*1280：
        //      · 显示与手势保持原生 2608*1200
        //      · ColorPickManager 的采样坐标按原生分辨率查表（表里本来就有 2608*1200）
        //      · 模型只关心画面内容，缩放不影响 uiState 判定
        findClass(MODEL_CONTROL)
            .findMethod { name("identifyYuanshenMainScene") }
            .createHook {
                before {
                    it.args[0] = scaleToSupportedResolution(it.args[0]) ?: it.args[0]
                }
            }

        // 3.5 提高拾取识别频率（间隔可在设置中调节）
        //     YuanshenAutoPickStepAdapter.getScreenShortDuration() 硬编码 return 1000L，
        //     意味着拾取控制器每秒才处理一帧；模型单次推理仅 3~4ms。
        findClass(AUTO_PICK_ADAPTER)
            .findMethod { name("getScreenShortDuration") }
            .createHook {
                after {
                    it.result = PrefsBridge.getInt(
                        PREF_PICK_INTERVAL,
                        DEFAULT_PICK_INTERVAL
                    ).toLong()
                }
            }

        // 5. 调试：把 LogUtils 的“只写文件”日志镜像到 logcat（tag = VGameAI）。
        //    仅 canary / beta / debug 构建启用；release 构建下整段不执行
        //    （BUILD_TYPE 是常量，R8 会直接折叠掉这个分支）。
        if (!ProjectApi.isRelease()) {
            listOf("d", "i", "e").forEach { level ->
                findClass(LOG_UTILS)
                    .findMethod {
                        name(level)
                        paramCount(3)
                    }
                    .createHook {
                        after {
                            runCatching {
                                val tag = it.args[0] as? String
                                val msg = it.args[1] as? String
                                if (tag != null && msg != null) {
                                    Log.d(DEBUG_TAG, "$level/$tag: $msg")
                                }
                            }
                        }
                    }
            }
        }
    }

    /**
     * 把 BitmapData 内的位图缩放到模型支持的尺寸，返回新的 BitmapData。
     * 已是目标尺寸、或缩放失败时返回 null（调用方沿用原参数）。
     */
    private fun scaleToSupportedResolution(arg: Any?): Any? = runCatching {
        val bd = arg ?: return@runCatching null
        val src = bd.javaClass.getMethod("getBitmap").invoke(bd) as? android.graphics.Bitmap
            ?: return@runCatching null
        if (src.width == MODEL_W && src.height == MODEL_H) return@runCatching null
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, MODEL_W, MODEL_H, true)
        val cls = Class.forName(BITMAP_DATA, false, bd.javaClass.classLoader)
        cls.getConstructor(android.graphics.Bitmap::class.java).newInstance(scaled)
    }.getOrNull()
}
