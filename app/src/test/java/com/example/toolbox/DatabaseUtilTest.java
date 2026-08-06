package com.example.toolbox;

import com.example.toolbox.Password.model.Password;
import com.example.toolbox.util.DatabaseUtil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DatabaseUtilTest {
    @Test
    public void readsPasswordMetadata() {
        DatabaseUtil.EntityMeta<Password> meta = DatabaseUtil.getEntityMeta(Password.class);

        assertEquals("password", meta.getTableName());
        assertEquals("password_id", meta.getIdColumnName());
    }

    @Test
    public void createsPasswordTableSql() {
        assertEquals(
                "CREATE TABLE IF NOT EXISTS `password` ("
                        + "`password_id` INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "`password_name` VARCHAR(255) NOT NULL, "
                        + "`password` VARCHAR(255) NOT NULL, "
                        + "`notes` VARCHAR(255), `url` VARCHAR(255), "
                        + "`category` VARCHAR(255) NOT NULL DEFAULT '其他')",
                DatabaseUtil.createTableSql(Password.class)
        );
    }
}
