package com.ly659.time_limiter.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ly659.time_limiter.R;
import com.ly659.time_limiter.data.LimitRepository;
import com.ly659.time_limiter.data.Rule;
import com.ly659.time_limiter.data.UsageState;
import com.ly659.time_limiter.service.GuardService;
import com.ly659.time_limiter.util.PermissionUtil;
import com.ly659.time_limiter.util.TimeFormat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_ADD_APP = 100;
    private static final int REQ_USAGE_ACCESS = 101;
    private static final int REQ_OVERLAY = 102;
    private static final int REQ_NOTIFICATION = 103;

    private LimitRepository repo;
    private RuleAdapter adapter;
    private SwitchCompat switchGuard;
    private TextView tvGuardStatus;
    private TextView tvEmpty;
    private View permissionBanner;
    private TextView tvPermText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repo = LimitRepository.get();
        repo.init(this);

        switchGuard = findViewById(R.id.switch_guard);
        tvGuardStatus = findViewById(R.id.tv_guard_status);
        tvEmpty = findViewById(R.id.tv_empty);
        permissionBanner = findViewById(R.id.permission_banner);
        tvPermText = findViewById(R.id.tv_perm_text);

        RecyclerView rv = findViewById(R.id.rv_rules);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RuleAdapter(this, new RuleAdapter.Listener() {
            @Override
            public void onToggle(Rule rule, boolean enabled) {
                repo.setRuleEnabled(rule.packageName, enabled);
                refresh();
            }

            @Override
            public void onEdit(Rule rule) {
                showEditDialog(rule);
            }

            @Override
            public void onResetUsage(Rule rule) {
                repo.resetUsage(MainActivity.this, rule.packageName);
                repo.clearCooldown(MainActivity.this, rule.packageName);
                refresh();
                Toast.makeText(MainActivity.this, "已重置今日用量", Toast.LENGTH_SHORT).show();
            }
        });
        rv.setAdapter(adapter);

        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_ADD_APP));

        findViewById(R.id.btn_usage_perm).setOnClickListener(v -> {
            if (!PermissionUtil.requestUsageAccess(this, REQ_USAGE_ACCESS)) {
                Toast.makeText(this, "无法打开权限页，请到系统设置中授予", Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.btn_overlay_perm).setOnClickListener(v ->
                PermissionUtil.requestOverlay(this, REQ_OVERLAY));
        findViewById(R.id.btn_battery).setOnClickListener(v ->
                PermissionUtil.requestIgnoreBatteryOptimization(this));
        findViewById(R.id.btn_autostart).setOnClickListener(v ->
                PermissionUtil.openAutoStartSettings(this));

        switchGuard.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) {
                repo.setGuardEnabled(false);
                GuardService.stop(this);
                refresh();
                return;
            }
            startGuardWithChecks();
        });

        maybeRequestNotificationPermission();

        // 从通知点进来时提示被限制的 App
        String focusPkg = getIntent().getStringExtra("focus_pkg");
        if (focusPkg != null && getIntent().getBooleanExtra("over_limit", false)) {
            Rule r = repo.findRule(focusPkg);
            if (r != null) showBlockedDialog(r);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页回来时，如果开关本应开启但服务没跑，自动拉起
        if (repo.isGuardEnabled() && !GuardService.isRunning()
                && PermissionUtil.hasUsageAccess(this)) {
            GuardService.start(this);
        }
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(PermissionUtil.notificationPermissions(), REQ_NOTIFICATION);
        }
    }

    private void startGuardWithChecks() {
        if (!repo.hasAnyRule()) {
            switchGuard.setChecked(false);
            Toast.makeText(this, R.string.need_rule_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PermissionUtil.hasUsageAccess(this)) {
            switchGuard.setChecked(false);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_usage_title)
                    .setMessage(R.string.perm_usage_msg)
                    .setPositiveButton(R.string.go_grant, (d, w) ->
                            PermissionUtil.requestUsageAccess(this, REQ_USAGE_ACCESS))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (!PermissionUtil.hasOverlay(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_overlay_title)
                    .setMessage(R.string.perm_overlay_msg)
                    .setPositiveButton(R.string.go_grant, (d, w) ->
                            PermissionUtil.requestOverlay(this, REQ_OVERLAY))
                    .setNegativeButton(R.string.later, null)
                    .show();
        }
        repo.setGuardEnabled(true);
        GuardService.start(this);
        Toast.makeText(this, R.string.guard_started, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void refresh() {
        List<Rule> rules = repo.getRules();
        adapter.submit(rules);
        tvEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);

        boolean usageOk = PermissionUtil.hasUsageAccess(this);
        permissionBanner.setVisibility(usageOk ? View.GONE : View.VISIBLE);
        tvPermText.setText(R.string.perm_banner_text);

        boolean enabled = repo.isGuardEnabled();
        if (switchGuard.isChecked() != enabled) {
            switchGuard.setChecked(enabled);
        }
        if (enabled && GuardService.isRunning()) {
            tvGuardStatus.setText(R.string.status_running);
            tvGuardStatus.setTextColor(getColor(R.color.ok_green));
        } else if (enabled) {
            tvGuardStatus.setText(R.string.status_pending);
            tvGuardStatus.setTextColor(getColor(R.color.warn_orange));
        } else {
            tvGuardStatus.setText(R.string.status_off);
            tvGuardStatus.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private void showEditDialog(Rule rule) {
        String targetPkg = rule.packageName;
        String targetLabel = rule.appLabel;
        LimitDialog.show(this, rule, updated -> {
            if (updated == null) return;
            repo.addOrUpdateRule(updated);
            refresh();
        }, target -> {
            repo.removeRule(targetPkg);
            refresh();
            Toast.makeText(this, "已移除「" + targetLabel + "」", Toast.LENGTH_SHORT).show();
        });
    }

    private void showBlockedDialog(Rule rule) {
        UsageState st = repo.getState(this, rule.packageName);
        long remain = st.remainingCooldown(System.currentTimeMillis());
        String msg = remain > 0
                ? "还需等待 " + TimeFormat.human(remain) + " 才能再次使用「" + rule.appLabel + "」。"
                : "「" + rule.appLabel + "」今日额度已用完。";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.blocked_title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD_APP && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra(AppPickerActivity.EXTRA_PKG);
            String label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL);
            if (pkg != null) {
                Rule existing = repo.findRule(pkg);
                if (existing != null) {
                    showEditDialog(existing);
                } else {
                    Rule r = new Rule(pkg, label == null ? pkg : label,
                            30 * 60 * 1000L, 10 * 60 * 1000L);
                    LimitDialog.show(this, r, updated -> {
                        repo.addOrUpdateRule(updated);
                        refresh();
                    });
                }
            }
        }
        if (requestCode == REQ_USAGE_ACCESS && resultCode == RESULT_OK) {
            refresh();
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
