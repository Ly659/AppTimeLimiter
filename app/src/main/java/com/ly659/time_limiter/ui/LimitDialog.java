package com.ly659.time_limiter.ui;

import android.app.Dialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ly659.time_limiter.R;
import com.ly659.time_limiter.data.Rule;
import com.ly659.time_limiter.util.TimeFormat;

import java.util.Locale;

/**
 * 本类负责规则编辑弹窗的显示：
 * 设置「每天可用时长」与「强制关闭后的最小间隔」。
 */
public class LimitDialog {

    public interface OnSaved {
        void onSaved(Rule rule);
    }

    public interface OnDelete {
        void onDelete(Rule rule);
    }

    public static void show(Context ctx, Rule rule, OnSaved onSaved) {
        show(ctx, rule, onSaved, null);
    }

    /** 每天可用时长：5 分钟 ~ 8 小时，步长 5 分钟 */
    private static final int LIMIT_MIN = 5;
    private static final int LIMIT_MAX = 480;
    private static final int LIMIT_STEP = 5;

    /** 冷却间隔候选（分钟） */
    private static final int[] COOLDOWN_CHOICES = {
            1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480
    };

    public static void show(Context ctx, Rule rule, OnSaved onSaved, OnDelete onDelete) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 24);
        root.setPadding(pad, dp(ctx, 8), pad, 0);

        // ---- 每日可用时长
        TextView tvLimit = new TextView(ctx);
        tvLimit.setTextSize(16);
        tvLimit.setTextColor(ctx.getColor(R.color.text_primary));
        SeekBar sbLimit = new SeekBar(ctx);
        sbLimit.setMax((LIMIT_MAX - LIMIT_MIN) / LIMIT_STEP);

        int limitMin = (int) Math.max(LIMIT_MIN, Math.min(LIMIT_MAX,
                Math.round(rule.dailyLimitMs / 60000.0)));
        limitMin = Math.round(limitMin / (float) LIMIT_STEP) * LIMIT_STEP;
        limitMin = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, limitMin));
        sbLimit.setProgress((limitMin - LIMIT_MIN) / LIMIT_STEP);
        tvLimit.setText(String.format(Locale.CHINA, "每天最多使用：%s", TimeFormat.human(limitMin * 60000L)));

        sbLimit.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int minutes = LIMIT_MIN + progress * LIMIT_STEP;
                tvLimit.setText(String.format(Locale.CHINA, "每天最多使用：%s",
                        TimeFormat.human(minutes * 60000L)));
            }
        });

        // ---- 冷却间隔
        TextView tvCooldown = new TextView(ctx);
        tvCooldown.setTextSize(16);
        tvCooldown.setPadding(0, dp(ctx, 20), 0, 0);
        tvCooldown.setTextColor(ctx.getColor(R.color.text_primary));
        SeekBar sbCool = new SeekBar(ctx);
        sbCool.setMax(COOLDOWN_CHOICES.length - 1);

        int coolIdx = nearestCooldownIndex(rule.cooldownMs);
        sbCool.setProgress(coolIdx);
        tvCooldown.setText(String.format(Locale.CHINA, "强制关闭后，至少间隔：%s 才能再次打开",
                TimeFormat.human(COOLDOWN_CHOICES[coolIdx] * 60000L)));

        sbCool.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvCooldown.setText(String.format(Locale.CHINA,
                        "强制关闭后，至少间隔：%s 才能再次打开",
                        TimeFormat.human(COOLDOWN_CHOICES[progress] * 60000L)));
            }
        });

        TextView hint = new TextView(ctx);
        hint.setTextSize(12);
        hint.setTextColor(ctx.getColor(R.color.text_secondary));
        hint.setPadding(0, dp(ctx, 16), 0, 0);
        hint.setText(R.string.dialog_hint);

        root.addView(tvLimit);
        root.addView(sbLimit);
        root.addView(tvCooldown);
        root.addView(sbCool);
        root.addView(hint);

        Dialog dlg = new MaterialAlertDialogBuilder(ctx)
                .setTitle(rule.appLabel == null ? rule.packageName : rule.appLabel)
                .setView(root)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Rule updated = new Rule(rule.packageName, rule.appLabel,
                            (LIMIT_MIN + sbLimit.getProgress() * LIMIT_STEP) * 60000L,
                            COOLDOWN_CHOICES[sbCool.getProgress()] * 60000L);
                    updated.enabled = rule.enabled;
                    onSaved.onSaved(updated);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.delete, (d, w) -> {
                    if (onDelete != null) onDelete.onDelete(rule);
                })
                .create();
        dlg.show();
    }

    private static int nearestCooldownIndex(long ms) {
        long minutes = Math.max(1, Math.round(ms / 60000.0));
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < COOLDOWN_CHOICES.length; i++) {
            long diff = Math.abs(COOLDOWN_CHOICES[i] - minutes);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
