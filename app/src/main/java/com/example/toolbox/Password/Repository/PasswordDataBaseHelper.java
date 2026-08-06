package com.example.toolbox.Password.Repository;

import android.content.Context;

import com.example.toolbox.repository.ToolboxDatabaseHelper;

/**
 * 密码模块旧版数据库帮助类的兼容入口。
 *
 * <p>该类不再维护独立数据库或版本，只继承统一的 {@link ToolboxDatabaseHelper}。保留类名是为了兼容仍引用
 * 旧类型的调用代码；新代码应直接依赖统一帮助类，避免再次形成模块级数据库版本分支。</p>
 *
 * @deprecated 所有模块统一使用 {@link ToolboxDatabaseHelper}。
 */
@Deprecated
public final class PasswordDataBaseHelper extends ToolboxDatabaseHelper {
    public PasswordDataBaseHelper(Context context) {
        super(context);
    }
}
