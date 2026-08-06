package com.example.toolbox.interfases;

import com.example.toolbox.enums.passwordenum.ValueType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
/**
 * 标记实体唯一的 SQLite 自增主键字段。
 *
 * <p>每个实体必须且只能声明一个该字段。当前映射实现固定生成
 * {@code INTEGER PRIMARY KEY AUTOINCREMENT}，字段 Java 类型需为 {@code long}/{@link Long}、
 * {@code int}/{@link Integer} 或 {@code short}/{@link Short}，以便插入后回填行号。</p>
 */
public @interface TableFiledId {
    /** 主键生成策略；当前仅支持自增，保留该属性用于表达和兼容既有注解写法。 */
    ValueType value()default ValueType.AUTO_INCREMENT;
}
