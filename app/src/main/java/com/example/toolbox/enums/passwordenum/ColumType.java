package com.example.toolbox.enums.passwordenum;

/**
 * 实体列的逻辑类型，由数据库工具映射为 SQLite 声明类型。
 *
 * <p>名称 {@code ColumType} 是既有公共类型的历史拼写，为兼容注解和现有实体继续保留。SQLite 使用动态类型系统，
 * 因而多个逻辑类型会归并：INTEGER/BIGINT 映射为 INTEGER，其余当前类型映射为 TEXT 或 VARCHAR。</p>
 */
public enum ColumType {
    /** SQLite INTEGER。 */
    INTEGER,
    /** 映射为 SQLite INTEGER，可承载 Java long。 */
    BIGINT,
    /** SQLite TEXT。 */
    TEXT,
    /** 生成带声明长度的 VARCHAR；SQLite 不强制该长度上限。 */
    VARCHAR,
    /** 当前映射为 TEXT，不自动进行日期格式转换。 */
    DATETIME,
    /** 当前映射为 TEXT，不自动进行日期格式转换。 */
    DATE,
    /** 当前映射为 TEXT，SQLite 本身不区分长文本类型。 */
    LONGTEXT

}
