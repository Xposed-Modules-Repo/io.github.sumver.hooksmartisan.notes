package io.github.sumver.hooksmartisan.notes;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 开屏广告 Hook（锤子便签 4.3.1 / versionCode 43100），全部结论来自对 4.3.1 APK 的反编译核实。
 *
 * <pre>
 * 一、冷启动链路（点桌面图标，系统流量走这条）
 *   com.smartisan.notes.Splash#onCreate
 *     → 读 intent 的 force_show_ad / launch_at_list；解析启动类型（COLD/WARM/HOT）
 *     → 依次判断：isVip（会员不展示）→ AdSdk.adConfig()==null → 广告开关
 *     → private OoooOoO(LaunchType, boolean): boolean   ← 广告时机闸门（走 Launch.OooO0oo）
 *         true  → private OooooOo(LaunchType, long) 异步加载开屏广告（SDK 广告位 1051360）
 *                 加载完成 → private OooooOO(LaunchType, long) → 展示（wq8.OooO0O0 → ISplashAd.showAd）
 *         false → private OoooOoo() 走 App 自带跳过流程：直接 startActivity(NewNotesActivity) + finish
 *
 * 二、热启动链路（便签退到后台再回前台；NewNotesActivity 的 ActivityLifecycleCallbacks 触发）
 *   Lyv3（InsertSplashController 单例，源文件 InsertSplashController.kt）
 *     → private OooOO0O(LaunchType, boolean) 广告时机闸门（走 Launch.OooO0oO）
 *     → private OooOOO0(Activity, LaunchType, long) → 打开 InsertSplashAdActivity 展示插屏开屏
 *   公开入口 Lyv3#OooOOO(Activity, LaunchType, boolean)：会员 / AdSdk 未初始化 / 时机不满足
 *   时直接 return —— 就是"不展示"的正常分支，可安全复用。
 *
 * 三、广告 SDK（展示兜底）
 *   美数 SDK：com.meishu.sdk.core.ad.splash.SplashAdLoader（开屏广告位 pid 1051360），
 *   展示接口 com.meishu.sdk.core.ad.splash.ISplashAd#showAd(ViewGroup)；
 *   ISplashAd 的实现树以 com.meishu.sdk.core.ad.splash.c 为抽象根（该类的 showAd
 *   只上报事件），真正的渲染入口是各平台适配器重写的 showAd（CSJ/Pangle/GDT/BD/...），
 *   本模块对**每一个重写者**逐一拦截（见 MEISHU_SPLASH_AD_CLASSES）。
 * </pre>
 *
 * 拦截层级（多重保险）：
 *   1) 时机闸门：Launch#OooO0oo / Launch#OooO0oO 强制返回 false
 *      —— 广告从"是否需要展示"这一步就被否定，不发起请求、不进入加载；
 *   2) 入口兜底：Splash#OoooOoO / Lyv3#OooOOO 强制返回
 *      —— 即使闸门因版本差异失效，也直接走 App 自带跳过流程；
 *   3) SDK 兜底：com.meishu.sdk.core.ad.splash.c#showAd 直接拦截展示
 *      —— 无论广告从哪条路径加载出来，都不会被画到界面上；
 *   4) 视图清理：App 自带跳过流程 OoooOoo() 执行后，把开屏广告容器 splash_container
 *      重新压回 GONE（防止跳过瞬间残留一帧广告内容）。
 *
 * 各拦截点对应的都是 App 已有的"不展示 / 跳过"分支，不存在伪造状态或异常路径；
 * 任何类 / 方法找不到时只记录日志并跳过，绝不影响 App 正常运行。
 *
 * 开关：受设置界面「去除开屏广告」({@link Prefs#K_SPLASH_AD}) 控制，默认开启；
 * 关闭后所有拦截点直接放行，行为与未装模块一致。
 */
final class SplashAdHook {

    /** 冷启动开屏页（LAUNCHER Activity）。 */
    private static final String SPLASH_ACTIVITY = "com.smartisan.notes.Splash";
    /** 热启动插屏开屏页。 */
    private static final String INSERT_SPLASH_ACTIVITY =
            "com.smartisan.notes.ad.splash.ui.InsertSplashAdActivity";
    /** 启动管理器（广告时机闸门所在类）。 */
    private static final String LAUNCH = "com.smartisan.notes.Launch";
    /** 热启动插屏开屏控制器（源文件 InsertSplashController.kt）。 */
    private static final String INSERT_SPLASH_CONTROLLER = "yv3";
    /** 美数 SDK 开屏广告抽象根类（ISplashAd 的唯一实现根，其余适配器均派生自它）。 */
    private static final String MEISHU_SPLASH_AD_ROOT = "com.meishu.sdk.core.ad.splash.c";
    /** 开屏广告容器资源名（activity_splash.xml，默认即 GONE）。 */
    private static final String SPLASH_CONTAINER_ID_NAME = "splash_container";
    // 已停用（1.9 起移除「隐藏免广告VIP按钮」功能，代码保留备查）：
    // 开屏页「免广告·VIP」购买按钮资源名（activity_splash.xml）
    // private static final String SPLASH_VIP_BUTTON_ID_NAME = "buy_vip";

    /** 类缺失日志只输出一次（避免刷屏）。 */
    private static final Map<String, Boolean> sMissingClasses = new ConcurrentHashMap<>();

    private SplashAdHook() {
    }

    /** 设置开关：去除开屏广告（默认开启）。 */
    private static boolean enabled() {
        return Prefs.isEnabled(Prefs.K_SPLASH_AD);
    }

    static void install(ClassLoader cl) {
        installTimingGates(cl);
        installSplashEntry(cl);
        installInsertSplashController(cl);
        installSdkBackstop(cl);
        // 已停用（1.9 起移除「隐藏免广告VIP按钮」功能，代码保留备查）：
        // 开屏页「免广告·VIP」按钮注册到公共 id 守卫（见 ViewIdGuard）
        // ViewIdGuard.addRule(SPLASH_VIP_BUTTON_ID_NAME, Prefs.K_SPLASH_VIP);
        XposedInit.log("SplashAdHook installed.");
    }

    /* ==================== 1) 广告时机闸门（主拦截点） ==================== */

    /**
     * Launch#OooO0oo(LaunchType, boolean): boolean —— 冷启动闸门
     *   Splash#onCreate → OoooOoO → 本方法；true 才会进入广告加载。
     * Launch#OooO0oO(LaunchType, boolean): boolean —— 热启动闸门
     *   InsertSplashController#OooOO0O → 本方法；true 才会打开插屏开屏。
     *
     * 两个方法只被开屏广告流程调用（4.3.1 静态引用核实），返回 false 等价于：
     * App 自己判定"本次不需要开屏广告"，其余启动逻辑完全不受影响。
     */
    private static void installTimingGates(ClassLoader cl) {
        Class<?> launch = findClassSafe(cl, LAUNCH);
        if (launch == null) {
            return;
        }
        hookAllSafe(launch, "OooO0oo", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!enabled()) {
                    return;
                }
                XposedInit.log("splash ad gate (cold launch) blocked: Launch.OooO0oo -> false");
                param.setResult(Boolean.FALSE);
            }
        });
        hookAllSafe(launch, "OooO0oO", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!enabled()) {
                    return;
                }
                XposedInit.log("splash ad gate (insert splash) blocked: Launch.OooO0oO -> false");
                param.setResult(Boolean.FALSE);
            }
        });
    }

    /* ==================== 2) 启动入口兜底 ==================== */

    /**
     * Splash#OoooOoO(LaunchType, boolean): boolean —— 冷启动入口
     * 返回 false 后 onCreate 直接调用 App 自带的跳过流程 OoooOoo()：
     * 取消广告任务、回调广告状态、startActivity(NewNotesActivity) 并 finish。
     */
    private static void installSplashEntry(ClassLoader cl) {
        Class<?> splash = findClassSafe(cl, SPLASH_ACTIVITY);
        if (splash == null) {
            return;
        }
        hookAllSafe(splash, "OoooOoO", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!enabled()) {
                    return;
                }
                // 兜底重试：开屏流程触发时 SDK 类必然已加载，此时再挂一次 SDK 展示拦截
                installSdkBackstop(splash.getClassLoader());
                XposedInit.log("splash ad entry blocked: Splash.OoooOoO -> false ("
                        + INSERT_SPLASH_ACTIVITY + " not used for cold launch)");
                param.setResult(Boolean.FALSE);
            }
        });
        // 诊断：App 自带跳过流程执行后，确认广告容器里确实没有内容残留
        hookAllSafe(splash, "OoooOoo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled()) {
                    return;
                }
                Diagnose.afterSplashSkipped(param.thisObject);
            }
        });
    }

    /* ==================== 3) 热启动插屏入口兜底 ==================== */

    /**
     * Lyv3#OooOOO(Activity, LaunchType, boolean): void —— 插屏开屏公开入口。
     * 方法内部本就有"会员 / 未初始化 / 时机不满足 → 直接 return"的正常分支，
     * 这里直接走同一分支，不打开 InsertSplashAdActivity。
     */
    private static void installInsertSplashController(ClassLoader cl) {
        Class<?> controller = findClassSafe(cl, INSERT_SPLASH_CONTROLLER);
        if (controller == null) {
            return;
        }
        hookAllSafe(controller, "OooOOO", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!enabled()) {
                    return;
                }
                // 兜底重试：热启动插屏流程触发时再挂一次 SDK 展示拦截
                installSdkBackstop(controller.getClassLoader());
                XposedInit.log("insert splash blocked: InsertSplashController.OooOOO -> skip ("
                        + INSERT_SPLASH_ACTIVITY + " not launched)");
                param.setResult(null);
            }
        });
    }

    /* ==================== 4) SDK 展示兜底 ==================== */

    /**
     * 美数 SDK 里所有重写 showAd(ViewGroup) 的开屏广告类（APK 反编译逐一核实，共 21 个）。
     *
     * 4.3.1 中 ISplashAd 的实现树只有一个抽象根
     * com.meishu.sdk.core.ad.splash.c（它的 showAd 只上报事件），
     * 真正的渲染入口分散在各平台适配器里（CSJ/Pangle/GDT/BD/华为/OPPO/KS/MiMo/JD/
     * Sigmob/多盟/自有渲染），因此这里把**每一个重写者**都拦下来：
     * 无论广告由哪个平台适配器渲染，都不会被画到容器上。
     *
     * 只影响开屏广告：这些类全部位于 com.meishu.sdk.*.splash 命名空间，
     * 与信息流 / 插屏 / 激励视频等其它广告位的适配器互不相干。
     */
    private static final String[] MEISHU_SPLASH_AD_CLASSES = {
            MEISHU_SPLASH_AD_ROOT,                                // 抽象根（基类 showAd）
            "com.meishu.sdk.meishu_ad.splash.c",                  // 自有渲染
            "com.meishu.sdk.platform.bd.splash.BDNativeSplashAd",
            "com.meishu.sdk.platform.bd.splash.BDSplashAd",
            "com.meishu.sdk.platform.csj.splash.CSJNativeSplashAd",
            "com.meishu.sdk.platform.csj.splash.CsjNewSplashAd",
            "com.meishu.sdk.platform.duomeng.splash.DMSplashAd",
            "com.meishu.sdk.platform.gdt.splash.GDTNativeSplashAd",
            "com.meishu.sdk.platform.gdt.splash.GDTSplashAd",
            "com.meishu.sdk.platform.huawei.splash.HWBiddingSplashAd",
            "com.meishu.sdk.platform.huawei.splash.HWSplashAd",
            "com.meishu.sdk.platform.jd.splash.JDSplashAd",
            "com.meishu.sdk.platform.ks.splash.KSNativeSplashAd",
            "com.meishu.sdk.platform.ks.splash.KSSplashAd",
            "com.meishu.sdk.platform.mimo.splash.MimoSplashAd",
            "com.meishu.sdk.platform.ms.splash.b",
            "com.meishu.sdk.platform.oppo.splash.OPPOSplashAd",
            "com.meishu.sdk.platform.pangle.splash.PangleSplashAd",
            "com.meishu.sdk.platform.sigmob.custom.splash.SigmobCustomSplashAd",
            // 以下两个为继承实现（本身不重写 showAd），一并列出以便版本演进后依旧兜底
            "com.meishu.sdk.platform.custom.splash.MsCustomSplashAd",
            "com.meishu.sdk.platform.tradplus.splash.MsSplashAdapter",
    };

    private static volatile boolean sSdkBackstopInstalled;

    /**
     * 逐个拦截上面罗列的 showAd(ViewGroup)。
     *
     * 只在类**已经加载**时才挂 Hook（findClassSafe 用 Class.forName(name, false, cl)，
     * 不会为了 Hook 提前加载类）；在 Hook 安装时尝试一次，若 SDK 类尚未加载，
     * 会在开屏流程触发时再重试（见 installSplashEntry / installInsertSplashController）。
     */
    private static void installSdkBackstop(ClassLoader cl) {
        if (sSdkBackstopInstalled) {
            return;
        }
        int hooked = 0;
        for (String name : MEISHU_SPLASH_AD_CLASSES) {
            Class<?> type = findClassSafe(cl, name);
            if (type != null && hookShowAd(type)) {
                hooked++;
            }
        }
        if (hooked == 0) {
            // SDK 适配器尚未加载（进程启动早期）：不置标志，留待开屏流程重试
            return;
        }
        sSdkBackstopInstalled = true;
        XposedInit.log("SDK backstop ready: " + hooked + " splash ad class(es) guarded");
    }

    /** 拦截一个类的 showAd(ViewGroup)；返回是否成功挂上。 */
    private static boolean hookShowAd(Class<?> type) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(
                    "showAd", android.view.ViewGroup.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) {
                        return;
                    }
                    XposedInit.log("SDK backstop: splash showAd suppressed on "
                            + param.method.getDeclaringClass().getSimpleName());
                    param.setResult(null);
                }
            });
            return true;
        } catch (NoSuchMethodException e) {
            // 该子类未重写 showAd：继承基类实现，已由基类拦截覆盖
            return false;
        } catch (Throwable t) {
            XposedInit.log("hook failed: " + type.getName() + ".showAd " + t);
            return false;
        }
    }

    /* ==================== 诊断辅助 ==================== */

    /** 跳过流程执行后的健康检查，便于区分"拦截成功"与"容器里仍有残留内容"。 */
    private static final class Diagnose {

        /** 广告视图日志只输出一次，避免每次冷启动刷屏。 */
        private static volatile boolean sLoggedOnce;

        private Diagnose() {
        }

        static void afterSplashSkipped(Object splashActivity) {
            if (sLoggedOnce) {
                return;
            }
            sLoggedOnce = true;
            try {
                XposedInit.logTargetVersionOnce();
                if (!(splashActivity instanceof Activity)) {
                    return;
                }
                Activity activity = (Activity) splashActivity;
                int id = activity.getResources().getIdentifier(
                        SPLASH_CONTAINER_ID_NAME, "id", XposedInit.TARGET_PKG);
                if (id == 0) {
                    return;
                }
                View container = activity.findViewById(id);
                if (!(container instanceof ViewGroup)) {
                    return;
                }
                ViewGroup group = (ViewGroup) container;
                int childCount = group.getChildCount();
                if (childCount > 0) {
                    // 出现这行说明广告已被绘制进容器（拦截未覆盖到该路径），
                    // 清空容器并压回 GONE，保证视觉效果干净
                    group.removeAllViews();
                    group.setVisibility(View.GONE);
                    XposedInit.log("WARN: ad container had " + childCount
                            + " child view(s) after skip; cleaned up");
                } else {
                    XposedInit.log("splash skipped cleanly (ad container empty)");
                }
            } catch (Throwable t) {
                XposedInit.log("splash diagnostics failed: " + t);
            }
        }
    }

    /* ==================== 工具 ==================== */

    private static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
            // initialize=false：只取已加载的类定义，不为 Hook 触发静态初始化
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            if (sMissingClasses.putIfAbsent(name, Boolean.TRUE) == null) {
                XposedInit.log("class not found (skip): " + name);
            }
            return null;
        }
    }

    private static void hookAllSafe(Class<?> cls, String method, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(cls, method, cb);
            XposedInit.log("hooked " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedInit.log("hook failed: " + cls.getSimpleName() + "." + method + " " + t);
        }
    }
}
