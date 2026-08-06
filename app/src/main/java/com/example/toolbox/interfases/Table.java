package com.example.toolbox.interfases;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
/**
 * 声明实体对应的 SQLite 表名。
 *
 * <p>注解保留到运行期，供反射映射读取。{@link #value()} 必须是非空且仅由字母、数字和下划线组成的合法
 * SQL 标识符，并且首字符不能是数字。</p>
 */
public @interface Table{
    /** SQLite 表名，不包含引号或其他 SQL 片段。 */
    String value();
}
