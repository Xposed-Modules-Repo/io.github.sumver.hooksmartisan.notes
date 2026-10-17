package com.sumver.hammernotes;

import android.app.Activity;
import android.content.res.Resources;
import android.text.Html;
import android.view.View;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 「更多设置」页净化 Hook（锤子便签 4.3.1 / versionCode 43100），结论来自对 4.3.1 APK 的反编译核实。
 *
 * <pre>
 * 目标页：com.smartisan.notes.sync.MoreActivity（布局 res/layout/activity_more.xml，
 *         「设置 → 更多设置」，manifest 中 exported=false）
 *
 * 1) ad_switch（0x7f09007d）「看视频免广告」
 *      - 布局默认 visibility=gone；由代码按业务状态决定是否展示：
 *        MoreActivity#o0000oo()（决策）：
 *          qu7.OooOO0()==false → setVisibility(GONE)（功能未就绪）
 *          qu7.OooO()==false   → setVisibility(GONE) + 注册异步观察者
 *                                （回调 dp5 → MoreActivity.Oooo() → ooOO()）
 *          qu7.OooO()==true    → ooOO()（设为可见 + 皮肤背景 + 剩余时间文案）
 *        MoreActivity#ooOO()（展示）：
 *          ad_switch.setVisibility(VISIBLE) + 皮肤 + tv_reward_remaining_time 文案
 *        子控件 tv_reward_remaining_time（0x7f0907fc）随行一起隐藏。
 *
 * 2) invitation_view（0x7f09036f）「邀请好友得会员」
 *      - 布局默认可见，代码中无显隐逻辑（o00ooo() 只设置点击监听）。
 *
 * 3) 账户行（setting_smiling_cloud）下方的 vip_desc（0x7f09084b）促销文案
 *      - MoreActivity#o00000oo(TextView, TextView, VipInfo)（smali 5643）：
 *        非会员（或 VipInfo 未加载且已登录）时把 vip_desc 文案设为
 *        upgrade_vip_prompt / upgrade_vip_prompt_dark（"开通会员，尊享全部权益"，Html 着色）；
 *        会员时设为"会员有效期至 %s"（0x7f1303ae），未登录时设为 membership_desc_not_login。
 *      - onStart 里经 w7a 生命周期观察者回调（ap5 → o000000O → o00000oo），
 *        是 vip_desc 的唯一文本设置入口。
 *
 * 拦截方式（多重保险，只隐藏入口/文案，不改动业务逻辑与其它页面）：
 *   1) 页面生命周期：MoreActivity#onCreate / onResume 之后，把开启开关的行置 GONE；
 *   2) 展示路径：MoreActivity#ooOO / o0000oo 之后同上（覆盖 VIP 状态异步回调触发的显示）；
 *   3) 促销文案：MoreActivity#o00000oo 之后，若 vip_desc 文本命中促销文案则清空
 *      （会员有效期等其它文案原样保留；文案运行时从资源解析，不硬编码）；
 *   4) 全局守卫：ViewIdGuard 注册 ad_switch / invitation_view 两条规则，
 *      任何把目标行设为可见的调用都会改写为 GONE（兜底皮肤框架 / 未来版本新增的直接赋值）。
 *
 * 开关：分别受 {@link Prefs#K_HIDE_REWARD_VIDEO}、{@link Prefs#K_HIDE_INVITE_FRIENDS}、
 * {@link Prefs#K_HIDE_ACCOUNT_PROMO} 控制（均默认开启）；关闭后对应项不拦截，行为与未装模块一致。
 * 任何类 / 方法找不到时只记录日志并跳过，绝不影响 App 正常运行。
 */
final class MoreSettingsCleanHook {

    /** 「更多设置」页 Activity。 */
    private static final String MORE_ACTIVITY = "com.smartisan.notes.sync.MoreActivity";
    /** 「看视频免广告」行资源名。 */
    private static final String ID_AD_SWITCH = "ad_switch";
    /** 「邀请好友得会员」行资源名。 */
    private static final String ID_INVITATION_VIEW = "invitation_view";
    /** vip_desc 促销文案的资源名（浅色 / 深色两个变体，明文一致）。 */
    private static final String[] PROMO_STRING_NAMES = {
            "upgrade_vip_prompt",
            "upgrade_vip_prompt_dark",
    };

    /** id 解析缓存（name -> id）；0 表示尚未解析成功，下次再试。 */
    private static final Map<String, Integer> sIdCache = new ConcurrentHashMap<>();

    /** 促销文案明文集合（懒解析：upgrade_vip_prompt[_dark] 经 Html 去标签）。 */
    private static volatile Set<String> sPromoTexts;

    private MoreSettingsCleanHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> activity = findClassSafe(cl, MORE_ACTIVITY);
        if (activity == null) {
            return;
        }

        // 1) 页面创建 / 回到前台：invitation_view 布局默认可见，需主动隐藏
        hookAllSafe(activity, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hideEntries(param.thisObject);
            }
        });
        hookAllSafe(activity, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hideEntries(param.thisObject);
            }
        });

        // 2) ad_switch 的展示路径（VIP 状态异步回调 / 点击刷新都会经过这两个方法）
        hookAllSafe(activity, "ooOO", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hideEntries(param.thisObject);
            }
        });
        hookAllSafe(activity, "o0000oo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hideEntries(param.thisObject);
            }
        });

        // 3) vip_desc 促销文案清理（唯一文本设置入口）
        hookAllSafe(activity, "o00000oo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                clearAccountPromo(param.args);
            }
        });

        // 4) 全局守卫规则（见 ViewIdGuard）
        ViewIdGuard.addRule(ID_AD_SWITCH, Prefs.K_HIDE_REWARD_VIDEO);
        ViewIdGuard.addRule(ID_INVITATION_VIEW, Prefs.K_HIDE_INVITE_FRIENDS);

        XposedInit.log("MoreSettingsCleanHook installed.");
    }

    /** 按开关把两个入口行置 GONE（页面存在才处理）。 */
    private static void hideEntries(Object moreActivity) {
        if (!(moreActivity instanceof Activity)) {
            return;
        }
        Activity activity = (Activity) moreActivity;
        try {
            hideById(activity, ID_AD_SWITCH, Prefs.K_HIDE_REWARD_VIDEO);
            hideById(activity, ID_INVITATION_VIEW, Prefs.K_HIDE_INVITE_FRIENDS);
        } catch (Throwable t) {
            XposedInit.log("hideEntries failed: " + t);
        }
    }

    /** 隐藏单个入口行；开关关闭 / 视图不存在时不动。 */
    private static void hideById(Activity activity, String idName, String prefKey) {
        if (!Prefs.isEnabled(prefKey)) {
            return;
        }
        int id = resolveId(activity.getResources(), idName);
        if (id == 0) {
            return;
        }
        View view = activity.findViewById(id);
        if (view != null && view.getVisibility() != View.GONE) {
            view.setVisibility(View.GONE);
            XposedInit.log(idName + " hidden on MoreActivity");
        }
    }

    /**
     * 账户行 vip_desc 促销文案清理（o00000oo 后置）：
     * args = (TextView tvExtra, TextView vipDesc, VipInfo vipInfo)，
     * vipDesc 文本命中促销文案时清空；会员有效期 / 未登录提示等其它文案保持原样。
     */
    private static void clearAccountPromo(Object[] args) {
        if (!Prefs.isEnabled(Prefs.K_HIDE_ACCOUNT_PROMO)) {
            return;
        }
        try {
            if (args == null || args.length < 2 || !(args[1] instanceof TextView)) {
                return;
            }
            TextView desc = (TextView) args[1];
            CharSequence text = desc.getText();
            if (text == null || text.length() == 0) {
                return;
            }
            if (isPromoText(desc.getResources(), text.toString())) {
                desc.setText("");
                XposedInit.log("account promo text cleared (vip_desc)");
            }
        } catch (Throwable t) {
            XposedInit.log("clearAccountPromo failed: " + t);
        }
    }

    /** 文本是否命中促销文案（文案集合懒解析并缓存）。 */
    private static boolean isPromoText(Resources res, String text) {
        Set<String> promos = sPromoTexts;
        if (promos == null) {
            promos = resolvePromoTexts(res);
            sPromoTexts = promos;
        }
        return promos.contains(text.trim());
    }

    /** 从资源解析促销文案明文（upgrade_vip_prompt[_dark] 经 Html 去标签；失败时用内置兜底）。 */
    private static Set<String> resolvePromoTexts(Resources res) {
        Set<String> out = new HashSet<>();
        for (String name : PROMO_STRING_NAMES) {
            try {
                int id = res.getIdentifier(name, "string", XposedInit.TARGET_PKG);
                if (id != 0) {
                    out.add(Html.fromHtml(res.getString(id)).toString().trim());
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            // 资源解析失败时的兜底（4.3.1 实际文案）
            out.add("开通会员，尊享全部权益");
        }
        XposedInit.log("promo texts resolved: " + out);
        return Collections.unmodifiableSet(out);
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
