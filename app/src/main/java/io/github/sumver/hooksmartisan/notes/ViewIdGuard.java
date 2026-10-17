package io.github.sumver.hooksmartisan.notes;

import android.content.res.Resources;
import android.view.View;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 通用「按资源 id 隐藏控件」守卫（模块内多功能的公共设施）。
 *
 * 各净化功能都用同一套兜底逻辑：开关开启时，任何把目标控件设为非 GONE 的调用
 * 都被改写为 GONE（覆盖异步回调、皮肤框架、动画等任意时机的 setVisibility）。
 *
 * 由于 {@code View#setVisibility} 调用频繁，这里只安装**一个**钩子，
 * 各功能通过 {@link #addRule} 注册 (资源名, 开关 key) 规则，避免每个功能各挂一个钩子。
 * 资源 id 运行时 getIdentifier 解析并缓存（不硬编码编号）；规则里开关关闭时该规则不参与拦截。
 */
final class ViewIdGuard {

    /** 一条拦截规则：目标控件资源名 + 控制它的开关 key。 */
    private static final class Rule {
        final String idName;
        final String prefKey;
        /** 已解析的资源 id；0 = 尚未解析成功（下次再试）。 */
        volatile int id;

        Rule(String idName, String prefKey) {
            this.idName = idName;
            this.prefKey = prefKey;
        }
    }

    private static final List<Rule> sRules = new CopyOnWriteArrayList<>();
    private static volatile boolean sInstalled;

    private ViewIdGuard() {
    }

    /** 注册一条拦截规则（须在 {@link #install} 之前或之后均可，钩子读取的是实时列表）。 */
    static void addRule(String idName, String prefKey) {
        sRules.add(new Rule(idName, prefKey));
    }

    /** 安装唯一的 View.setVisibility 钩子（幂等）。 */
    static synchronized void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) {
                    return;
                }
                View v = (View) param.thisObject;
                int want = (Integer) param.args[0];
                if (want == View.GONE) {
                    return; // 本来就是隐藏，放行
                }
                int id = v.getId();
                if (id == 0) {
                    return;
                }
                Resources res = null;
                for (Rule rule : sRules) {
                    if (!Prefs.isEnabled(rule.prefKey)) {
                        continue; // 该功能未开启：不拦截
                    }
                    if (rule.id == 0) {
                        if (res == null) {
                            try {
                                res = v.getResources();
                            } catch (Throwable ignored) {
                                return;
                            }
                        }
                        resolveId(res, rule);
                    }
                    if (rule.id != 0 && rule.id == id) {
                        param.args[0] = View.GONE;
                        return;
                    }
                }
            }
        });
        XposedInit.log("ViewIdGuard installed (" + sRules.size() + " rule(s) registered)");
    }

    /** 运行时解析资源 id（name -> id，写入 rule 缓存；失败保持 0，下次再试）。 */
    private static void resolveId(Resources res, Rule rule) {
        try {
            int id = res.getIdentifier(rule.idName, "id", XposedInit.TARGET_PKG);
            if (id != 0) {
                rule.id = id;
                XposedInit.log(rule.idName + " id resolved: " + Integer.toHexString(id));
            }
        } catch (Throwable ignored) {
        }
    }
}
