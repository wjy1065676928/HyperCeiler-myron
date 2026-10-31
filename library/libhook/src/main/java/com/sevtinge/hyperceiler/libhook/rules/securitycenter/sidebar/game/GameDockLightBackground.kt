/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.securitycenter.sidebar.game

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 浅色模式下的游戏侧边栏：改成「全部应用」抽屉那种毛玻璃浅色。
 *
 * 关键点：
 * 1. 给 dock 窗口开背景模糊（FLAG_BLUR_BEHIND + blurBehindRadius），否则背后是实心游戏；
 * 2. 底色必须半透明（GLASS），不透明就看不到模糊；
 * 3. 深色来源：RegionSamplingImageView 采样图、头部 BitmapDrawable、白色文字、7% 白卡片。
 */
object GameDockLightBackground : BaseHook() {

    private const val TAG = "HyperCeiler.GameDock"
    private const val GLASS = 0xFFF7F7F7.toInt()   // 浅灰实底（窗口级模糊会全屏，暂不启用模糊）
    private const val CARD = 0x0F000000      // 6% 黑，浅一点的卡片底，避免整体发灰
    private const val TEXT_DARK = 0xDE000000.toInt()
    private const val TEXT_DARK2 = 0x99000000.toInt()
    private const val BLUR_RADIUS = 70

    // 只处理头部深色位图；sidebar_background 是折叠态抓手，必须保持原样
    private val samplerIds = setOf("iv_game_toolbox_title_dual_bg")

    // MIUI 模糊 SDK 反射结果
    private var blurDrawInfo: Any? = null
    private var blurDrawMethod: Method? = null
    private var blurPanel: View? = null

    // 判定"是否属于游戏侧边栏"的唯一依据：侧边栏窗口的根布局。
    //
    // res/layout/sidebar_panel_container.xml：
    //   <FrameLayout android:id="@id/sidebar_panel_container">
    //       <RegionSamplingImageView android:id="@id/sidebar_background" />   折叠态抓手
    //       <TurboLayout             android:id="@id/sidebar_panel" />         侧边栏主体
    //       <DockShortCutMenu        android:id="@id/sidebar_shortcut_menu" /> 快捷菜单
    //   </FrameLayout>
    //
    // game_toolbox_main_container / iv_game_toolbox_title_dual_bg /
    // game_toolbox_second_container 等都在 game_turbo_main_view.xml 中，
    // 被 inflate 进 sidebar_panel，因此全部位于 sidebar_panel_container 之内。
    // 只认这一个 id，可保证改动严格限制在侧边栏，不波及其它页面。
    private val toolboxIds = setOf("sidebar_panel_container")

