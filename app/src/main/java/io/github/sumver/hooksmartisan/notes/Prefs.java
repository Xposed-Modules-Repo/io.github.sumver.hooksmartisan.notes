package io.github.sumver.hooksmartisan.notes;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 功能开关配置（模块设置界面 ↔ Hook 进程的桥梁）。
 *
 * 通道协议（替代 LSPosed 已废弃的「New XSharedPreferences」）：
 *  - 模块 App 进程：设置界面以 {@link Context#MODE_PRIVATE} 正常读写偏好文件（settings），
 *    并通过只读 ContentProvider（{@link PrefsProvider}，authority 见 {@link #PROVIDER_AUTHORITY}）
 *    把 Boolean 开关暴露给外部查询；
 *  - Hook（锤子便签）进程：启动后**异步**经 ContentResolver 查询
 *    content://io.github.sumver.hooksmartisan.notes.prefs/settings（首次查询会拉起模块 App 进程），
 *    成功后把整份配置缓存到进程内存；查询期间 / 查询失败时按内置默认值行事
 *    （全部开关默认开启），不阻塞便签启动。
 *  - 读取策略：**每个进程只加载一次**。因此开关修改后必须重启（强制停止）便签才生效
 *    —— 刻意设计，避免运行中状态错乱。
 */
final class Prefs {

    /** 与 app/build.gradle 的 applicationId 保持一致（不依赖 BuildConfig 的开关）。 */
    static final String MODULE_PKG = "io.github.sumver.hooksmartisan.notes";
    /** 设置界面与 Hook 共用同一偏好文件名。 */
    static final String FILE = "settings";

    /**
     * 配置桥（只读 ContentProvider）authority：= applicationId + ".prefs"。
     * 必须与 AndroidManifest.xml 中 provider 的 android:authorities 完全一致。
     */
    static final String PROVIDER_AUTHORITY = MODULE_PKG + ".prefs";
    /** Hook 进程查询用的 URI。 */
    static final Uri PROVIDER_URI = Uri.parse("content://" + PROVIDER_AUTHORITY + "/" + FILE);
    /** 游标列名（与 {@link PrefsProvider} 约定）。 */
    static final String COLUMN_KEY = "key";
    static final String COLUMN_VALUE = "value";

    // ---- 开关 key（设置界面 / Hook 共用）----
    /** 去除开屏广告（冷启动 + 热启动插屏）。 */
    static final String K_SPLASH_AD = "splash_ad";
    /** 隐藏开屏页「免广告·VIP」购买按钮（已停用：1.9 起移除该功能，代码保留备查）。 */
    // static final String K_SPLASH_VIP = "splash_vip";
    /** 隐藏「更多设置」页的「看视频免广告」入口。 */
    static final String K_HIDE_REWARD_VIDEO = "hide_reward_video";
    /** 隐藏「更多设置」页的「邀请好友得会员」入口。 */
    static final String K_HIDE_INVITE_FRIENDS = "hide_invite_friends";
    /** 隐藏「更多设置」页账户行下方的「开通会员，尊享全部权益」文案。 */
    static final String K_HIDE_ACCOUNT_PROMO = "hide_account_promo";
    /** 隐藏个人中心页的「便签会员」卡片（含"立即开通"按钮）。 */
    static final String K_HIDE_VIP_CARD = "hide_vip_card";
    /** 隐藏便签列表标题栏右侧的「知识库」入口。 */
    static final String K_HIDE_RAG_ENTRY = "hide_rag_entry";
    /** 隐藏便签查看/编辑页标题栏的 AI 入口。 */
    static final String K_HIDE_AI_ENTRY = "hide_ai_entry";
    /** 分享弹窗：隐藏「导入到知识库」。 */
    static final String K_HIDE_SHARE_IMPORT_KNB = "hide_share_import_knb";
    /** 分享弹窗：隐藏「分享至新浪长微博」。 */
    static final String K_HIDE_SHARE_LONG_WEIBO = "hide_share_long_weibo";
    /** 分享弹窗：隐藏「发送邮件」。 */
    static final String K_HIDE_SHARE_EMAIL = "hide_share_email";
    /** 总是允许分享（跳过敏感词检测）。 */
    static final String K_ALLOW_SENSITIVE_SHARE = "allow_sensitive_share";
    /** 隐藏便签图片页脚。 */
    static final String K_HIDE_FOOTER = "hide_footer";

    /** 全部开关 key（设置界面用于写默认值）。 */
    static String[] allKeys() {
        return new String[]{
                K_SPLASH_AD,
                // K_SPLASH_VIP,  // 已停用：隐藏开屏页「免广告·VIP」按钮（见 K_SPLASH_VIP 注释）
                K_HIDE_REWARD_VIDEO,
                K_HIDE_INVITE_FRIENDS,
                K_HIDE_ACCOUNT_PROMO,
                K_HIDE_VIP_CARD,
                K_HIDE_RAG_ENTRY,
                K_HIDE_AI_ENTRY,
                K_HIDE_SHARE_IMPORT_KNB,
                K_HIDE_SHARE_LONG_WEIBO,
                K_HIDE_SHARE_EMAIL,
                K_ALLOW_SENSITIVE_SHARE,
                K_HIDE_FOOTER,
        };
    }

    /** 未配置时的默认值：全部开关默认开启（原「隐藏 VIP 按钮」默认关闭项已随功能移除）。 */
    private static final Set<String> DEFAULT_OFF = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // K_SPLASH_VIP,  // 已停用：隐藏开屏页「免广告·VIP」按钮
            )));

    /** 开关的默认值（设置界面写入默认值、Hook 侧读取回退共用）。 */
    static boolean defaultOf(String key) {
        return !DEFAULT_OFF.contains(key);
    }

    /** Hook 进程的配置快照；null = 尚未加载完成（此时按默认值行事）。 */
    private static volatile Map<String, Boolean> sSnapshot;

    private static final Object sLoadLock = new Object();
    /** 是否已发起加载（每个进程只发起一轮；失败会固定为空快照，不再重试）。 */
    private static boolean sLoadStarted;
    /** provider 错误日志只输出一次（避免刷屏）。 */
    private static boolean sProviderErrorLogged;

    /** 等待目标应用 Application 创建的上限与轮询间隔（毫秒）。 */
    private static final long CONTEXT_WAIT_MS = 1500L;
    private static final long CONTEXT_POLL_MS = 50L;
    /** provider 查询的最大尝试次数。 */
    private static final int MAX_LOAD_ATTEMPTS = 3;

    private Prefs() {
    }

    /**
     * 模块 App 进程使用：读写设置文件（MODE_PRIVATE）。
     * 偏好文件即 {@link PrefsProvider} 对外暴露的数据源，无需任何跨进程权限处理。
     */
    static SharedPreferences ui(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Hook 进程使用：开关是否开启（快照未就绪 / 读取失败 = 内置默认值）。
     * 不阻塞：首次调用会发起异步加载，快照就绪后各次调用即返回真实配置。
     */
    static boolean isEnabled(String key) {
        Map<String, Boolean> snapshot = sSnapshot;
        if (snapshot == null) {
            ensureLoadStarted();
            return defaultOf(key);
        }
        Boolean value = snapshot.get(key);
        return value == null ? defaultOf(key) : value;
    }

    /** 供 XposedInit 在进程启动时尽早触发配置加载（异步、不阻塞）。 */
    static void prime() {
        ensureLoadStarted();
    }

    /** 幂等：每个进程只发起一轮异步加载。 */
    private static void ensureLoadStarted() {
        synchronized (sLoadLock) {
            if (sLoadStarted || sSnapshot != null) {
                return;
            }
            sLoadStarted = true;
        }
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                loadFromProvider();
            }
        }, "HammerNotesAdFree-Prefs");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 后台加载：等 Application 就绪 → 查询模块 App 的只读 ContentProvider → 填充快照。
     * 失败会重试有限次；最终失败时固定为空快照（此后一律用内置默认值），并明确记录日志。
     */
    private static void loadFromProvider() {
        for (int attempt = 1; attempt <= MAX_LOAD_ATTEMPTS; attempt++) {
            Application app = waitForApplication();
            if (app == null) {
                // Application 尚未创建（进程启动很早）：稍等再试
                sleepQuietly(400L);
                continue;
            }
            Map<String, Boolean> loaded = queryProvider(app);
            if (loaded != null) {
                sSnapshot = loaded;
                XposedInit.log("Prefs: loaded " + loaded.size()
                        + " switches via PrefsProvider (read once per process; restart target app to apply)");
                return;
            }
            sleepQuietly(300L * attempt);
        }
        sSnapshot = Collections.emptyMap();
        XposedInit.log("Prefs: provider unavailable after " + MAX_LOAD_ATTEMPTS
                + " attempts; 回退内置默认值（全部开关默认开启）。"
                + "请确认模块 App 已安装，并打开过一次模块设置界面");
    }

    /** 等待目标应用 Application 创建（Hook 进程启动早期可能为 null）。 */
    private static Application waitForApplication() {
        long deadline = System.currentTimeMillis() + CONTEXT_WAIT_MS;
        do {
            try {
                Application app = AndroidAppHelper.currentApplication();
                if (app != null) {
                    return app;
                }
            } catch (Throwable ignored) {
                // ActivityThread 尚未就绪等场景：继续等待
            }
            sleepQuietly(CONTEXT_POLL_MS);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /** 查询模块 App 的只读 ContentProvider；失败返回 null（由调用方决定是否重试）。 */
    private static Map<String, Boolean> queryProvider(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(PROVIDER_URI, null, null, null, null);
            if (cursor == null) {
                logProviderError("query returned null");
                return null;
            }
            int keyIndex = cursor.getColumnIndex(COLUMN_KEY);
            int valueIndex = cursor.getColumnIndex(COLUMN_VALUE);
            if (keyIndex < 0 || valueIndex < 0) {
                logProviderError("unexpected cursor columns");
                return null;
            }
            Map<String, Boolean> loaded = new HashMap<>();
            while (cursor.moveToNext()) {
                String key = cursor.getString(keyIndex);
                if (key != null) {
                    loaded.put(key, cursor.getInt(valueIndex) != 0);
                }
            }
            return loaded;
        } catch (Throwable t) {
            logProviderError(String.valueOf(t));
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void logProviderError(String reason) {
        if (sProviderErrorLogged) {
            return;
        }
        sProviderErrorLogged = true;
        XposedInit.log("Prefs: provider query failed: " + reason);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
