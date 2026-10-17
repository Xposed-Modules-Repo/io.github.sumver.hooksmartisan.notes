package com.sumver.hammernotes;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.util.Base64;
import android.util.DisplayMetrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「隐藏便签图片页脚」Hook（锤子便签 4.3.1 / versionCode 43100，默认开启）。
 *
 * 1.8 起改为「内置素材整图替换」：不再做任何画布修补/像素遮盖，而是在资源加载处
 * 把目标 drawable 直接换成模块内置的「去页脚」版本（见 {@link FooterAssets}）。
 *
 * 覆盖的 4 个目标：
 *   · weibo_bg_preview / weibo_from_other_bg_preview —— 预览页背景（用户提供的去页脚素材）；
 *   · weibo_background —— 生成图 / 分享图容器背景（模块生成：页脚带填为纸面色）；
 *     weibo_background_nosignature 与其仅页脚文字不同（去页脚后逐像素一致），复用同一素材。
 *
 * 为什么类型安全（吸取 1.4 闪退教训）：
 *   k06 等处的字段/参数是具体类型 NinePatchDrawable，本模块替换用的是**真正的**
 *   NinePatchDrawable（由「素材位图 + 原始 9-patch chunk」构造，几何/密度/padding 与
 *   官方资源完全一致，仅页脚像素不同），任何 check-cast 均安全。
 *
 * 注入点（覆盖全部 drawable 加载路径）：
 *   ① Resources.getDrawableForDensity(int,int,Theme)——代码路径汇点
 *      （Context.getDrawable / ContextCompat / View.setBackgroundResource / getResources().getDrawable）；
 *   ② TypedArray.getDrawable(int) 与 TypedArray.getDrawableForDensity(int,int)——XML 路径
 *      （layout_note_pic、share_picture_preview_layout 的 android:background）。
 *
 * 任何一步失败（id 缺失、解码失败、构造异常）只记录日志并保持原样，不影响 APP 运行。
 * 开关：{@link Prefs#K_HIDE_FOOTER}（默认开启），关闭后完全不拦截。
 */
final class HideFooterHook {

    /** 目标资源 id → 素材索引（懒解析；成功解析前不做任何替换）。 */
    private static final Map<Integer, Integer> sIdToIndex = new HashMap<>();
    private static volatile boolean sIdsResolved;
    /** 已替换出的 drawable 实例（同一次加载可能经过两层钩子，防重复替换）。 */
    private static final Set<Drawable> sOurs = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Drawable, Boolean>()));
    /** 去页脚位图缓存（每进程不超过 3 张）。 */
    private static final Bitmap[] sBitmaps = new Bitmap[FooterAssets.NAMES.length];
    /** 「已替换」日志只输出一次（按素材名）。 */
    private static final Set<String> sLoggedNames = Collections.synchronizedSet(new HashSet<String>());
    private static final Object sLock = new Object();
    private static volatile boolean sWarnLogged;
    private static volatile Resources sAppResources;

    private HideFooterHook() {
    }

    static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getDrawableForDensity",
                    int.class, int.class, Resources.Theme.class, new ResourcesHook());
            XposedInit.log("hooked Resources.getDrawableForDensity(int,int,Theme)");
        } catch (Throwable t) {
            XposedInit.log("hook failed: Resources.getDrawableForDensity " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(TypedArray.class, "getDrawableForDensity",
                    int.class, int.class, new TypedArrayHook());
            XposedInit.log("hooked TypedArray.getDrawableForDensity(int,int)");
        } catch (Throwable t) {
            XposedInit.log("hook failed: TypedArray.getDrawableForDensity " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(TypedArray.class, "getDrawable",
                    int.class, new TypedArrayHook());
            XposedInit.log("hooked TypedArray.getDrawable(int)");
        } catch (Throwable t) {
            XposedInit.log("hook failed: TypedArray.getDrawable " + t);
        }
        XposedInit.log("HideFooterHook installed (built-in footer-less drawables).");
    }

    /* ==================== 钩子实现 ==================== */

    private static final class ResourcesHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!Prefs.isEnabled(Prefs.K_HIDE_FOOTER)) {
                    return;
                }
                int id = (Integer) param.args[0];
                replaceResult(param, id, (Resources) param.thisObject);
            } catch (Throwable t) {
                warnOnce("replace (Resources) failed: " + t);
            }
        }
    }

    private static final class TypedArrayHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!Prefs.isEnabled(Prefs.K_HIDE_FOOTER)) {
                    return;
                }
                TypedArray typedArray = (TypedArray) param.thisObject;
                int index = (Integer) param.args[0];
                int id = typedArray.getResourceId(index, 0);
                if (id == 0) {
                    return; // 非资源值（或未定义）
                }
                replaceResult(param, id, appResources());
            } catch (Throwable t) {
                warnOnce("replace (TypedArray) failed: " + t);
            }
        }
    }

    private static void replaceResult(XC_MethodHook.MethodHookParam param, int id, Resources res) {
        if (id == 0 || res == null) {
            return;
        }
        Object result = param.getResult();
        if (!(result instanceof Drawable) || sOurs.contains(result)) {
            return;
        }
        Drawable replacement = replacementFor(id, res);
        if (replacement != null) {
            param.setResult(replacement);
        }
    }

    /* ==================== 替换对象构建 ==================== */

    private static Drawable replacementFor(int id, Resources res) {
        ensureIds(res);
        Integer indexBox = sIdToIndex.get(id);
        if (indexBox == null) {
            return null;
        }
        int index = indexBox;
        Bitmap bitmap = obtainBitmap(index);
        if (bitmap == null) {
            return null;
        }
        try {
            byte[] chunk = Base64.decode(FooterAssets.CHUNK_B64[index], Base64.DEFAULT);
            int[] padding = FooterAssets.PADDING[index];
            NinePatchDrawable drawable = new NinePatchDrawable(res, bitmap, chunk,
                    new Rect(padding[0], padding[1], padding[2], padding[3]),
                    FooterAssets.NAMES[index]);
            sOurs.add(drawable);
            logOnce("replaced: " + FooterAssets.NAMES[index]);
            return drawable;
        } catch (Throwable t) {
            warnOnce("NinePatchDrawable build failed (" + FooterAssets.NAMES[index] + "): " + t);
            return null;
        }
    }

    private static Bitmap obtainBitmap(int index) {
        synchronized (sLock) {
            Bitmap cached = sBitmaps[index];
            if (cached != null) {
                return cached;
            }
            try {
                byte[] png = Base64.decode(FooterAssets.PNG_B64[index], Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bitmap == null) {
                    return null;
                }
                // 与编译资源目录 drawable-xxhdpi 的源密度一致（真机同为 xxhdpi，1:1 渲染）
                bitmap.setDensity(DisplayMetrics.DENSITY_XXHIGH);
                sBitmaps[index] = bitmap;
                return bitmap;
            } catch (Throwable t) {
                warnOnce("decode footer asset failed: " + t);
                return null;
            }
        }
    }

    private static void ensureIds(Resources res) {
        if (sIdsResolved) {
            return;
        }
        synchronized (sLock) {
            if (sIdsResolved) {
                return;
            }
            int found = 0;
            for (int i = 0; i < FooterAssets.NAMES.length; i++) {
                int id = res.getIdentifier(FooterAssets.NAMES[i], "drawable", XposedInit.TARGET_PKG);
                if (id != 0) {
                    sIdToIndex.put(id, i);
                    found++;
                } else {
                    XposedInit.log("HideFooterHook: drawable not found: " + FooterAssets.NAMES[i]);
                }
            }
            // 无署名版与署名版仅页脚文字不同（去页脚后逐像素一致），复用同一素材。
            int nosignature = res.getIdentifier("weibo_background_nosignature", "drawable", XposedInit.TARGET_PKG);
            if (nosignature != 0) {
                sIdToIndex.put(nosignature, 2);
                found++;
            }
            sIdsResolved = true;
            XposedInit.log("HideFooterHook: resolved " + found + " footer drawable id(s).");
        }
    }

    private static Resources appResources() {
        Resources res = sAppResources;
        if (res != null) {
            return res;
        }
        try {
            Application application = AndroidAppHelper.currentApplication();
            if (application != null) {
                res = application.getResources();
                sAppResources = res;
            }
        } catch (Throwable ignored) {
        }
        return res;
    }

    /* ==================== 日志 ==================== */

    private static void logOnce(String message) {
        if (sLoggedNames.add(message)) {
            XposedInit.log("HideFooterHook: " + message);
        }
    }

    private static void warnOnce(String message) {
        if (sWarnLogged) {
            return;
        }
        sWarnLogged = true;
        XposedInit.log("HideFooterHook: " + message);
    }
}
