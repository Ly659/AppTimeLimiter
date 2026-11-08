package com.ly659.time_limiter.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ly659.time_limiter.R;
import com.ly659.time_limiter.data.LimitRepository;
import com.ly659.time_limiter.util.AppInfoUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 选择要限制的应用（支持搜索、排除已在列表中的应用）。
 */
public class AppPickerActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "extra_pkg";
    public static final String EXTRA_LABEL = "extra_label";

    private final List<AppInfoUtil.InstalledApp> all = new ArrayList<>();
    private final List<AppInfoUtil.InstalledApp> shown = new ArrayList<>();
    private final Set<String> excluded = new HashSet<>();
    private Adapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);
        setTitle(R.string.pick_app);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LimitRepository repo = LimitRepository.get();
        repo.init(this);
        for (com.ly659.time_limiter.data.Rule r : repo.getRules()) {
            excluded.add(r.packageName);
        }

        RecyclerView rv = findViewById(R.id.rv_apps);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        EditText search = findViewById(R.id.et_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 加载应用列表放到后台线程，避免首帧卡顿
        new Thread(() -> {
            List<AppInfoUtil.InstalledApp> list = AppInfoUtil.getLaunchableApps(this);
            handler.post(() -> {
                all.clear();
                all.addAll(list);
                filter("");
                findViewById(R.id.pb_loading).setVisibility(View.GONE);
            });
        }).start();
    }

    private void filter(String q) {
        String query = q.trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (AppInfoUtil.InstalledApp a : all) {
            if (excluded.contains(a.packageName)) continue;
            if (query.isEmpty()
                    || a.label.toLowerCase(Locale.getDefault()).contains(query)
                    || a.packageName.toLowerCase(Locale.getDefault()).contains(query)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
        findViewById(R.id.tv_no_result).setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_pick, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AppInfoUtil.InstalledApp a = shown.get(position);
            h.icon.setImageDrawable(a.icon);
            h.label.setText(a.label);
            h.sub.setText(a.packageName);
            h.itemView.setOnClickListener(v -> {
                Intent data = new Intent();
                data.putExtra(EXTRA_PKG, a.packageName);
                data.putExtra(EXTRA_LABEL, a.label);
                setResult(RESULT_OK, data);
                finish();
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView sub;

            VH(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.pick_icon);
                label = v.findViewById(R.id.pick_label);
                sub = v.findViewById(R.id.pick_pkg);
            }
        }
    }
}
