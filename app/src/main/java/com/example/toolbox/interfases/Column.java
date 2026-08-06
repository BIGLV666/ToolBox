package com.example.toolbox.interfases;

import com.example.toolbox.enums.passwordenum.ColumType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
/**
 * 声明普通实体字段的 SQLite 列映射。
 *
 * <p>只有带本注解或主键注解的字段会参与持久化。该注解不能替代主键注解；自增主键应只由
 * {@link TableFiledId} 标识。</p>
 */
public @interface Column {
    /** 列名；为空时由 Java 字段名自动转换为 snake_case。 */
    String value() default "";
    /** 是否允许 SQL NULL；为 {@code false} 时建表语句追加 NOT NULL。 */
    boolean nullable()default true;
    /** VARCHAR 的声明长度；小于 1 时生成器按 1 处理，对其他列类型无效。 */
    int length()default 255;
    /**
     * SQL 默认值表达式，将不经转义直接追加到 DDL；字符串字面量必须自行包含单引号，空字符串表示不声明默认值。
     */
    String defaultValue() default "";
    /** 逻辑列类型，最终由数据库工具转换为 SQLite 声明类型。 */
    ColumType type() default ColumType.VARCHAR;
}
