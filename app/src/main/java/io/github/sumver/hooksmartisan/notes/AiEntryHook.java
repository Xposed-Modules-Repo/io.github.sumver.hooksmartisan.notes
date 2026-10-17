package io.github.sumver.hooksmartisan.notes;

import android.content.res.Resources;
import android.view.View;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 便签查看/编辑页「AI 入口」隐藏 Hook（锤子便签 4.3.1 / versionCode 43100），
 * 结论来自对 4.3.1 APK 的反编译核实。
 *
 * <pre>
 * 目标控件：ai_button（0x7f090081，NotesButton）—— 便签页标题栏的 AI 入口按钮
 *   （图标 btn_ai），布局 res/layout/notes_titlebar.xml（该布局中 XML 默认 visibility=gone）。
 *
 * 显隐机制（com.smartisanos.notes.widget 包）：
 *   IActionBar$State 状态：INIT=0 / LIST=1 / DETAIL_PREVIEW=2 / DETAIL_EDIT=3 / SEARCH_LIST=4
 *   IActionBar#getShowButtons：
 *     DETAIL_PREVIEW(2)（查看便签） 按钮 = BACK, AI, DELETE_NOTE, SHARE        ← 含 AI
 *     DETAIL_EDIT(3)  （新增/编辑） 按钮 = BACK, UNDO, REDO, AI, INSERT_IMAGE, EDIT_DONE ← 含 AI
 *   进入状态/状态切换：NotesActivity / DetailFragment → IActionBar#enterState
 *     → updateSelf → IActionBar#setBtnVisibleInternal(ButtonID, boolean, Animation)
 *       → findBtnById(ButtonID.AI) → DefaultNotesTitleBar.mAIButton → setVisibility(VISIBLE/GONE)
 *   （动画路径 IActionBar$TitleBarAnimator 同样落到 setBtnVisibleInternal）
 *
 * 拦截方式（多重保险）：
 *   1) IActionBar#setBtnVisibleInternal 前置：ButtonID 为 AI 时强制 visible=false
 *      （App 任何"显示 AI 按钮"的判定都被改写为隐藏，含状态切换与动画路径）；
 *   2) ViewIdGuard 注册 ai_button 规则：任何把该控件设为可见的调用改写为 GONE
 *      （ai_button 仅出现在 notes_titlebar.xml，无同名冲突）。
 *
 * 开关：受 {@link Prefs#K_HIDE_AI_ENTRY} 控制（默认开启）；关闭后不拦截，行为与未装模块一致。
 * 任何类 / 方法找不到时只记录日志并跳过，绝不影响 App 正常运行。
 */
final class AiEntryHook {

    /** 标题栏基类（按钮显隐状态机所在类）。 */
    private static final String ACTION_BAR = "com.smartisanos.notes.widget.IActionBar";
    /** AI 按钮资源名。 */
    private static final String ID_AI_BUTTON = "ai_button";
    /** AI 按钮的枚举名（IActionBar$ButtonID.AI）。 */
    private static final String BTN_AI = "AI";

    /** 类缺失日志只输出一次（避免刷屏）。 */
    private static final Map<String, Boolean> sMissingClasses = new ConcurrentHashMap<>();

    private AiEntryHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> actionBar = findClassSafe(cl, ACTION_BAR);
        if (actionBar == null) {
            return;
        }
        hookAllSafe(actionBar, "setBtnVisibleInternal", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Prefs.isEnabled(Prefs.K_HIDE_AI_ENTRY)) {
                    return;
                }
                try {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    Object btnId = param.args[0];
                    if (btnId instanceof Enum && BTN_AI.equals(((Enum<?>) btnId).name())) {
                        // 任何"显示 AI 按钮"的调用都改写为隐藏
                        param.args[1] = Boolean.FALSE;
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        // 全局守卫兜底（xml 默认 gone，此规则防御未来版本 / 其他直接赋值路径）
        ViewIdGuard.addRule(ID_AI_BUTTON, Prefs.K_HIDE_AI_ENTRY);

        XposedInit.log("AiEntryHook installed.");
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
