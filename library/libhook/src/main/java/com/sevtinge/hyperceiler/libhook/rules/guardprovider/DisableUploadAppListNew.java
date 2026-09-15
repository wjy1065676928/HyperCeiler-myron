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
package com.sevtinge.hyperceiler.libhook.rules.guardprovider;

import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class DisableUploadAppListNew extends BaseHook {
    private Method mAntiDefraudAppManagerMethod;

    @Override
    protected boolean useDexKit() {
        return true;
    }

    @Override
    protected boolean initDexKit() {
        // 本机（myron / OS4）实测：原匹配 usingStrings("AntiDefraudAppManager",
        // "https://flash.sec.miui.com/detect/app") 为 AND 语义，但这两个字符串现已分属不同类——
        //   "AntiDefraudAppManager"                → Lie2 的 a/c/d/e 方法
        //   "https://flash.sec.miui.com/detect/app" → Lls1.d(...)（网络层构建请求）
        // 无任何方法同时引用两者，故 singleOrNull() 返回 null、hook 失效。
        //
        // 真正的上传入口是 Lie2.e(Context)V：收集应用列表 → 转 JSON → 提交（失败时打印
        // "updateAllDetectApps error, "，该字符串全 dex 唯一，且只出现在 e 方法内）。
        // 用两个字符串 AND 精确锁定 e。
        mAntiDefraudAppManagerMethod = requiredMember("AntiDefraudAppManager", bridge -> bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .usingStrings("AntiDefraudAppManager", "updateAllDetectApps error, ")
            )).singleOrNull());
        return true;
    }

    @Override
    public void init() {
        com.sevtinge.hyperceiler.libhook.base.BaseHook.hookMethod(mAntiDefraudAppManagerMethod, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                // 直接跳过上报应用列表，不向 flash.sec.miui.com 提交。
                param.setResult(null);
            }
        });
    }
}
