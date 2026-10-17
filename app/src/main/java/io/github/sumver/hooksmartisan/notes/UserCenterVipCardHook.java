package io.github.sumver.hooksmartisan.notes;

import android.content.res.Resources;
import android.view.View;
import android.widget.RelativeLayout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 个人中心页「便签会员」卡片隐藏 Hook（锤子便签 4.3.1 / versionCode 43100），
 * 结论来自对 4.3.1 APK 的反编译核实。
 *
 * <pre>
 * 入口链路：更多设置页账户行点击（setting_smiling_cloud）
 *   → MoreActivity#o0O0O00 → o0000OOO(false, "setting_page,usercenter_button")
 *   → startActivity(com.smartisan.notes.sync.MainSyncActivity)
 *   → MainSyncActivity extends AccountsActivity，内容是
 *     com.smartisan.common.sync.SettingFragment（布局 res/layout/common_cloud_sync_main.xml）
 *
 * 目标卡片：vip_benefits_info（0x7f09084a，ConstraintLayout）
 *   - 标题 vip_benefit_title（@string/ai_vip"便签会员"）
 *   - 描述 vip_benefit_desc（@string/upgrade_vip_desc"开通会员，尊享全部权益"）
 *   - 权益列表 benefit_list（RecyclerView）
 *   - 按钮 btn_buy → btn_buy_title（"立即开通"，非会员时由 SettingFragment 设置）
 *   - 该卡片在代码中没有任何显隐控制（全 APK 无代码引用其 id，纯静态可见）
 *
 * 布局链（RelativeLayout，layout_below 逐级锚定）：
 *   userItemSwitchView（账户行，marginTop=10dip）
 *     → vip_benefits_info（会员卡片，marginTop=7dip，layout_below=账户行）
 *       → commonSyncSwitchView（同步开关-上，marginTop=7dip，layout_below=卡片）
 *         → commonSyncWithWifi（同步开关-下）
 *           → btn_cancellation → exitLogin
 *
 * 空隙处理（用户要求"避免一片空白"）：卡片 GONE 后 layout_below 锚链可能落空，
 * 这里显式把 commonSyncSwitchView 重新锚定到账户行下方（userItemSwitchView），
 * 保证卡片位置被完全吃掉、不留空白也不与账户行重叠；重复调用幂等（已锚定则不重复设置）。
 *
 * 拦截方式：
 *   1) SettingFragment#onCreateView / onViewCreated / onResume 之后主动隐藏 + 压缩锚链；
 *   2) ViewIdGuard 注册 vip_benefits_info 规则兜底（任何把卡片设为可见的调用改写为 GONE）。
 *
 * 开关：受 {@link Prefs#K_HIDE_VIP_CARD} 控制（默认开启）；关闭后不拦截，行为与未装模块一致。
 * 任何类 / 方法找不到时只记录日志并跳过，绝不影响 App 正常运行。
 */
final class UserCenterVipCardHook {

    /** 个人中心页 Fragment。 */
    private static final String SETTING_FRAGMENT = "com.smartisan.common.sync.SettingFragment";
    /** 会员卡片（容器）资源名。 */
    private static final String ID_VIP_BENEFITS_INFO = "vip_benefits_info";
    /** 卡片下方第一个元素：同步开关行。 */
    private static final String ID_COMMON_SYNC_SWITCH = "commonSyncSwitchView";
    /** 账户行（重新锚定的目标）。 */
    private static final String ID_USER_ITEM = "userItemSwitchView";

    /** id 解析缓存（name -> id）；0 表示尚未解析成功，下次再试。 */
    private static final Map<String, Integer> sIdCache = new ConcurrentHashMap<>();

    private UserCenterVipCardHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> fragment = findClassSafe(cl, SETTING_FRAGMENT);
        if (fragment == null) {
            return;
        }

        // onCreateView 返回值即根视图；onViewCreated 首参即根视图；onResume 时经 getView() 取根视图
        hookAllSafe(fragment, "onCreateView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyClean(param.getResult());
            }
        });
        hookAllSafe(fragment, "onViewCreated", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0) {
                    applyClean(param.args[0]);
                }
            }
        });
        hookAllSafe(fragment, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyClean(param.thisObject);
            }
        });

        // 全局守卫兜底
        ViewIdGuard.addRule(ID_VIP_BENEFITS_INFO, Prefs.K_HIDE_VIP_CARD);

        XposedInit.log("UserCenterVipCardHook installed.");
    }

    /** 隐藏会员卡片并压缩遗留空档；target 可以是根视图或 Fragment 实例。 */
    private static void applyClean(Object target) {
        if (!Prefs.isEnabled(Prefs.K_HIDE_VIP_CARD)) {
            return;
        }
        View root = rootViewOf(target);
        if (root == null) {
            return;
        }
        try {
            Resources res = root.getResources();
            int cardId = resolveId(res, ID_VIP_BENEFITS_INFO);
            int syncId = resolveId(res, ID_COMMON_SYNC_SWITCH);
            int accountId = resolveId(res, ID_USER_ITEM);

            // 1) 卡片置 GONE
            if (cardId != 0) {
                View card = root.findViewById(cardId);
                if (card != null && card.getVisibility() != View.GONE) {
                    card.setVisibility(View.GONE);
                    XposedInit.log("vip_benefits_info hidden on user center");
                }
            }

            // 2) 空隙处理：卡片下方的同步开关行重新锚定到账户行下方
            if (syncId != 0 && accountId != 0) {
                View sync = root.findViewById(syncId);
                if (sync != null && sync.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                    RelativeLayout.LayoutParams lp =
                            (RelativeLayout.LayoutParams) sync.getLayoutParams();
                    int[] rules = lp.getRules();
                    if (rules == null || rules[RelativeLayout.BELOW] != accountId) {
                        lp.addRule(RelativeLayout.BELOW, accountId);
                        sync.setLayoutParams(lp);
                        XposedInit.log("commonSyncSwitchView re-anchored below userItemSwitchView"
                                + " (card gap removed)");
                    }
                }
            }
        } catch (Throwable t) {
            XposedInit.log("applyClean (vip card) failed: " + t);
        }
    }

    /** 取根视图：View 直接用；Fragment 走 getView()。 */
    private static View rootViewOf(Object target) {
        if (target instanceof View) {
            return (View) target;
        }
        try {
            Object v = XposedHelpers.callMethod(target, "getView");
            if (v instanceof View) {
                return (View) v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 运行时解析资源 id（name -> id，带缓存；失败返回 0，下次再试）。 */
    private static int resolveId(Resources res, String name) {
        Integer cached = sIdCache.get(name);
        if (cached != null && cached != 0) {
            return cached;
        }
        try {
            int id = res.getIdentifier(name, "id", XposedInit.TARGET_PKG);
            if (id != 0) {
                sIdCache.put(name, id);
                XposedInit.log(name + " id resolved: " + Integer.toHexString(id));
                return id;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            XposedInit.log("class not found (skip): " + name);
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
