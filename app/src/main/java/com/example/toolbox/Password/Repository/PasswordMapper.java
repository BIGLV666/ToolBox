package com.example.toolbox.Password.Repository;

import com.example.toolbox.Password.model.Password;
import com.example.toolbox.mapper.BaseMapper;
import com.example.toolbox.repository.ToolboxDatabaseHelper;

/**
 * 密码实体的 SQLite Mapper，仅声明实体类型并复用通用 CRUD 实现。
 *
 * <p>本类不负责加解密：写入这里的 {@link Password#getPassword()} 必须已经是密文，从这里读出的值也仍是密文。
 * 正常业务应通过密码服务访问，以维持明文与持久化层之间的边界。</p>
 */
public final class PasswordMapper extends BaseMapper<Password> {
    public PasswordMapper(ToolboxDatabaseHelper databaseHelper) {
        super(databaseHelper, Password.class);
    }
}
