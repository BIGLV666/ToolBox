package com.example.toolbox.agent.network;

import com.example.toolbox.agent.repository.AgentRepository;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentClientNoteLimitTest {
    @Test
    public void keepsDefaultLimitForOrdinaryNoteSearch() {
        assertFalse(AgentClient.allowsExpandedNoteResults(history("帮我找一下项目计划笔记")));
        assertFalse(AgentClient.allowsExpandedNoteResults(history("找最近3条笔记")));
    }

    @Test
    public void allowsMoreOnlyWhenUserExplicitlyRequestsIt() {
        assertTrue(AgentClient.allowsExpandedNoteResults(history("把所有项目笔记都找出来")));
        assertTrue(AgentClient.allowsExpandedNoteResults(history("给我最近 8 篇笔记")));
        assertTrue(AgentClient.allowsExpandedNoteResults(history("再多给我一些笔记")));
        assertTrue(AgentClient.allowsExpandedNoteResults(history("找五篇旅行笔记")));
    }

    private static List<AgentRepository.ChatMessage> history(String prompt) {
        return Arrays.asList(
                new AgentRepository.ChatMessage("assistant", "好的", null, null),
                new AgentRepository.ChatMessage("user", prompt, null, null)
        );
    }
}
