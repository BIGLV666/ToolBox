package com.example.toolbox.util;

import android.content.ContentValues;
import android.database.Cursor;

import com.example.toolbox.enums.passwordenum.ColumType;
import com.example.toolbox.interfases.Column;
import com.example.toolbox.interfases.Table;
import com.example.toolbox.interfases.TableFiledId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 根据实体注解生成 SQLite 表结构，并在 Java 对象、{@link ContentValues} 与 {@link Cursor} 之间映射数据。
 *
 * <p>该工具只识别 {@link Table}、{@link Column} 和 {@link TableFiledId} 明确标记的元数据，
 * 不会按普通成员变量自动持久化。反射元数据在 Mapper 构造时解析，实体必须具有一个无参构造器和且仅有一个
 * 自增主键字段。</p>
 */
public final class DatabaseUtil {
    private DatabaseUtil() {
    }

    /**
     * 解析实体类及其父类中声明的持久化字段。
     *
     * <p>静态字段、编译器生成字段和无持久化注解的字段会被忽略；字段即使是私有成员也会通过反射访问。
     * 表名和列名只允许安全的 SQL 标识符，避免元数据被拼入 DDL 时形成非法 SQL。</p>
     *
     * @param entityClass 要解析的实体类型
     * @return 包含表名、字段映射、主键和无参构造器的不可变元数据
     * @throws IllegalArgumentException 注解、主键、标识符或无参构造器不符合映射规则时抛出
     */
    public static <T> EntityMeta<T> getEntityMeta(Class<T> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table == null || table.value().trim().isEmpty()) {
            throw new IllegalArgumentException(entityClass.getName() + " must declare @Table");
        }
        validateIdentifier(table.value().trim());

