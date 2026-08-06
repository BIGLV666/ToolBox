package com.example.toolbox.mapper;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.toolbox.util.DatabaseUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于注解元数据的通用 SQLite CRUD Mapper。
 *
 * <p>表名、主键列和对象转换规则统一由 {@link DatabaseUtil} 提供；所有筛选值均通过参数绑定传入，
 * 不直接拼接实体数据。该类不管理跨操作事务，需要原子执行多个操作时应由上层显式开启事务。</p>
 *
 * @param <T> 具有有效数据库注解和无参构造器的实体类型
 */
public class BaseMapper<T> {
    private final SQLiteOpenHelper databaseHelper;
    private final DatabaseUtil.EntityMeta<T> meta;

    protected BaseMapper(SQLiteOpenHelper databaseHelper, Class<T> entityClass) {
        if (databaseHelper == null) {
            throw new IllegalArgumentException("databaseHelper cannot be null");
        }
        this.databaseHelper = databaseHelper;
        this.meta = DatabaseUtil.getEntityMeta(entityClass);
    }

    /**
     * 插入实体但不写入其主键字段，并将 SQLite 生成的行号反射回填到原实体。
     */
    public long insert(T entity) {
        requireEntity(entity);
        SQLiteDatabase database = databaseHelper.getWritableDatabase();
        long id = database.insertOrThrow(meta.getTableName(), null,
                meta.toContentValues(entity, false));
        meta.setId(entity, id);
        return id;
    }

    /**
     * 以实体主键为条件更新其余所有映射列。
     *
     * @return SQLite 实际更新的行数
     */
    public int updateById(T entity) {
        requireEntity(entity);
        Object id = requireId(entity);
        return databaseHelper.getWritableDatabase().update(
                meta.getTableName(),
                meta.toContentValues(entity, false),
                meta.getIdColumnName() + " = ?",
                new String[]{String.valueOf(id)}
        );
    }

    public int deleteById(Object id) {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        return databaseHelper.getWritableDatabase().delete(
                meta.getTableName(),
                meta.getIdColumnName() + " = ?",
                new String[]{String.valueOf(id)}
        );
    }

    /**
     * 按主键读取至多一行，并通过列名映射为实体；不存在时返回 {@code null}。
     */
    public T selectById(Object id) {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        try (Cursor cursor = databaseHelper.getReadableDatabase().query(
                meta.getTableName(), null,
                meta.getIdColumnName() + " = ?", new String[]{String.valueOf(id)},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? meta.fromCursor(cursor) : null;
        }
    }

    /**
     * 读取表中全部记录。未指定排序条件，返回顺序不构成稳定接口。
     */
    public List<T> selectList() {
        List<T> result = new ArrayList<>();
        try (Cursor cursor = databaseHelper.getReadableDatabase().query(
                meta.getTableName(), null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                result.add(meta.fromCursor(cursor));
            }
        }
        return result;
    }

    public long selectCount() {
        String sql = "SELECT COUNT(*) FROM `" + meta.getTableName() + "`";
        try (Cursor cursor = databaseHelper.getReadableDatabase().rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private void requireEntity(T entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity cannot be null");
        }
    }

    private Object requireId(T entity) {
        Object id = meta.getId(entity);
        if (id == null) {
            throw new IllegalArgumentException("entity ID cannot be null");
        }
        return id;
    }
}
