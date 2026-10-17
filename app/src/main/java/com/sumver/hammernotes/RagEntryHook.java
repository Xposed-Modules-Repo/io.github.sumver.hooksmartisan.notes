package com.sumver.hammernotes;

import android.content.res.Resources;
import android.view.View;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 便签列表（主页）标题栏「知识库」入口隐藏 Hook（锤子便签 4.3.1 / versionCode 43100），
 * 结论来自对 4.3.1 APK 的反编译核实。
 *
 * <pre>
 * 目标控件：rag_button（0x7f09063a，FrameLayout）—— 便签列表标题栏右侧、
 *   "+"（new_note_button）左侧的知识库按钮（内含 ai_rag_icon 图标与
 *   ic_rag_red_dot 红点角标），布局 res/layout/notes_titlebar.xml。
 *
 * 宿主与显隐机制（com.smartisanos.notes.widget 包）：
 *   notes_titlebar.xml 由 DefaultNotesTitleBar 构造函数 inflate（rag_button 无
 *   visibility 属性 → 默认可见，由状态机接管）；
 *   IActionBar.ALL_BUTTONS 含 ButtonID.RAG；
 *   IActionBar#updateSelf 按状态取 getShowButtons(State)（列表态含 RAG）
 *   → IActionBar#setBtnVisibleInternal(ButtonID, boolean, Animation)
 *     → findBtnById(RAG) == mRAGButton → setVisibility(VISIBLE/GONE)；
 *   状态切换/进入走 IActionBar#enterState → updateSelf，动画路径走 TitleBarAnimator，
 *   最终同样落到 setBtnVisibleInternal。
 *
 * 拦截方式（多重保险）：
 *   1) DefaultNotesTitleBar 构造函数之后：inflate 完立即把 rag_button 置 GONE
 *      （布局默认可见，需要主动隐藏，避免首帧残留）；
 *   2) IActionBar#setBtnVisibleInternal 前置：ButtonID 为 RAG 时强制 visible=false
 *      （App 任何"显示知识库按钮"的判定都会被改写为隐藏，含状态切换与动画路径）；
 *   3) IActionBar#enterState 之后：再次主动隐藏（幂等兜底）；
 *   4) ViewIdGuard 注册 rag_button 规则：任何把该控件设为可见的调用改写为 GONE。
 *
 * 开关：受 {@link Prefs#K_HIDE_RAG_ENTRY} 控制（默认开启）；关闭后不拦截，行为与未装模块一致。
 * 任何类 / 方法找不到时只记录日志并跳过，绝不影响 App 正常运行。
 */
final class RagEntryHook {

    /** 标题栏基类（按钮显隐状态机所在类）。 */
    private static final String ACTION_BAR = "com.smartisanos.notes.widget.IActionBar";
    /** 默认标题栏实现（构造函数里 inflate notes_titlebar.xml）。 */
    private static final String DEFAULT_TITLE_BAR =
            "com.smartisanos.notes.widget.DefaultNotesTitleBar";
    /** 知识库按钮资源名。 */
    private static final String ID_RAG_BUTTON = "rag_button";
    /** 知识库按钮的枚举名（IActionBar$ButtonID.RAG）。 */
    private static final String BTN_RAG = "RAG";

    /** 类缺失日志只输出一次（避免刷屏）。 */
    private static final Map<String, Boolean> sMissingClasses = new ConcurrentHashMap<>();

    /** rag_button 资源 id（懒解析；0 = 尚未解析成功）。 */
    private static volatile int sRagButtonId;

    private RagEntryHook() {
    }

    static void install(ClassLoader cl) {
        installTitleBarHooks(cl);
        installActionBarHooks(cl);
        // 全局守卫兜底
        ViewIdGuard.addRule(ID_RAG_BUTTON, Prefs.K_HIDE_RAG_ENTRY);
        XposedInit.log("RagEntryHook installed.");
    }

    /** 1) 标题栏构造完成后立即隐藏（布局默认可见）。 */
    private static void installTitleBarHooks(ClassLoader cl) {
        Class<?> titleBar = findClassSafe(cl, DEFAULT_TITLE_BAR);
        if (titleBar == null) {
            return;
        }
        // 注意：hookAllMethods 不含构造函数（getDeclaredMethods 不返回 <init>），
        // 必须用 hookAllConstructors
        try {
            XposedBridge.hookAllConstructors(titleBar, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Prefs.isEnabled(Prefs.K_HIDE_RAG_ENTRY)) {
                        return;
                    }
                    if (param.thisObject instanceof View) {
                        hideRagButton((View) param.thisObject);
                    }
                }
            });
            XposedInit.log("hooked " + titleBar.getSimpleName() + ".<init> (all constructors)");
        } catch (Throwable t) {
            XposedInit.log("hook failed: " + titleBar.getSimpleName() + ".<init> " + t);
        }
    }

    /** 2) + 3) 状态机拦截：强制 RAG 按钮不可见，并在状态切换后复查。 */
    private static void installActionBarHooks(ClassLoader cl) {
        Class<?> actionBar = findClassSafe(cl, ACTION_BAR);
        if (actionBar == null) {
            return;
        }
        hookAllSafe(actionBar, "setBtnVisibleInternal", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Prefs.isEnabled(Prefs.K_HIDE_RAG_ENTRY)) {
                    return;
                }
                try {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    Object btnId = param.args[0];
                    if (btnId instanceof Enum && BTN_RAG.equals(((Enum<?>) btnId).name())) {
                        // 任何"显示 RAG 按钮"的调用都改写为隐藏
                        param.args[1] = Boolean.FALSE;
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        hookAllSafe(actionBar, "enterState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Prefs.isEnabled(Prefs.K_HIDE_RAG_ENTRY)) {
                    return;
                }
                if (param.thisObject instanceof View) {
                    hideRagButton((View) param.thisObject);
                }
            }
        });
    }

    /** 把标题栏里的知识库按钮置 GONE（幂等）。 */
    private static void hideRagButton(View titleBar) {
        try {
            if (sRagButtonId == 0) {
                resolveRagButtonId(titleBar.getResources());
            }
            if (sRagButtonId == 0) {
                return;
            }
            View button = titleBar.findViewById(sRagButtonId);
            if (button != null && button.getVisibility() != View.GONE) {
                button.setVisibility(View.GONE);
                XposedInit.log("rag_button (知识库入口) hidden on title bar");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 运行时解析 rag_button 的资源 id。 */
    private static void resolveRagButtonId(Resources res) {
        try {
            int id = res.getIdentifier(ID_RAG_BUTTON, "id", XposedInit.TARGET_PKG);
            if (id != 0) {
                sRagButtonId = id;
                XposedInit.log(ID_RAG_BUTTON + " id resolved: " + Integer.toHexString(id));
            }
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
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