        List<FieldMeta> fields = new ArrayList<>();
        FieldMeta idField = null;
        // 先处理实体自身，再沿继承链向上处理；字段顺序只影响生成 DDL 的列顺序，不影响按列名读写。
        for (Class<?> type = entityClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                TableFiledId id = field.getAnnotation(TableFiledId.class);
                Column column = field.getAnnotation(Column.class);
                if (id == null && column == null) {
                    continue;
                }
                if (id != null && idField != null) {
                    throw new IllegalArgumentException(entityClass.getName() + " has more than one @TableFiledId");
                }

                field.setAccessible(true);
                // 显式列名优先；空列名则把 Java 驼峰字段名转换为 snake_case。
                String columnName = column != null && !column.value().trim().isEmpty()
                        ? column.value().trim()
                        : toSnakeCase(field.getName());
                validateIdentifier(columnName);
                FieldMeta fieldMeta = new FieldMeta(field, columnName, column, id != null);
                fields.add(fieldMeta);
                if (id != null) {
                    idField = fieldMeta;
                }
            }
        }
        if (idField == null) {
            throw new IllegalArgumentException(entityClass.getName() + " must declare one @TableFiledId");
        }

        try {
            Constructor<T> constructor = entityClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return new EntityMeta<>(table.value().trim(), fields, idField, constructor);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(entityClass.getName() + " must have a no-argument constructor", e);
        }
    }

    /**
     * 按实体元数据生成当前模型对应的 {@code CREATE TABLE IF NOT EXISTS} 语句。
     *
     * <p>主键固定映射为 SQLite 的 {@code INTEGER PRIMARY KEY AUTOINCREMENT}；普通列的空值、长度和默认值
     * 来自 {@link Column}。默认值是 SQL 片段，会原样追加，因此字符串默认值需要由注解自行包含引号。</p>
     */
    public static String createTableSql(Class<?> entityClass) {
        EntityMeta<?> meta = getEntityMeta(entityClass);
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(quoteIdentifier(meta.tableName)).append(" (");
        for (int i = 0; i < meta.fields.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            FieldMeta field = meta.fields.get(i);
            sql.append(quoteIdentifier(field.columnName)).append(' ');
            if (field.id) {
                sql.append("INTEGER PRIMARY KEY AUTOINCREMENT");
                continue;
            }

            Column column = field.column;
            sql.append(toSqlType(column.type(), column.length()));
            if (!column.nullable()) {
                sql.append(" NOT NULL");
            }
            if (!column.defaultValue().isEmpty()) {
                sql.append(" DEFAULT ").append(column.defaultValue());
            }
        }
        return sql.append(')').toString();
    }

    private static String toSqlType(ColumType type, int length) {
        // SQLite 使用动态类型：BIGINT 归入 INTEGER，日期和长文本统一以 TEXT 声明。
        switch (type) {
            case INTEGER:
            case BIGINT:
                return "INTEGER";
            case VARCHAR:
                return "VARCHAR(" + Math.max(1, length) + ")";
            case DATETIME:
            case DATE:
            case TEXT:
            case LONGTEXT:
            default:
                return "TEXT";
        }
    }

    private static String toSnakeCase(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isUpperCase(current) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(current));
        }
        return result.toString();
    }

    private static String quoteIdentifier(String identifier) {
        validateIdentifier(identifier);
        return '`' + identifier + '`';
    }

    private static void validateIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
    }

    /**
     * 单个实体类型的已解析映射信息，供通用 Mapper 复用。
     *
     * <p>字段集合在构造时复制并冻结，避免运行期间修改映射规则。</p>
     */
    public static final class EntityMeta<T> {
        private final String tableName;
        private final List<FieldMeta> fields;
        private final FieldMeta idField;
        private final Constructor<T> constructor;

        private EntityMeta(String tableName, List<FieldMeta> fields, FieldMeta idField,
                           Constructor<T> constructor) {
            this.tableName = tableName;
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
            this.idField = idField;
            this.constructor = constructor;
        }

        public String getTableName() {
            return tableName;
        }

        public String getIdColumnName() {
            return idField.columnName;
        }

        public Object getId(T entity) {
            return idField.read(entity);
        }

        public void setId(T entity, long id) {
            idField.write(entity, convertNumber(id, idField.field.getType()));
        }

        /**
         * 将实体转为 SQLite 写入参数。
         *
         * @param includeId 是否包含自增主键；常规插入和更新均应排除主键
         */
        public ContentValues toContentValues(T entity, boolean includeId) {
            ContentValues values = new ContentValues();
            for (FieldMeta field : fields) {
                if (!includeId && field.id) {
                    continue;
                }
                putValue(values, field.columnName, field.read(entity));
            }
            return values;
        }

        /**
         * 使用无参构造器创建实体，并按列名填充当前游标行。
         *
         * <p>查询结果未包含的列会被跳过，保留构造器或字段初始化产生的值；SQL NULL 写入基本类型时也会
         * 保留该基本类型的初始值。</p>
         */
        public T fromCursor(Cursor cursor) {
            try {
                T entity = constructor.newInstance();
                for (FieldMeta field : fields) {
                    int index = cursor.getColumnIndex(field.columnName);
                    if (index >= 0) {
                        field.write(entity, readValue(cursor, index, field.field.getType()));
                    }
                }
                return entity;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + constructor.getDeclaringClass().getName(), e);
            }
        }
    }

    private static final class FieldMeta {
        private final Field field;
        private final String columnName;
        private final Column column;
        private final boolean id;

        private FieldMeta(Field field, String columnName, Column column, boolean id) {
            this.field = field;
            this.columnName = columnName;
            this.column = column;
            this.id = id;
        }

        private Object read(Object entity) {
            try {
                return field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field.getName(), e);
            }
        }

        private void write(Object entity, Object value) {
            if (value == null && field.getType().isPrimitive()) {
                return;
            }
            try {
                field.set(entity, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot write field " + field.getName(), e);
            }
        }
    }

    private static void putValue(ContentValues values, String key, Object value) {
        // Date 按毫秒时间戳写入，枚举按常量名写入；读取时必须遵循相同约定。
        if (value == null) {
            values.putNull(key);
        } else if (value instanceof String) {
            values.put(key, (String) value);
        } else if (value instanceof Integer) {
            values.put(key, (Integer) value);
        } else if (value instanceof Long) {
            values.put(key, (Long) value);
        } else if (value instanceof Short) {
            values.put(key, (Short) value);
        } else if (value instanceof Byte) {
            values.put(key, (Byte) value);
        } else if (value instanceof Float) {
            values.put(key, (Float) value);
        } else if (value instanceof Double) {
            values.put(key, (Double) value);
        } else if (value instanceof Boolean) {
            values.put(key, (Boolean) value);
        } else if (value instanceof byte[]) {
            values.put(key, (byte[]) value);
        } else if (value instanceof Date) {
            values.put(key, ((Date) value).getTime());
        } else if (value.getClass().isEnum()) {
            values.put(key, ((Enum<?>) value).name());
        } else {
            throw new IllegalArgumentException("Unsupported field type: " + value.getClass().getName());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readValue(Cursor cursor, int index, Class<?> type) {
        // SQLite 没有独立布尔类型，约定 0 为 false、非 0 为 true。
        if (cursor.isNull(index)) {
            return null;
        }
        if (type == String.class) {
            return cursor.getString(index);
        }
        if (type == long.class || type == Long.class) {
            return cursor.getLong(index);
        }
        if (type == int.class || type == Integer.class) {
            return cursor.getInt(index);
        }
        if (type == short.class || type == Short.class) {
            return cursor.getShort(index);
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) cursor.getShort(index);
        }
        if (type == float.class || type == Float.class) {
            return cursor.getFloat(index);
        }
        if (type == double.class || type == Double.class) {
            return cursor.getDouble(index);
        }
        if (type == boolean.class || type == Boolean.class) {
            return cursor.getInt(index) != 0;
        }
        if (type == byte[].class) {
            return cursor.getBlob(index);
        }
        if (type == Date.class) {
            return new Date(cursor.getLong(index));
        }
        if (type.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) type, cursor.getString(index));
        }
        throw new IllegalArgumentException("Unsupported field type: " + type.getName());
    }

    private static Object convertNumber(long value, Class<?> type) {
        // insert 返回 long 行号，回填时仅允许主键声明为受支持的整数类型。
        if (type == long.class || type == Long.class) {
            return value;
        }
        if (type == int.class || type == Integer.class) {
            return (int) value;
        }
        if (type == short.class || type == Short.class) {
            return (short) value;
        }
        throw new IllegalArgumentException("Auto-increment ID must be a numeric field");
    }
}
