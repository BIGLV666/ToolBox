package com.example.toolbox.smoking;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.toolbox.R;
import com.example.toolbox.repository.ToolboxDatabaseHelper;
import com.example.toolbox.util.SwipeBackHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 抽烟统计页面。
 *
 * <p>顶部大按钮一键记录当前时间；两个 Tab 分别展示当日明细和当月明细；
 * 数字卡片展示今日/本月的香烟总数；列表按日期分组展示每支烟的精确时刻。</p>
 *
 * <p>所有数据库操作在单线程池中执行，结果回到主线程刷新 UI，与项目其他模块的模式一致。</p>
 */
public class SmokingActivity extends AppCompatActivity {

    private static final int TAB_DAY = 0;
    private static final int TAB_MONTH = 1;

    private SmokingMapper mapper;
    private ExecutorService executor;
    private SwipeBackHelper.Controller swipeBack;

    // 当前浏览的日期游标（切换日/月时以此为基准）
    private final Calendar cursor = Calendar.getInstance();

    // Views
    private TextView tvDayCount;
    private TextView tvMonthCount;
    private TextView tvPeriodLabel;
    private RecyclerView recyclerView;
    private SmokingListAdapter adapter;
    private TabLayout tabLayout;
    private View rootView;

    // 最近一次插入的记录（用于 Snackbar 撤销）
    private SmokingRecord lastInserted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smoking);

        ToolboxDatabaseHelper dbHelper = new ToolboxDatabaseHelper(this);
        mapper = new SmokingMapper(dbHelper);
        executor = Executors.newSingleThreadExecutor();

        bindViews();
        swipeBack = SwipeBackHelper.create(rootView, this::finish);
        setupToolbar();
        setupTabs();
        setupRecyclerView();
        setupAddButton();
        loadData();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (swipeBack != null && swipeBack.handle(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ── 初始化 ────────────────────────────────────────────────────────────────

    private void bindViews() {
        rootView = findViewById(R.id.smoking_root);
        tvDayCount = findViewById(R.id.smoking_day_count);
        tvMonthCount = findViewById(R.id.smoking_month_count);
        tvPeriodLabel = findViewById(R.id.smoking_period_label);
        recyclerView = findViewById(R.id.smoking_list);
        tabLayout = findViewById(R.id.smoking_tabs);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.smoking_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.smoking_tab_day));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.smoking_tab_month));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { loadData(); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupRecyclerView() {
        adapter = new SmokingListAdapter(this::onDeleteRecord);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void setupAddButton() {
        findViewById(R.id.smoking_add_btn).setOnClickListener(v -> addRecord());
    }

    // ── 数据操作 ──────────────────────────────────────────────────────────────

    /** 一键记录：当前时间戳写库，成功后刷新统计并提供撤销。 */
    private void addRecord() {
        long now = System.currentTimeMillis();
        SmokingRecord record = new SmokingRecord(now);
        executor.execute(() -> {
            mapper.insert(record);
            lastInserted = record;
            runOnUiThread(() -> {
                loadData();
                Snackbar.make(rootView, R.string.smoking_recorded, Snackbar.LENGTH_LONG)
                        .setAction(R.string.smoking_undo, v -> undoLastRecord())
                        .show();
            });
        });
    }

    /** 撤销最近一次插入。 */
    private void undoLastRecord() {
        if (lastInserted == null) return;
        SmokingRecord toDelete = lastInserted;
        lastInserted = null;
        executor.execute(() -> {
            mapper.deleteById(toDelete.getRecordId());
            runOnUiThread(this::loadData);
        });
    }

    /** 从列表长按删除（SmokingListAdapter 回调）。 */
    private void onDeleteRecord(SmokingRecord record) {
        executor.execute(() -> {
            mapper.deleteById(record.getRecordId());
            runOnUiThread(this::loadData);
        });
    }

    /** 根据当前 Tab 和日期游标，刷新统计数字和列表。 */
    private void loadData() {
        int tab = tabLayout.getSelectedTabPosition();
        long[] range = tab == TAB_DAY ? dayRange(cursor) : monthRange(cursor);
        long startMs = range[0];
        long endMs = range[1];

        executor.execute(() -> {
            int dayCount = mapper.countByDay(dayRange(cursor)[0], dayRange(cursor)[1]);
            int monthCount = mapper.countByMonth(monthRange(cursor)[0], monthRange(cursor)[1]);
            List<SmokingRecord> records = tab == TAB_DAY
                    ? mapper.queryByDay(startMs, endMs)
                    : mapper.queryByMonth(startMs, endMs);

            // 按日期分组
            List<SmokingListItem> items = groupByDate(records);

            runOnUiThread(() -> {
                tvDayCount.setText(String.valueOf(dayCount));
                tvMonthCount.setText(String.valueOf(monthCount));
                tvPeriodLabel.setText(tab == TAB_DAY ? formatDay(cursor) : formatMonth(cursor));
                adapter.submitList(items);
            });
        });
    }

    /** 将记录按日期分组，每组前插入日期头。 */
    private List<SmokingListItem> groupByDate(List<SmokingRecord> records) {
        List<SmokingListItem> items = new ArrayList<>();
        Map<String, List<SmokingRecord>> groups = new LinkedHashMap<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("M月d日", Locale.CHINA);

        // 按日期分组
        for (SmokingRecord record : records) {
            String date = dateFormat.format(record.getSmokedAt());
            groups.computeIfAbsent(date, k -> new ArrayList<>()).add(record);
        }

        // 转换为 SmokingListItem
        for (Map.Entry<String, List<SmokingRecord>> entry : groups.entrySet()) {
            items.add(SmokingListItem.header(entry.getKey(), entry.getValue().size()));
            for (SmokingRecord record : entry.getValue()) {
                items.add(SmokingListItem.record(record));
            }
        }

        return items;
    }

    // ── 时间工具 ──────────────────────────────────────────────────────────────

    /** 返回当天 [00:00:00.000, 23:59:59.999] 的毫秒时间戳数组。 */
    private static long[] dayRange(Calendar cal) {
        Calendar start = (Calendar) cal.clone();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) start.clone();
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        end.set(Calendar.MILLISECOND, 999);

        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    /** 返回当月第一天 00:00 到最后一天 23:59 的毫秒时间戳数组。 */
    private static long[] monthRange(Calendar cal) {
        Calendar start = (Calendar) cal.clone();
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) cal.clone();
        end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH));
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        end.set(Calendar.MILLISECOND, 999);

        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    private static String formatDay(Calendar cal) {
        return new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(cal.getTime());
    }

    private static String formatMonth(Calendar cal) {
        return new SimpleDateFormat("yyyy年M月", Locale.CHINA).format(cal.getTime());
    }
}