    override fun init() {
        val night = (Resources.getSystem().configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Log.i(TAG, "init pid=${android.os.Process.myPid()} night=$night")
        // 不在 init 时直接 return：深浅色要在每次回调里动态判断，否则切换模式后不生效
        // 窗口级模糊会全屏糊，改用 MIUI BlurManager 做面板级模糊
        hookImageSetters()
        hookTextColor()
        hookRootView()
        hookPanelDraw()
    }

    private fun isNightCtx(v: View): Boolean =
        (v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 是否属于游戏侧边栏。
     *
     * 自身或任一祖先（最多 30 层）命中 [toolboxIds] 即判定为侧边栏内部。
     * 侧边栏与安全中心主界面共用一个窗口，无法用 rootView 区分，只能按 id 判断。
     *
     * 注意：setTextColor 可能在视图尚未 attach 时调用，此时 parent 链不完整，
     * 判定为 false，具体染色由 onAttachedToWindow 那条路径兜底。
     */
    private fun inToolbox(v: View): Boolean {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 30) {
            if (idName(cur) in toolboxIds) return true
            cur = cur.parent as? View
            depth++
        }
        return false
    }

    /** 文字颜色在任何时候被设置都拦截（解决绑定晚于 attach 的问题） */
    private fun hookTextColor() {
        try {
            findAndHookMethod(
                "android.widget.TextView", "setTextColor",
                Int::class.javaPrimitiveType,
                object : IMethodHook {
                    override fun before(param: HookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        if (isNightCtx(tv)) return
                        if (!inToolbox(tv)) return
                        val c = param.args[0] as? Int ?: return
                        val a = Color.alpha(c)
                        val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                        if (lum > 120 && a > 40) {
                            param.args[0] = if (a > 150) TEXT_DARK else TEXT_DARK2
                        }
                    }
                }
            )
            Log.i(TAG, "setTextColor hooked")
        } catch (t: Throwable) {
            Log.w(TAG, "setTextColor hook fail: $t")
        }

        // XML 里 android:textColor 走 ColorStateList 重载，也要拦
        try {
            findAndHookMethod(
                "android.widget.TextView", "setTextColor",
                android.content.res.ColorStateList::class.java,
                object : IMethodHook {
                    override fun before(param: HookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        if (isNightCtx(tv)) return
                        if (!inToolbox(tv)) return
                        val cs = param.args[0] as? android.content.res.ColorStateList ?: return
                        val c = try { cs.defaultColor } catch (t: Throwable) { return }
                        val a = Color.alpha(c)
                        val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                        if (lum > 120 && a > 40) {
                            param.args[0] = android.content.res.ColorStateList.valueOf(
                                if (a > 150) TEXT_DARK else TEXT_DARK2
                            )
                        }
                    }
                }
            )
            Log.i(TAG, "setTextColor(ColorStateList) hooked")
        } catch (t: Throwable) {
            Log.w(TAG, "setTextColor(CSL) hook fail: $t")
        }

        // 兜底：构造器/主题直接写入 mTextColor 的情况，在绘制时纠正
        try {
            findAndHookMethod(
                "android.widget.TextView", "onDraw", Canvas::class.java,
                object : IMethodHook {
                    override fun before(param: HookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        if (isNightCtx(tv)) return
                        if (!inToolbox(tv)) return
                        val c = tv.currentTextColor
                        val a = Color.alpha(c)
                        val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                        if (inSecondaryPage(tv)) {
                            // 二级页保持深色底，文字恢复白色
                            if (lum < 120) tv.setTextColor(0xFFFFFFFF.toInt())
                        } else if (lum > 120 && a > 40) {
                            tv.setTextColor(if (a > 150) TEXT_DARK else TEXT_DARK2)
                        }
                    }
                }
            )
            Log.i(TAG, "TextView.onDraw hooked")
        } catch (t: Throwable) {
            Log.w(TAG, "TextView.onDraw hook fail: $t")
        }
    }

    /** 给 dock 窗口开启背景模糊 */
    private fun hookWindowBlur() {
        try {
            findAndHookMethod(
                "android.view.WindowManagerImpl", "addView",
                View::class.java, ViewGroup.LayoutParams::class.java,
                object : IMethodHook {
                    override fun before(param: HookParam) {
                        val v = param.args[0] as? View ?: return
                        val pkg = try { v.context.packageName } catch (t: Throwable) { return }
                        if (pkg != "com.miui.securitycenter") return
                        val lp = param.args[1] as? WindowManager.LayoutParams ?: return
                        lp.flags = lp.flags or 0x00000004 // FLAG_BLUR_BEHIND
                        try {
                            lp.setBlurBehindRadius(BLUR_RADIUS)
                            Log.i(TAG, "blur behind radius=$BLUR_RADIUS")
                        } catch (t: Throwable) {
                            Log.w(TAG, "setBlurBehindRadius fail: $t")
                        }
                    }
                }
            )
            Log.i(TAG, "WindowManagerImpl.addView hooked")
        } catch (t: Throwable) {
            Log.w(TAG, "addView hook fail: $t")
        }
    }

    /** 采样图 / 头部位图：阻断设置，改半透明底；并把浅色矢量图标染深 */
    private fun hookImageSetters() {
        val imageHook = object : IMethodHook {
            override fun before(param: HookParam) {
                val v = param.thisObject as? View ?: return
                if (isNightCtx(v)) return
                if (idName(v) in samplerIds) {
                    v.setBackgroundColor(GLASS)
                    param.result = null
                }
            }
        }
        // 矢量图标（浅色线性图标）染深；位图（App 图标）不动
        val tintHook = object : IMethodHook {
            override fun after(param: HookParam) {
                val iv = param.thisObject as? ImageView ?: return
                if (isNightCtx(iv)) return
                if (!inToolbox(iv)) return
                val d = param.args[0] as? Drawable ?: return
                if (d is android.graphics.drawable.VectorDrawable) {
                    iv.setColorFilter(0xFF444444.toInt(), PorterDuff.Mode.SRC_IN)
                }
            }
        }
        try {
            findAndHookMethod("android.widget.ImageView", "setImageDrawable", Drawable::class.java, imageHook)
            findAndHookMethod("android.widget.ImageView", "setImageDrawable", Drawable::class.java, tintHook)
            findAndHookMethod("android.widget.ImageView", "setImageBitmap", Bitmap::class.java, imageHook)
            Log.i(TAG, "image setters hooked")
        } catch (t: Throwable) {
            Log.w(TAG, "image setters hook fail: $t")
        }

        // 兜底：矢量图标绘制时若还没染深，补一次
        try {
            findAndHookMethod(
                "android.widget.ImageView", "onDraw", Canvas::class.java,
                object : IMethodHook {
                    override fun before(param: HookParam) {
                        val iv = param.thisObject as? ImageView ?: return
                        if (isNightCtx(iv)) return
                        if (!inToolbox(iv)) return
                        if (inSecondaryPage(iv)) {
                            // 二级页保持深色底，图标要原色（白）
                            if (iv.colorFilter != null) iv.colorFilter = null
                            return
                        }
                        // 功能项图标按 id 统一染深（主面板和二级页共用同一套 item 布局）
                        if (idName(iv) == "function_icon") {
                            if (iv.colorFilter == null) {
                                iv.setColorFilter(0xFF333333.toInt(), PorterDuff.Mode.SRC_IN)
                            }
                            return
                        }
                        if (iv.colorFilter == null &&
                            iv.drawable is android.graphics.drawable.VectorDrawable
                        ) {
                            iv.setColorFilter(0xFF444444.toInt(), PorterDuff.Mode.SRC_IN)
                        }
                    }
                }
            )
            Log.i(TAG, "ImageView.onDraw hooked")
        } catch (t: Throwable) {
            Log.w(TAG, "ImageView.onDraw hook fail: $t")
        }
    }

    private fun hookRootView() {
        try {
            findAndHookMethod(
                "android.view.View", "onAttachedToWindow",
                object : IMethodHook {
                    override fun after(param: HookParam) {
                        val v = param.thisObject as? View ?: return
                        if (isNightCtx(v)) return
                        // 二级页图标是 GifImageView（GIF 忽略 setColorFilter），无法染深，
                        // 只能保持原深色底以保证可读性，整体跳过
                        if (inSecondaryPage(v)) return
                        val name = idName(v)
                        if (!inToolbox(v)) return
                        when (name) {
                            // 折叠态抓手（那条线）保持原样，不要动，否则会变成白色块
                            "game_toolbox_main_container" -> {
                                v.background = roundedCard(v, 16f)
                                v.elevation = 8f * v.resources.displayMetrics.density
                            }
                            "iv_game_toolbox_title_dual_bg" -> {
                                (v as? ImageView)?.setImageDrawable(null)
                                // 头部保持透明，让父容器的圆角白卡透出来（否则方形底色会盖住圆角）
                                v.setBackgroundColor(0x00000000)
                            }
                            // 性能表盘的不透明向量背景盖住了底层，改透明让颜色透上来
                            "performance_panel_view" -> v.setBackgroundColor(0x00000000)
                        }
                        // 二级页内容由 g1 自绘（白色图形/文字），反相图层会导致整块消失，已弃用
                        if (false && v.javaClass.name == "com.miui.gamebooster.windowmanager.newbox.g1") {
                            val cm = android.graphics.ColorMatrix(
                                floatArrayOf(
                                    -1f, 0f, 0f, 0f, 255f,
                                    0f, -1f, 0f, 0f, 255f,
                                    0f, 0f, -1f, 0f, 255f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            val p = Paint()
                            p.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                            v.setLayerType(View.LAYER_TYPE_HARDWARE, p)
                            Log.i(TAG, "invert layer -> g1")
                        }
                        // 文字亮色 -> 深色
                        if (v is TextView) {
                            val c = v.currentTextColor
                            val a = Color.alpha(c)
                            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                            when {
                                lum > 170 && a > 150 -> v.setTextColor(TEXT_DARK)
                                lum > 170 && a > 40 -> v.setTextColor(TEXT_DARK2)
                            }
                        }
                        // 左侧 dock 列（bg=null 透出游戏）-> 同款圆角白卡
                        if (v.javaClass.name == "com.miui.gamebooster.windowmanager.newbox.p0") {
                            v.background = roundedCard(v, 16f)
                        }
                        // 功能项图标染深
                        if (name == "function_icon" && v is ImageView) {
                            v.setColorFilter(0xFF333333.toInt(), PorterDuff.Mode.SRC_IN)
                        }
                        // 7% 白卡片 -> 可见浅灰，圆角沿用原始值，不要硬编码
                        val bg = v.background
                        if (bg is GradientDrawable && isFaintWhite(bg)) {
                            val nd = GradientDrawable()
                            nd.shape = GradientDrawable.RECTANGLE
                            val radii = try { bg.cornerRadii } catch (t: Throwable) { null }
                            val r = try { bg.cornerRadius } catch (t: Throwable) { 0f }
                            when {
                                radii != null -> nd.cornerRadii = radii
                                r > 0f -> nd.cornerRadius = r
                                else -> nd.cornerRadius = 12f * v.resources.displayMetrics.density
                            }
                            nd.setColor(CARD)
                            v.background = nd
                        }
                    }
                }
            )
            Log.i(TAG, "onAttachedToWindow hooked")
        } catch (t: Throwable) {
            Log.w(TAG, "onAttachedToWindow hook fail: $t")
        }
    }

    /** 只模糊面板那块：反射 setBlurRegions(隐藏 API) */
    private fun applyBlurRegion(v: View) {
        try {
            if (v.width <= 0 || v.height <= 0) return
            val root = v.rootView
            val sc = getSurfaceControl(root)
            if (sc == null) {
                Log.w(TAG, "no SurfaceControl")
                return
            }
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val region = floatArrayOf(
                loc[0].toFloat(), loc[1].toFloat(),
                (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat()
            )
            val t = android.view.SurfaceControl.Transaction()
            val m = t.javaClass.getDeclaredMethod(
                "setBlurRegions", android.view.SurfaceControl::class.java, Array<FloatArray>::class.java
            )
            m.isAccessible = true
            m.invoke(t, sc, arrayOf(region))
            t.apply()
            Log.i(TAG, "blur region ok: ${region.joinToString()}")
        } catch (t: Throwable) {
            Log.w(TAG, "applyBlurRegion fail: $t")
        }
    }

    private fun getSurfaceControl(root: View): android.view.SurfaceControl? {
        return try {
            val m1 = View::class.java.getDeclaredMethod("getViewRootImpl")
            m1.isAccessible = true
            val impl = m1.invoke(root) ?: return null
            val m2 = impl.javaClass.getDeclaredMethod("getSurfaceControl")
            m2.isAccessible = true
            m2.invoke(impl) as? android.view.SurfaceControl
        } catch (t: Throwable) {
            Log.w(TAG, "getSurfaceControl fail: $t")
            null
        }
    }

    /** 用 MIUI 模糊 SDK（BlurManager + BlurDrawInfo 动态代理）给面板做真模糊 */
    private fun setupBlur(v: View) {
        try {
            if (v.width <= 0 || v.height <= 0) {
                v.postDelayed({ setupBlur(v) }, 500)
                return
            }
            val cl = v.context.classLoader
            val drawInfoCls = cl.loadClass("com.miui.blur.sdk.backdrop.BlurDrawInfo")
            val managerCls = cl.loadClass("com.miui.blur.sdk.backdrop.BlurManager")
            val styleCls = cl.loadClass("com.miui.blur.sdk.backdrop.n")
            val defaultStyle = styleCls.getField("f").get(null)

            val proxy = Proxy.newProxyInstance(cl, arrayOf(drawInfoCls), InvocationHandler { _, method, args ->
                when (method.name) {
                    "getBlurViewRootImpl" -> viewRootImpl(v)
                    "getWidth" -> v.width
                    "getHeight" -> v.height
                    "getLocationOnScreen" -> {
                        v.getLocationOnScreen(args!![0] as IntArray); null
                    }
                    "getBlurOutline" -> {
                        (args!![0] as Outline).setRect(0, 0, v.width, v.height); null
                    }
                    "getBlurStyle" -> defaultStyle
                    "getRequestedSamplingPeriodNs" -> 20_000_000
                    "postInvalidateOnAnimation" -> {
                        v.postInvalidateOnAnimation(); null
                    }
                    else -> {
                        Log.w(TAG, "unhandled blur method: ${method.name} -> ${method.returnType.name}")
                        when (method.returnType) {
                            Int::class.javaPrimitiveType, Int::class.javaObjectType -> 0
                            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
                            Float::class.javaPrimitiveType, Float::class.javaObjectType -> 0f
                            else -> null
                        }
                    }
                }
            })

            val e = managerCls.getDeclaredMethod("e", Context::class.java, drawInfoCls)
            e.isAccessible = true
            Log.i(TAG, "invoking BlurManager.e ...")
            e.invoke(null, v.context, proxy)
            Log.i(TAG, "BlurManager.e ok")

            val b = managerCls.getDeclaredMethod("b", Canvas::class.java, drawInfoCls)
            b.isAccessible = true

            blurDrawInfo = proxy
            blurDrawMethod = b
            Log.i(TAG, "blur sdk registered ${v.width}x${v.height}")
        } catch (t: Throwable) {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth < 5) {
                Log.w(TAG, "setupBlur fail[$depth]: ${c.javaClass.name}: ${c.message}")
                c = c.cause
                depth++
            }
        }
    }

    private fun viewRootImpl(v: View): Any? = try {
        val m = View::class.java.getDeclaredMethod("getViewRootImpl")
        m.isAccessible = true
        m.invoke(v)
    } catch (t: Throwable) { null }

    private fun hookPanelDraw() {
        try {
            findAndHookMethod(
                "android.view.View", "draw", Canvas::class.java,
                object : IMethodHook {
                    override fun before(param: HookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v !== blurPanel) return
                        val info = blurDrawInfo ?: return
                        val m = blurDrawMethod ?: return
                        try {
                            m.invoke(null, param.args[0], info)
                        } catch (t: Throwable) {
                            Log.w(TAG, "blur draw fail: $t")
                        }
                    }
                }
            )
            Log.i(TAG, "View.draw hooked (blur)")
        } catch (t: Throwable) {
            Log.w(TAG, "draw hook fail: $t")
        }
    }

    /** 复用「全部应用」卡片样式：16dp 圆角 + 80% 半透明白 */
    private fun roundedCard(v: View, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusDp * v.resources.displayMetrics.density
        d.setColor(0xF2FFFFFF.toInt())
        return d
    }

    private fun isFaintWhite(d: GradientDrawable): Boolean {
        return try {
            val cs = d.color ?: return false
            val c = cs.defaultColor
            Color.alpha(c) <= 0x30 && Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200
        } catch (t: Throwable) { false }
    }

    /** 诊断：打印子树类名与背景 */
    private fun dumpTree(v: View, depth: Int) {
        if (depth > 25) return
        val bg = v.background
        val bgInfo = when (bg) {
            null -> "null"
            is GradientDrawable -> "Gradient"
            else -> bg.javaClass.simpleName
        }
        val tc = if (v is TextView) {
            val c = v.currentTextColor
            " textColor=#${Integer.toHexString(c)}"
        } else ""
        Log.i(
            TAG,
            "${"  ".repeat(depth)}${v.javaClass.name} id=${idName(v)} bg=$bgInfo " +
                "size=${v.width}x${v.height} alpha=${v.alpha}$tc"
        )
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1)
        }
    }

    /** 是否位于二级页（这些页面保持原深色） */
    private fun inSecondaryPage(v: View): Boolean =
        isInside(v, "game_toolbox_second_container") || isInside(v, "game_toolbox_third_container")

    /** 判断 view 是否位于指定 id 的容器内 */
    private fun isInside(v: View, ancestorId: String): Boolean {
        var p: android.view.ViewParent? = v.parent
        var depth = 0
        while (p != null && depth < 30) {
            if (p is View && idName(p) == ancestorId) return true
            p = p.parent
            depth++
        }
        return false
    }

    private fun idName(v: View): String = try {
        if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else ""
    } catch (t: Throwable) { "" }
}
