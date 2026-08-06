package com.example.toolbox.enums.passwordenum;

/**
 * 旧版密码表列名常量。
 *
 * <p>当前 Mapper 以实体注解作为列映射的唯一依据，本枚举仅为兼容旧调用保留。它反映的是早期表结构，
 * 不包含数据库 v2 新增的 {@code category}，因此不得用于推导当前完整表结构。</p>
 */
public enum PasswordDB {
    /** 自增主键列。 */
    password_id,
    /** 条目名称列。 */
    password_name,
    /** 持久化密码密文列。 */
    password,
    /** 备注列。 */
    notes,
    /** 关联地址列。 */
    url
}
