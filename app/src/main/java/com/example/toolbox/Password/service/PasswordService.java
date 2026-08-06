package com.example.toolbox.Password.service;

import android.content.Context;

import com.example.toolbox.Password.Repository.PasswordMapper;
import com.example.toolbox.Password.model.Password;
import com.example.toolbox.util.PasswordEncoding;
import com.example.toolbox.repository.ToolboxDatabaseHelper;

import java.util.List;

/**
 * 密码业务服务，负责校验数据并隔离数据库密文与调用方明文。
 *
 * <p>所有写操作先复制调用方实体，再只对副本中的密码加密后交给 Mapper，避免把调用方持有的明文对象
 * 原地替换成密文。所有读操作则在离开服务层前解密，因此 UI 和其他调用方不应直接使用 Mapper。</p>
 */
public final class PasswordService {
    private final PasswordMapper passwordMapper;
    private final PasswordEncoding passwordEncoding;

    public PasswordService(Context context) {
        passwordMapper = new PasswordMapper(new ToolboxDatabaseHelper(context));
        passwordEncoding = new PasswordEncoding();
    }

    /** 返回密码列表，其中密码字段已从数据库密文转换为明文。 */
    public List<Password> getPasswords() {
        List<Password> passwords = passwordMapper.selectList();
        for (Password password : passwords) {
            password.setPassword(passwordEncoding.decrypt(password.getPassword()));
        }
        return passwords;
    }

    /**
     * 加密并插入密码条目；原对象保留明文，仅回填数据库生成的主键。
     */
    public void insertPassword(Password password) {
        validate(password);
        Password stored = copy(password);
        stored.setPassword(passwordEncoding.encrypt(password.getPassword()));
        long id = passwordMapper.insert(stored);
        try {
            password.setPasswordId(id);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法回填密码 ID", e);
        }
    }

    /** 按主键查询，并在有记录时于服务边界内完成解密。 */
    public Password selectPassword(Long passwordId) {
        Password password = passwordMapper.selectById(passwordId);
        if (password != null) {
            password.setPassword(passwordEncoding.decrypt(password.getPassword()));
        }
        return password;
    }

    /**
     * 使用调用方提供的明文重新加密整条记录；返回值仍是原明文对象。
     */
    public Password updatePassword(Password password) {
        validate(password);
        Password stored = copy(password);
        stored.setPassword(passwordEncoding.encrypt(password.getPassword()));
        if (passwordMapper.updateById(stored) == 0) {
            throw new IllegalStateException("密码更新失败");
        }
        return password;
    }

    public void deletePassword(Long passwordId) {
        if (passwordId == null || passwordMapper.deleteById(passwordId) == 0) {
            throw new IllegalStateException("密码删除失败");
        }
    }

    private static void validate(Password password) {
        if (password == null || password.getName() == null || password.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        if (password.getPassword() == null || password.getPassword().trim().isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (password.getCategory() == null || password.getCategory().trim().isEmpty()) {
            password.setCategory("其他");
        }
    }

    private static Password copy(Password source) {
        // 加密只发生在副本上，防止持有 source 的界面或调用者意外接触持久化密文。
        Password copy = new Password(source.getPasswordId(), source.getName(), source.getPassword(),
                source.getNotes(), source.getUrl());
        copy.setCategory(source.getCategory());
        return copy;
    }
}
