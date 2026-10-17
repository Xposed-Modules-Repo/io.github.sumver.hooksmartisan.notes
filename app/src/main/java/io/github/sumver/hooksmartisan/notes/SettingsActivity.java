package io.github.sumver.hooksmartisan.notes;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块设置界面（Material Design 3）。
 *
 * 与 Hook 进程的通信：写入本模块自己的 SharedPreferences（{@code settings}），
 * Hook 进程通过本模块的只读 ContentProvider（{@link PrefsProvider}）读取同一文件（见 {@link Prefs}）。
 * 开关保存后需重启（强制停止）锤子便签才生效。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 分组标题字号（sp）：比卡片内标题（Material3 BodyLarge = 16sp）更大。 */
    private static final float GROUP_HEADER_SP = 18f;

    /** 单个开关项。 */
    private static final class SwitchItem {
        final String key;
        final String title;
        final String summary;

        SwitchItem(String key, String title, String summary) {
            this.key = key;
            this.title = title;
            this.summary = summary;
        }
    }

    private SharedPreferences prefs;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.setSubtitle(R.string.settings_subtitle);

        prefs = Prefs.ui(this);
        seedDefaults();

        container = findViewById(R.id.container);
        buildContent();
    }

    /** 首次打开时写入全部默认值（全部开关默认开启）。 */
    private void seedDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : Prefs.allKeys()) {
            if (!prefs.contains(key)) {
                editor.putBoolean(key, Prefs.defaultOf(key));
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    private void buildContent() {
        container.removeAllViews();

        Map<String, List<SwitchItem>> groups = new LinkedHashMap<>();
        groups.put(getString(R.string.group_splash), Arrays.asList(
                new SwitchItem(Prefs.K_SPLASH_AD,
                        getString(R.string.switch_splash_ad),
                        getString(R.string.switch_splash_ad_summary))
                // 已停用（1.9 起移除该功能，代码保留备查）：隐藏开屏页「免广告·VIP」按钮
                // , new SwitchItem(Prefs.K_SPLASH_VIP,
                //         getString(R.string.switch_splash_vip),
                //         getString(R.string.switch_splash_vip_summary))
        ));
        groups.put(getString(R.string.group_sensitive_check), Arrays.asList(
                new SwitchItem(Prefs.K_ALLOW_SENSITIVE_SHARE,
                        getString(R.string.switch_allow_sensitive_share),
                        getString(R.string.switch_allow_sensitive_share_summary))));
        groups.put(getString(R.string.group_share), Arrays.asList(
                new SwitchItem(Prefs.K_HIDE_SHARE_IMPORT_KNB,
                        getString(R.string.switch_hide_share_import_knb),
                        getString(R.string.switch_hide_share_import_knb_summary)),
                new SwitchItem(Prefs.K_HIDE_SHARE_LONG_WEIBO,
                        getString(R.string.switch_hide_share_long_weibo),
                        getString(R.string.switch_hide_share_long_weibo_summary)),
                new SwitchItem(Prefs.K_HIDE_SHARE_EMAIL,
                        getString(R.string.switch_hide_share_email),
                        getString(R.string.switch_hide_share_email_summary)),
                new SwitchItem(Prefs.K_HIDE_FOOTER,
                        getString(R.string.switch_hide_footer),
                        getString(R.string.switch_hide_footer_summary))));
        groups.put(getString(R.string.group_note_page), Arrays.asList(
                new SwitchItem(Prefs.K_HIDE_AI_ENTRY,
                        getString(R.string.switch_hide_ai_entry),
                        getString(R.string.switch_hide_ai_entry_summary))));
        groups.put(getString(R.string.group_home), Arrays.asList(
                new SwitchItem(Prefs.K_HIDE_RAG_ENTRY,
                        getString(R.string.switch_hide_rag_entry),
                        getString(R.string.switch_hide_rag_entry_summary))));
        groups.put(getString(R.string.group_user_center), Arrays.asList(
                new SwitchItem(Prefs.K_HIDE_VIP_CARD,
                        getString(R.string.switch_hide_vip_card),
                        getString(R.string.switch_hide_vip_card_summary))));
        groups.put(getString(R.string.group_settings_page), Arrays.asList(
                new SwitchItem(Prefs.K_HIDE_REWARD_VIDEO,
                        getString(R.string.switch_hide_reward_video),
                        getString(R.string.switch_hide_reward_video_summary)),
                new SwitchItem(Prefs.K_HIDE_INVITE_FRIENDS,
                        getString(R.string.switch_hide_invite_friends),
                        getString(R.string.switch_hide_invite_friends_summary)),
                new SwitchItem(Prefs.K_HIDE_ACCOUNT_PROMO,
                        getString(R.string.switch_hide_account_promo),
                        getString(R.string.switch_hide_account_promo_summary))));
        for (Map.Entry<String, List<SwitchItem>> entry : groups.entrySet()) {
            addGroup(entry.getKey(), entry.getValue());
        }
        addHintCard();
    }

    /** 分组标题：比卡片内文字（BodyLarge 16sp）更大且加粗（18sp Bold）。 */
    private TextView createGroupHeader(CharSequence text) {
        TextView header = new TextView(this);
        TextViewCompat.setTextAppearance(header,
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, GROUP_HEADER_SP);
        header.setText(text);
        header.setTextColor(MaterialColors.getColor(header,
                com.google.android.material.R.attr.colorPrimary));
        return header;
    }

    /** 一组开关 = 分组标题 + 一张 MD3 卡片（行之间带分割线）。 */
    private void addGroup(String title, List<SwitchItem> items) {
        TextView header = createGroupHeader(title);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(16);
        headerParams.bottomMargin = dp(8);
        headerParams.leftMargin = dp(4);
        container.addView(header, headerParams);

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardParams);
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOutlineVariant)));
        card.setRadius(dp(12));

        LinearLayout cardInner = new LinearLayout(this);
        cardInner.setOrientation(LinearLayout.VERTICAL);
        card.addView(cardInner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.size(); i++) {
            SwitchItem item = items.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_switch, cardInner, false);
            TextView tvTitle = row.findViewById(R.id.tv_title);
            TextView tvSummary = row.findViewById(R.id.tv_summary);
            MaterialSwitch switchWidget = row.findViewById(R.id.switch_widget);

            tvTitle.setText(item.title);
            if (item.summary == null || item.summary.isEmpty()) {
                tvSummary.setVisibility(View.GONE);
            } else {
                tvSummary.setText(item.summary);
            }
            switchWidget.setChecked(prefs.getBoolean(item.key, Prefs.defaultOf(item.key)));
            switchWidget.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean(item.key, isChecked).apply();
                // 注意：这里不能用 XposedInit.log —— 模块 App 进程里没有 Xposed API 类
                // （de.robv.android.xposed.* 为 compileOnly），引用会导致 NoClassDefFoundError。
                Log.i("HammerNotesAdFree", "settings: " + item.key + " -> " + isChecked);
            });
            row.setOnClickListener(v -> switchWidget.toggle());
            cardInner.addView(row);

            if (i != items.size() - 1) {
                MaterialDivider divider = new MaterialDivider(this);
                cardInner.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        container.addView(card);
    }

    private void addHintCard() {
        TextView header = createGroupHeader(getString(R.string.settings_hint_title));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(16);
        headerParams.bottomMargin = dp(8);
        headerParams.leftMargin = dp(4);
        container.addView(header, headerParams);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOutlineVariant)));
        card.setRadius(dp(12));

        TextView hint = new TextView(this);
        TextViewCompat.setTextAppearance(hint,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        hint.setText(R.string.settings_hint);
        hint.setTextColor(MaterialColors.getColor(hint,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(dp(16), dp(14), dp(16), dp(14));
        // 说明文本里如含链接，转成可点击链接（点击用浏览器打开）
        hint.setMovementMethod(LinkMovementMethod.getInstance());
        hint.setLinkTextColor(MaterialColors.getColor(hint,
                com.google.android.material.R.attr.colorPrimary));
        Linkify.addLinks(hint, Linkify.WEB_URLS);
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(card);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
