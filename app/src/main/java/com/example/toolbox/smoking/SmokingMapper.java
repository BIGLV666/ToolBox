package com.example.toolbox.smoking;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.toolbox.repository.ToolboxDatabaseHelper;
import com.example.toolbox.util.DatabaseUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽烟记录的 SQLite 数据访问层。
 *
 * <p>查询结果均按 {@code smoked_at DESC} 排序，方便 UI 直接展示最新记录。
 * 统计方法返回聚合值而非完整实体，减少不必要的对象创建。</p>
 */
public class SmokingMapper {

    private final ToolboxDatabaseHelper dbHelper;
    private final DatabaseUtil.EntityMeta<SmokingRecord> meta;

    public SmokingMapper(ToolboxDatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
        this.meta = DatabaseUtil.getEntityMeta(SmokingRecord.class);
    }

    /** 插入一条记录，回填 recordId 后返回该实体。 */
    public SmokingRecord insert(SmokingRecord record) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = meta.toContentValues(record, false);
        long id = db.insert(meta.getTableName(), null, values);
        meta.setId(record, id);
        return record;
    }

    /** 删除指定 id 的记录（用于误操作撤销）。 */
    public void deleteById(long recordId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(meta.getTableName(),
                '`' + meta.getIdColumnName() + "` = ?",
                new String[]{String.valueOf(recordId)});
    }

    /**
     * 查询指定日范围内的全部记录，按时间倒序。
     *
     * @param dayStartMs 当天 00:00:00.000 的 Unix 毫秒
     * @param dayEndMs   当天 23:59:59.999 的 Unix 毫秒
     */
    public List<SmokingRecord> queryByDay(long dayStartMs, long dayEndMs) {
        return queryRange(dayStartMs, dayEndMs);
    }

    /**
     * 查询指定月范围内的全部记录，按时间倒序。
     *
     * @param monthStartMs 该月第一天 00:00:00.000 的 Unix 毫秒
     * @param monthEndMs   该月最后一天 23:59:59.999 的 Unix 毫秒
     */
    public List<SmokingRecord> queryByMonth(long monthStartMs, long monthEndMs) {
        return queryRange(monthStartMs, monthEndMs);
    }

    /**
     * 统计某一天内的抽烟数量，不返回实体列表。
     */
    public int countByDay(long dayStartMs, long dayEndMs) {
        return countRange(dayStartMs, dayEndMs);
    }

    /**
     * 统计某一个月内的抽烟数量，不返回实体列表。
     */
    public int countByMonth(long monthStartMs, long monthEndMs) {
        return countRange(monthStartMs, monthEndMs);
    }

    /**
     * 查询最近一次记录，用于首屏展示"上次抽烟时间"。
     * 没有记录时返回 null。
     */
    public SmokingRecord queryLatest() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT * FROM `" + meta.getTableName()
                + "` ORDER BY `smoked_at` DESC LIMIT 1";
        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (cursor.moveToFirst()) {
                return meta.fromCursor(cursor);
            }
        }
        return null;
    }

    // ── 私有辅助 ─────────────────────────────────────────────────────────────

    private List<SmokingRecord> queryRange(long startMs, long endMs) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT * FROM `" + meta.getTableName()
                + "` WHERE `smoked_at` >= ? AND `smoked_at` <= ?"
                + " ORDER BY `smoked_at` DESC";
        List<SmokingRecord> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql,
                new String[]{String.valueOf(startMs), String.valueOf(endMs)})) {
            while (cursor.moveToNext()) {
                result.add(meta.fromCursor(cursor));
            }
        }
        return result;
    }

    private int countRange(long startMs, long endMs) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT COUNT(*) FROM `" + meta.getTableName()
                + "` WHERE `smoked_at` >= ? AND `smoked_at` <= ?";
        try (Cursor cursor = db.rawQuery(sql,
                new String[]{String.valueOf(startMs), String.valueOf(endMs)})) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }
}
