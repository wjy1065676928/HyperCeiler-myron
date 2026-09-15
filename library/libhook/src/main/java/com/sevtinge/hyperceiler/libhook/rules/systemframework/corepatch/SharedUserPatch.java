package com.sevtinge.hyperceiler.libhook.rules.systemframework.corepatch;

import static com.sevtinge.hyperceiler.libhook.base.BaseHook.deoptimizeMethods;
import static com.sevtinge.hyperceiler.libhook.base.BaseHook.findClassIfExists;

import android.content.pm.ApplicationInfo;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.PackageTarget;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public class SharedUserPatch extends CorePatchHelper {

    private static final String TAG = "SharedUserPatch";

    public void init(PackageTarget lpparam) {
        // Android 14+
        try {
            var utilClass = findClass("com.android.server.pm.ReconcilePackageUtils", lpparam.getClassLoader());
            if (utilClass != null) {
                deoptimizeMethods(utilClass, "reconcilePackages");
            }

            // ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS 是 private static final，
            // 但自 Android 12 起，Field.set() 的 final 校验已下沉到 ART native 层，
            // 仅抹掉 java.lang.reflect.Field.accessFlags 的 FINAL 位不再有效，
            // 会抛 IllegalAccessException: Cannot set private static final field。
            //
            // 现代 AOSP 的该常量并非硬编码，而是由 aconfig flag 推导（实测 services.jar）：
            //   private static final boolean ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS =
            //       !Flags.restrictNonpreloadsSystemShareduids();
            // 因此改为 hook 那个 flag 方法返回 false，<clinit> 自然会算出 true。
            if (CorePatchHelper.isSharedUserEnabled()) {
                try {
                    findAndHookMethod(
                        "com.android.internal.hidden_from_bootclasspath.android.content.pm.Flags",
                        lpparam.getClassLoader(),
                        "restrictNonpreloadsSystemShareduids",
                        new IMethodHook() {
                            @Override
                            public void before(HookParam param) {
                                // restrict=true 会令 ALLOW_NON_PRELOADS... 为 false，这里反过来
                                param.setResult(false);
                            }
                        }
                    );
                    XposedLog.d(TAG, "system", "hooked restrictNonpreloadsSystemShareduids -> false");
                } catch (Throwable e) {
                    XposedLog.w(TAG, "system", "hook restrictNonpreloadsSystemShareduids failed: " + e);
                }

                // 兜底：若 ReconcilePackageUtils 已被提前加载（<clinit> 已执行、常量已定死），
                // 上面的 flag hook 就来不及生效。此时改从 MIUI 自家授权入口放行 ——
                // reconcilePackages 在判定失败前会调用：
                //   PackageManagerServiceStub.get().allowInstallNonPreloadApp(pkgName)
                // 让它返回 true 即可绕过拒绝。
                try {
                    findAndHookMethod(
                        "com.android.server.pm.PackageManagerServiceStub",
                        lpparam.getClassLoader(),
                        "allowInstallNonPreloadApp",
                        String.class,
                        new IMethodHook() {
                            @Override
                            public void before(HookParam param) {
                                param.setResult(true);
                            }
                        }
                    );
                    XposedLog.d(TAG, "system", "hooked allowInstallNonPreloadApp -> true");
                } catch (Throwable e) {
                    XposedLog.w(TAG, "system", "hook allowInstallNonPreloadApp failed: " + e);
                }
            }
        } catch (Throwable t) {
            XposedLog.e(TAG, "system", "Android 14+ hook failed, crash: " + t);
        }

        // 签名校验绕过（Android 11 引入，Android 17 依然适用）
        try {
            Class<?> signingDetails = getSigningDetails(lpparam.getClassLoader());
            // for SharedUser
            // "Package " + packageName + " has a signing lineage " + "that diverges from the lineage of the sharedUserId"
            // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r21:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=728;drc=02a58171a9d41ad0048d6a1a48d79dee585c22a5
            hookAllMethods(signingDetails, "hasCommonAncestor", new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (CorePatchHelper.isSharedUserEnabled()
                        // because of LSPosed's bug, we can't hook verifySignatures while deoptimize it
                        && Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch((o) -> "verifySignatures".equals(o.getMethodName()))
                    )
                        param.setResult(true);
                }
            });

            var utilClass = findClass("com.android.server.pm.PackageManagerServiceUtils", lpparam.getClassLoader());
            if (utilClass != null) {
                deoptimizeMethods(utilClass, "verifySignatures");
                hookVerifySignatures(utilClass);
            }

            // choose a signature after all old signed packages are removed
            var sharedUserSettingClass = findClass("com.android.server.pm.SharedUserSetting", lpparam.getClassLoader());
            hookAllMethods(sharedUserSettingClass, "removePackage", new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        if (!CorePatchHelper.isSharedUserEnabled())
                            return;
                        var flags = (int) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(param.getThisObject(), "uidFlags");
                        if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                            return; // do not modify system's signature
                        var toRemove = param.getArgs()[0]; // PackageSetting
                        if (toRemove == null) return;
                        var removed = false; // Is toRemove really needed to be removed
                        var sharedUserSig = Setting_getSigningDetails(param.getThisObject());
                        Object newSig = null;
                        var packages = /*Watchable?ArraySet<PackageSetting>*/ SharedUserSetting_packages(param.getThisObject());
                        var size = (int) com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(packages, "size");
                        for (var i = 0; i < size; i++) {
                            var p = com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(packages, "valueAt", i);
                            // skip the removed package
                            if (toRemove.equals(p)) {
                                removed = true;
                                continue;
                            }
                            var packageSig = Setting_getSigningDetails(p);
                            // if old signing exists, return
                            if ((boolean) callOriginMethod(packageSig, "checkCapability", sharedUserSig, 0) || (boolean) callOriginMethod(sharedUserSig, "checkCapability", packageSig, 0)) {
                                return;
                            }
                            // otherwise, choose the first signature we meet, and merge with others if possible
                            // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/ReconcilePackageUtils.java;l=193;drc=c9a8baf585e8eb0f3272443930301a61331b65c1
                            // respect to system
                            if (newSig == null) newSig = packageSig;
                            else newSig = SigningDetails_mergeLineageWith(newSig, packageSig);
                        }
                        if (!removed || newSig == null) return;
                        XposedLog.w(TAG, "system", "updating signature in sharedUser during remove: " + param.getThisObject());
                        Setting_setSigningDetails(param.getThisObject(), newSig);
                    }
                }
            );

            hookAllMethods(sharedUserSettingClass, "addPackage", new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        if (!CorePatchHelper.isSharedUserEnabled())
                            return;
                        var flags = (int) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(param.getThisObject(), "uidFlags");
                        if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                            return; // do not modify system's signature
                        var toAdd = param.getArgs()[0]; // PackageSetting
                        if (toAdd == null) return;
                        var added = false;
                        var sharedUserSig = Setting_getSigningDetails(param.getThisObject());
                        Object newSig = null;
                        var packages = /*Watchable?ArraySet<PackageSetting>*/ SharedUserSetting_packages(param.getThisObject());
                        var size = (int) com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(packages, "size");
                        for (var i = 0; i < size; i++) {
                            var p = com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(packages, "valueAt", i);
                            if (toAdd.equals(p)) {
                                // must be an existing package
                                added = true;
                                p = toAdd;
                            }
                            var packageSig = Setting_getSigningDetails(p);
                            // if old signing exists, return
                            if ((boolean) callOriginMethod(packageSig, "checkCapability", sharedUserSig, 0) || (boolean) callOriginMethod(sharedUserSig, "checkCapability", packageSig, 0)) {
                                return;
                            }
                            // otherwise, choose the first signature we meet, and merge with others if possible
                            // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/ReconcilePackageUtils.java;l=193;drc=c9a8baf585e8eb0f3272443930301a61331b65c1
                            // respect to system
                            if (newSig == null) newSig = packageSig;
                            else newSig = SigningDetails_mergeLineageWith(newSig, packageSig);
                        }
                        if (!added || newSig == null) return;
                        XposedLog.w(TAG, "system", "updating signature in sharedUser during add " + toAdd + ": " + param.getThisObject());
                        Setting_setSigningDetails(param.getThisObject(), newSig);
                    }
                }
            );
        } catch (Throwable t) {
            XposedLog.e(TAG, "system", "Android 11+ hook failed, crash: " + t);
        }
    }

    /**
     * SigningDetails 自 Android 13 起从 PackageParser 内部类提升为独立类。
     * 本 fork 仅适配 Android 17，直接使用新位置。
     */
    Class<?> getSigningDetails(ClassLoader classLoader) {
        return findClassIfExists("android.content.pm.SigningDetails", classLoader);
    }

    static Object callOriginMethod(Object obj, String methodName, Object... args) {
        try {
            var method = com.sevtinge.hyperceiler.libhook.base.BaseHook.findMethodBestMatch(obj.getClass(), methodName, args);
            return com.sevtinge.hyperceiler.libhook.base.BaseHook.invokeOriginalMethod(method, obj, args);
        } catch (IllegalArgumentException e) {
            throw e;
        }
    }

    /**
     * Get signing details for PackageSetting or SharedUserSetting
     */
    Object Setting_getSigningDetails(Object pkgOrSharedUser) {
        // PackageSetting/SharedUserSetting.<PackageSignatures>signatures.<SigningDetails>mSigningDetails
        return com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(pkgOrSharedUser, "signatures"), "mSigningDetails");
    }

    /**
     * Set signing details for PackageSetting or SharedUserSetting
     */
    void Setting_setSigningDetails(Object pkgOrSharedUser, Object signingDetails) {
        com.sevtinge.hyperceiler.libhook.base.BaseHook.setObjectField(com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(pkgOrSharedUser, "signatures"), "mSigningDetails", signingDetails);
    }

    /**
     * SharedUserSetting 持有其成员包的集合。
     * Android 13 起字段名为 mPackages（旧版的 packages 在 Android 17 上已不存在）。
     */
    protected Object SharedUserSetting_packages(Object /*SharedUserSetting*/ sharedUser) {
        return com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(sharedUser, "mPackages");
    }

    /**
     * 合并签名血缘。Android 13 起重载带 mergeTarget 参数，
     * 2 表示 MERGE_RESTRICTED_CAPABILITY。
     */
    protected Object SigningDetails_mergeLineageWith(Object self, Object other) {
        return com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(self, "mergeLineageWith", other, 2 /*MERGE_RESTRICTED_CAPABILITY*/);
    }

    private void hookVerifySignatures(Class<?> utilClass) {
        for (Method method : utilClass.getDeclaredMethods()) {
            if (!"verifySignatures".equals(method.getName()) || method.getReturnType() != Boolean.TYPE) {
                continue;
            }
            com.sevtinge.hyperceiler.libhook.base.BaseHook.hookMethod(method, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (CorePatchHelper.isFeatureEnabled(CorePatchHelper.PREF_AUTH_CREAK, false)) {
                        param.setResult(false);
                    }
                }
            });
        }
    }
}
