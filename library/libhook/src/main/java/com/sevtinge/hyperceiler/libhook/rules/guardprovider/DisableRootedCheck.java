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

package com.sevtinge.hyperceiler.libhook.rules.guardprovider;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.File;
import java.lang.reflect.Method;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 阻止 guardprovider 将设备判定为已 root。
 *
 * 本机（myron / OS4）实测反编译 base.apk：guardprovider 有**两处独立**的 root 检测，
 * 而本机实际命中的是第 1 处。
 *
 * <h3>1) root 管理应用检测（本机真正的判定来源）</h3>
 * {@code Loe2.b} 是待检测的包名数组，实测内容为
 * {@code {me.bmax.apatch, me.weishu.kernelsu, com.topjohnwu.magisk}}。
 * {@code Loe2.x(GuardApplication)String} 逐个调用
 * {@code PackageManager.getApplicationInfo()} 探测，命中即打印
 * {@code "root manager found: "} 并返回该包名。
 * 本机装了 KernelSU（{@code me.weishu.kernelsu}），因此必然命中。
 *
 * <h3>2) su 文件检测（本机不会执行）</h3>
 * {@code Lse2.<clinit>}（该类仅此一个方法 + 一个 {@code PUBLIC STATIC FINAL} 字段 a）：
 * <pre>
 *   Boolean rooted = false;
 *   if (Build.TAGS != null &amp;&amp; Build.TAGS.contains("test-keys")) {
 *       // test-keys 是「是否继续检查 su 文件」的前置开关
 *       for (String p : {"/system/bin/su", "/system/xbin/su"}) {
 *           if (new File(p).exists()) { rooted = true; break; }
 *       }
 *   }
 *   if (rooted) Lal2.w("Current device is rooted");
 *   Lse2.a = rooted;
 * </pre>
 * 本机 {@code ro.build.tags} = release-keys，test-keys 分支不命中，故这段不会执行。
 *
 * <h3>为什么旧实现失效</h3>
 * 旧实现用 DexKit 匹配 {@code usingStrings("/system/bin/") + returnType(boolean)}，
 * 只对应第 2 处：引用该字符串的方法现已全部返回 void（且多为 MiPush / onetrack
 * 埋点 SDK 的日志脱敏代码），早已失配。
 * 改走 DexKit 定位 {@code Lse2.<clinit>} 同样不可行 —— 反射层拿不到
 * {@code <clinit>} 的 Method 对象，DexKit 解析该成员会直接失败
 * （报错：required DexKit member list not found: CheckRoot）；
 * 而 {@code Lse2.a} 是 static final，Android 12+ 用反射改写会被 ART 拒绝。
 *
 * <h3>本实现</h3>
 * <ul>
 *   <li>主：DexKit 按字符串 AND 定位 {@code Loe2.x}（{@code "root manager found: "}
 *       与 {@code "getApplicationInfo failed: "} 在全 dex 均唯一），hook 使其返回
 *       null，即「未发现 root 管理应用」。</li>
 *   <li>兜底：拦截 {@link File#exists()}，仅对两个 su 路径返回 false，
 *       覆盖第 2 处检测（本机虽不触发，但可防御 ROM 变化）。</li>
 * </ul>
 *
 * 该 hook 由设置项 {@code guard_provider_disable_root_check} 控制。
 */
public class DisableRootedCheck extends BaseHook {

    /** su 文件检测依次探测的路径，见上文 {@code Lse2.<clinit>} 控制流。 */
    private static final String[] SU_PATHS = {"/system/bin/su", "/system/xbin/su"};

    /** 定位到的 root 管理应用检测方法（Loe2.x）。 */
    private Method mRootManagerCheckMethod;

    @Override
    protected boolean useDexKit() {
        return true;
    }

    @Override
    protected boolean initDexKit() {
        try {
            // 两个字符串在全 dex 均唯一且同属 Loe2.x，AND 可精确定位。
            mRootManagerCheckMethod = requiredMember("RootManagerCheck", bridge -> bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .usingStrings("root manager found: ", "getApplicationInfo failed: ")
                )).singleOrNull());
        } catch (Throwable t) {
            // 定位失败不应中断下面的兜底方案，故吞掉异常继续。
            XposedLog.w(TAG, getPackageName(), "root manager check not located, fallback only: " + t);
        }
        return true;
    }

    @Override
    public void init() {
        // 1) root 管理应用检测：让它认为一个都没找到（本机真正的判定来源）
        if (mRootManagerCheckMethod != null) {
            XposedLog.d(TAG, getPackageName(), "hooking root manager check: " + mRootManagerCheckMethod);
            hookMethod(mRootManagerCheckMethod, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    // 返回 null == 未发现 root 管理应用
                    param.setResult(null);
                }
            });
        }

        // 2) su 文件检测：对两个 su 路径一律报告「不存在」
        findAndHookMethod(File.class, "exists", new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object self = param.getThisObject();
                if (!(self instanceof File)) return;
                // 用 getPath() 而非 getAbsolutePath()：检测方构造的就是绝对路径字面量
                String path = ((File) self).getPath();
                for (String su : SU_PATHS) {
                    if (su.equals(path)) {
                        param.setResult(false);
                        return;
                    }
                }
            }
        });

        XposedLog.d(TAG, getPackageName(), "disable root check installed");
    }
}
