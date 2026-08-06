package com.example.toolbox.Password.model;

import com.example.toolbox.enums.passwordenum.ColumType;
import com.example.toolbox.interfases.Column;
import com.example.toolbox.interfases.Table;
import com.example.toolbox.interfases.TableFiledId;

@Table(value = "password")
/**
 * 密码表实体，同时作为服务层输入与输出模型。
 *
 * <p>{@link #Password} 的内容具有层级边界：传入或返回 {@code PasswordService} 时是明文，交给 Mapper
 * 持久化时必须是 {@code PasswordEncoding} 生成的密文。模型自身不执行加解密，绕过服务层直接写库会破坏该约定。</p>
 *
 * <p>字段名虽然采用历史上的首字母大写形式，实际 SQLite 列名由注解固定，重命名 Java 字段时不得改变既有列名。</p>
 */
public class Password {
    /** SQLite 自增主键；插入前可为空，插入成功后由 Mapper 回填。 */
    @TableFiledId
    private Long PasswordId;
    /** 用户可识别的条目名称，对应非空列 {@code password_name}。 */
    @Column(value = "password_name",nullable = false,type = ColumType.VARCHAR)
    private String PasswordName;
    /** 密码载荷；数据库内必须为密文，服务层边界之外才可为明文。 */
    @Column(value = "password",nullable = false,type = ColumType.VARCHAR)
    private String Password;
    /** 可空的补充说明。 */
    @Column(value = "notes",nullable = true,type = ColumType.VARCHAR)
    private String Notes;
    /** 可空的关联地址；未指定类型时使用 Column 的默认 VARCHAR 映射。 */
    @Column(value = "url" )
    private String Url;
    /**
     * 分类列自数据库 v2 起存在；Java 初始值和 SQL 默认值共同兼容新对象及迁移前的历史记录。
     * defaultValue 是原样拼入 DDL 的 SQL 表达式，因此字符串值需要保留单引号。
     */
    @Column(value = "category", nullable = false, defaultValue = "'其他'")
    private String Category = "其他";

    public Long getPasswordId() {
        return PasswordId;
    }

    public void setPasswordId(Long passwordId) throws IllegalAccessException {
        if(passwordId<=0){
            throw new IllegalAccessException("ID不为1");
        }
        PasswordId = passwordId;
    }

    public String getPassword() {
        return Password;
    }

    public void setPassword(String password) {
        Password = password;
    }

    public String getNotes() {
        return Notes;
    }

    public void setNotes(String notes) {
        Notes = notes;
    }

    public String getName() {
        return PasswordName;
    }

    public void setName(String name) {
        PasswordName = name;
    }

    public String getUrl() {
        return Url;
    }

    public void setUrl(String url) {
        Url = url;
    }

    public String getCategory() {
        return Category;
    }

    public void setCategory(String category) {
        Category = category;
    }

    public Password(Long passwordId,String name, String password, String notes, String url) {
        PasswordName = name;
        Password = password;
        PasswordId = passwordId;
        Notes = notes;
        Url = url;
    }

    public Password(){}

}
