package com.sumver.hammernotes;

import android.app.AndroidAppHelper;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed / EdXposed 模块入口。
 *
 * 目标：com.smartisan.notes（锤子便签）v4.3.1 (versionCode 43100)
 * 作用：去除开屏广告（冷启动 Splash 页 + 热启动插屏开屏）。只拦截开屏广告的展示链路，
 *      不改动其它广告位、不伪造会员状态、不拦截网络请求（详见 {@link SplashAdHook}）。
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "HammerNotesAdFree";
    public static final String TARGET_PKG = "com.smartisan.notes";
    /** 4.3.1 => versionCode 43100；仅作提示，不做硬性拦截。 */
    public static final int TARGET_VERSION_CODE = 43100;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        // 仅在主进程安装 Hook（避免推送等子进程重复安装）
        if (lpparam.processName != null && lpparam.processName.contains(":")) {
            log("skip secondary process: " + lpparam.processName);
            return;
        }

        log("loaded target: " + lpparam.packageName + " process=" + lpparam.processName);

        // 先触发配置加载（异步、经模块自带只读 ContentProvider 读取，见 Prefs）：
        // 每个进程只加载一次；开关修改后需重启（强制停止）目标应用才生效
        try {
            Prefs.prime();
        } catch (Throwable t) {
            log("Prefs prime failed: " + t);
        }

        // 各功能 Hook（其中会向 ViewIdGuard 注册 id 拦截规则）
        try {
            SplashAdHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("SplashAdHook install failed: " + t);
        }

        try {
            MoreSettingsCleanHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("MoreSettingsCleanHook install failed: " + t);
        }

        try {
            UserCenterVipCardHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("UserCenterVipCardHook install failed: " + t);
        }

        try {
            RagEntryHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("RagEntryHook install failed: " + t);
        }

        try {
            AiEntryHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("AiEntryHook install failed: " + t);
        }

        try {
            NoteShareHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("NoteShareHook install failed: " + t);
        }

        try {
            HideFooterHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("HideFooterHook install failed: " + t);
        }

        // 公共 id 守卫（多个净化功能共用一个 View.setVisibility 钩子；规则已在上面注册）
        try {
            ViewIdGuard.install();
        } catch (Throwable t) {
            log("ViewIdGuard install failed: " + t);
        }
    }

    public static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    /** 延迟输出目标 App 版本（handleLoadPackage 时 Application 可能尚未创建）。 */
    private static volatile boolean sVersionLogged = false;

    public static void logTargetVersionOnce() {
        if (sVersionLogged) {
            return;
        }
        sVersionLogged = true;
        try {
            android.content.Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                return;
            }
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(TARGET_PKG, 0);
            log("target app version: " + pi.versionName + " (" + pi.versionCode + "), expected "
                    + TARGET_VERSION_CODE);
        } catch (Throwable ignored) {
        }
    }
}
