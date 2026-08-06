package com.example.toolbox.enums.passwordenum;

/**
 * 主键值生成策略。
 *
 * <p>当前反射映射仅支持 SQLite 整数自增；枚举类型独立保留，以兼容注解 API 并为未来策略扩展提供位置。</p>
 */
public enum ValueType {
    /** 使用 SQLite 的 INTEGER PRIMARY KEY AUTOINCREMENT 生成行号。 */
    AUTO_INCREMENT
}
