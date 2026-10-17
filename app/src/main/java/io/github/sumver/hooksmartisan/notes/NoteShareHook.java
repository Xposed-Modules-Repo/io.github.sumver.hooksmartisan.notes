package io.github.sumver.hooksmartisan.notes;

import android.app.AndroidAppHelper;
import android.content.res.Resources;

import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 便签分享净化 Hook（锤子便签 4.3.1 / versionCode 43100），结论来自对 4.3.1 APK 的反编译核实。
 *
 * <pre>
 * 一、发送/分享弹窗（"发送"按钮点击后弹出的操作窗口）
 *   入口：便签页标题栏 share_button → com.smartisanos.notes.v2.detail.DetailFragment 等
 *        → of8（源文件 ShareHelper.java）→ private OooOoo0(View, x56, List)
 *          → 构建 NotesActionMenu$ActionItem 列表（标题为字符串资源 id）
 *          → NotesActionMenu#addActionItem(List) → 弹窗展示
 *
 *   弹窗条目（of8.OooOoo0 逐一核实，smali 1265-1635）：
 *     share_link_to_sina_long_weibo（0x7f130601「分享至新浪长微博」，受 isShowLongWeibo 开关）
 *     share_dialog_copy_text（「复制文字」）
 *     share_dialog_send_with_text（「以文字形式分享」）
 *     share_dialog_send_with_img（「以图片形式分享」）
 *     share_dialog_send_via_email（0x7f1305f1「发送邮件」）
 *     share_dialog_import_knb（0x7f1305ec「导入到知识库」）
 *
 *   实现：Hook NotesActionMenu#addActionItem（两个重载），按条目标题资源 id 过滤：
 *     标题命中「导入到知识库 / 分享至新浪长微博 / 发送邮件」且对应开关开启时，
 *     从列表里移除（或对单条重载直接跳过添加）。
 *   标题资源 id 运行时 getIdentifier 解析（不硬编码编号）；
 *   NotesActionMenu$ActionItem 的字段 title(int) 即标题字符串资源 id。
 *
 * 二、敏感词检测（"总是允许分享"）
 *   链路（以"以文字形式分享"为例）：
 *     点击条目 → of8#OooOOOo(OnCheckCallback, String)
 *       → 显示进度框（of8#OooOooO）→ SensitiveWordManager#checkSensitiveWords(context, callback, text)
 *         → 本地词库 / 在线接口检测 → callback.onSuccess()（通过）或 onFailure()（弹"不适合公开发表"提示）
 *   另：列表长按拖拽分享（DragSortController#startDragItemData）同样走 checkSensitiveWords，
 *       onFailure 时提示相同 toast 并阻止拖拽生成分享图。
 *
 *   实现：Hook SensitiveWordManager#checkSensitiveWords 前置——开关开启时直接调用
 *   callback.onSuccess()（等价于"未检测到敏感词"），并跳过真实检测；
 *   onSuccess 内部会关闭进度框并继续正常的分享流程。
 *   调用 onSuccess 失败时（异常）不拦截，回退原始检测，避免界面卡死。
 *
 * 开关：分别受 {@link Prefs#K_HIDE_SHARE_IMPORT_KNB}、{@link Prefs#K_HIDE_SHARE_LONG_WEIBO}、
 * {@link Prefs#K_HIDE_SHARE_EMAIL}、{@link Prefs#K_ALLOW_SENSITIVE_SHARE} 控制（均默认开启）；
 * 关闭后对应项不拦截，行为与未装模块一致。
 * 任何类 / 方法找不到时只记录日志并跳过，绝不影响 App 正常运行。
 */
final class NoteShareHook {

    /** 操作菜单类（弹窗构建）。 */
    private static final String ACTION_MENU = "com.smartisanos.notes.widget.NotesActionMenu";
    /** 敏感词管理类。 */
    private static final String SENSITIVE_MANAGER =
            "com.smartisanos.notes.widget.SensitiveWordManager";

    /** 分享弹窗条目规则：{ 字符串资源名, 开关 key }（标题命中且开关开启 → 移除）。 */
    private static final String[][] SHARE_ITEM_RULES = {
            {"share_dialog_import_knb", Prefs.K_HIDE_SHARE_IMPORT_KNB},
            {"share_link_to_sina_long_weibo", Prefs.K_HIDE_SHARE_LONG_WEIBO},
            {"share_dialog_send_via_email", Prefs.K_HIDE_SHARE_EMAIL},
    };

    /** 解析后的标题资源 id（与 SHARE_ITEM_RULES 一一对应；0 = 尚未解析成功）。 */
    private static volatile int[] sShareItemIds;

    private NoteShareHook() {
    }

    static void install(ClassLoader cl) {
        // 一、分享弹窗条目过滤
        Class<?> menu = findClassSafe(cl, ACTION_MENU);
        if (menu != null) {
            hookAllSafe(menu, "addActionItem", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    filterActionItem(param);
                }
            });
        }

        // 二、敏感词检测跳过（总是允许分享）
        Class<?> manager = findClassSafe(cl, SENSITIVE_MANAGER);
        if (manager != null) {
            hookAllSafe(manager, "checkSensitiveWords", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    bypassSensitiveCheck(param);
                }
            });
        }

        XposedInit.log("NoteShareHook installed.");
    }

    /* ==================== 一、分享弹窗条目过滤 ==================== */

    /**
     * addActionItem 两个重载：
     *   addActionItem(List)：逐条检查并从列表移除命中的条目；
     *   addActionItem(int titleRes, OnClickListener)：命中则直接跳过添加。
     */
    private static void filterActionItem(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args == null || param.args.length == 0) {
                return;
            }
            Object first = param.args[0];
            if (first instanceof List) {
                removeMatchedItems((List<?>) first);
            } else if (first instanceof Integer && param.args.length >= 2) {
                // 单条重载：标题命中且开关开启 → 跳过
                int title = (Integer) first;
                String key = matchedKey(title);
                if (key != null) {
                    XposedInit.log("share item skipped: " + key);
                    param.setResult(null);
                }
            }
        } catch (Throwable t) {
            XposedInit.log("filterActionItem failed: " + t);
        }
    }

    /** 从 ActionItem 列表中移除命中规则的条目。 */
    private static void removeMatchedItems(List<?> items) {
        if (items.isEmpty()) {
            return;
        }
        Iterator<?> it = items.iterator();
        while (it.hasNext()) {
            Object item = it.next();
            if (item == null) {
                continue;
            }
            int title;
            try {
                title = XposedHelpers.getIntField(item, "title");
            } catch (Throwable t) {
                continue; // 非 ActionItem 或字段变化：跳过
            }
            String key = matchedKey(title);
            if (key != null) {
                it.remove();
                XposedInit.log("share item removed: " + key);
            }
        }
    }

    /** 标题资源 id 是否命中开启中的规则；命中返回对应开关 key，否则 null。 */
    private static String matchedKey(int titleResId) {
        if (titleResId == 0) {
            return null;
        }
        int[] ids = sShareItemIds;
        if (ids == null) {
            ids = resolveShareItemIds();
            // 仅当至少解析出一个 id 时才缓存；全部失败则下次重试
            if (hasAnyId(ids)) {
                sShareItemIds = ids;
            }
        }
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != 0 && ids[i] == titleResId
                    && Prefs.isEnabled(SHARE_ITEM_RULES[i][1])) {
                return SHARE_ITEM_RULES[i][0];
            }
        }
        return null;
    }

    private static boolean hasAnyId(int[] ids) {
        for (int id : ids) {
            if (id != 0) {
                return true;
            }
        }
        return false;
    }

    /** 运行时解析三个条目标题的字符串资源 id。 */
    private static int[] resolveShareItemIds() {
        int[] ids = new int[SHARE_ITEM_RULES.length];
        try {
            Resources res = AndroidAppHelper.currentApplication().getResources();
            for (int i = 0; i < SHARE_ITEM_RULES.length; i++) {
                ids[i] = res.getIdentifier(SHARE_ITEM_RULES[i][0], "string",
                        XposedInit.TARGET_PKG);
            }
            XposedInit.log("share item ids resolved: "
                    + ids[0] + ", " + ids[1] + ", " + ids[2]);
        } catch (Throwable t) {
            XposedInit.log("resolve share item ids failed: " + t);
        }
        return ids;
    }

    /* ==================== 二、敏感词检测跳过 ==================== */

    /**
     * checkSensitiveWords(Context, OnCheckCallback, String) 前置：
     * 开关开启时直接回调 onSuccess()（= 未检测到敏感词），跳过真实检测。
     * onSuccess 调用失败时不拦截，回退原始检测。
     */
    private static void bypassSensitiveCheck(XC_MethodHook.MethodHookParam param) {
        if (!Prefs.isEnabled(Prefs.K_ALLOW_SENSITIVE_SHARE)) {
            return;
        }
        try {
            if (param.args == null || param.args.length < 2 || param.args[1] == null) {
                return;
            }
            // 等价于"检测通过"：回调内部会关闭进度框并继续正常分享流程
            XposedHelpers.callMethod(param.args[1], "onSuccess");
            param.setResult(null);
            XposedInit.log("sensitive check bypassed (share allowed)");
        } catch (Throwable t) {
            // 回调执行异常：不拦截，回退原始检测，避免界面卡死
            XposedInit.log("sensitive bypass failed, fallback to original check: " + t);
        }
    }

    /* ==================== 工具 ==================== */

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
