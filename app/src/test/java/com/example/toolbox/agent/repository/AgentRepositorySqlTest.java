package com.example.toolbox.agent.repository;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class AgentRepositorySqlTest {
    @Test
    public void acceptsSingleSelectFromNote() {
        String sql = "SELECT title, content\nFROM note WHERE title LIKE '%计划%' ORDER BY updated_at DESC";

        assertEquals(sql, AgentRepository.validateNoteSql(sql));
    }

    @Test
    public void rejectsOtherTablesAndCompoundQueries() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentRepository.validateNoteSql("SELECT * FROM password"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRepository.validateNoteSql("SELECT * FROM note JOIN password ON 1=1"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRepository.validateNoteSql("SELECT title FROM note UNION SELECT name FROM sqlite_master"));
    }

    @Test
    public void rejectsWritesAndMultipleStatements() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentRepository.validateNoteSql("DELETE FROM note"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRepository.validateNoteSql("SELECT * FROM note; DELETE FROM note"));
    }
}
