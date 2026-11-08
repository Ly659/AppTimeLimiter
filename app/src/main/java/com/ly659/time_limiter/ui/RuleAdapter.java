package com.ly659.time_limiter.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.ly659.time_limiter.R;
import com.ly659.time_limiter.data.LimitRepository;
import com.ly659.time_limiter.data.Rule;
import com.ly659.time_limiter.data.UsageState;
import com.ly659.time_limiter.util.AppInfoUtil;
import com.ly659.time_limiter.util.TimeFormat;

import java.util.ArrayList;
import java.util.List;

public class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.VH> {

    public interface Listener {
        void onToggle(Rule rule, boolean enabled);

        void onEdit(Rule rule);

        void onResetUsage(Rule rule);
    }

    private final Context ctx;
    private final Listener listener;
    private final List<Rule> items = new ArrayList<>();
    private final LimitRepository repo = LimitRepository.get();

    public RuleAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void submit(List<Rule> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_rule, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Rule r = items.get(position);
        h.bind(r);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView detail;
        final TextView remain;
        final ProgressBar progress;
        final SwitchCompat toggle;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.item_icon);
            label = itemView.findViewById(R.id.item_label);
            detail = itemView.findViewById(R.id.item_detail);
            remain = itemView.findViewById(R.id.item_remain);
            progress = itemView.findViewById(R.id.item_progress);
            toggle = itemView.findViewById(R.id.item_switch);
        }

        void bind(Rule r) {
            label.setText(r.appLabel == null ? r.packageName : r.appLabel);
            Drawable d = AppInfoUtil.getIcon(ctx, r.packageName);
            if (d != null) icon.setImageDrawable(d);
            else icon.setImageResource(R.drawable.bg_warn);

            UsageState st = repo.getState(ctx, r.packageName);
            long now = System.currentTimeMillis();
            long used = st.usedMs;
            long limit = r.dailyLimitMs;
            long remaining = Math.max(0, limit - used);
            int pct = limit <= 0 ? 0 : (int) Math.min(100, used * 100 / limit);
            progress.setProgress(pct);

            StringBuilder sb = new StringBuilder();
            sb.append("每天 ").append(TimeFormat.human(limit))
                    .append(" · 冷却 ").append(TimeFormat.human(r.cooldownMs));

            if (st.isCoolingDown(now)) {
                remain.setText("冷却中 " + TimeFormat.hms(st.remainingCooldown(now)));
                remain.setTextColor(ctx.getColor(R.color.danger_red));
            } else if (remaining <= 0) {
                remain.setText("今日已用完");
                remain.setTextColor(ctx.getColor(R.color.danger_red));
            } else {
                remain.setText("剩余 " + TimeFormat.hms(remaining));
                remain.setTextColor(ctx.getColor(pct > 80 ? R.color.danger_red : R.color.brand_blue));
            }
            detail.setText(sb.toString());

            // 避免复用导致回调串台
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(r.enabled);
            toggle.setOnCheckedChangeListener((b, checked) -> listener.onToggle(r, checked));

            itemView.setOnClickListener(v -> listener.onEdit(r));
            itemView.setOnLongClickListener(v -> {
                listener.onResetUsage(r);
                return true;
            });

            float alpha = r.enabled ? 1f : 0.5f;
            label.setAlpha(alpha);
            icon.setAlpha(alpha);
            detail.setAlpha(alpha);
            progress.setAlpha(alpha);
        }
    }
}
