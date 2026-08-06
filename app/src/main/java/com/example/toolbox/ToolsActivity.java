package com.example.toolbox;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.toolbox.agent.ui.AgentActivity;
import com.example.toolbox.note.NoteActivity;

/**
 * 工具库导航页。
 *
 * <p>助手和笔记直接压入新的 Activity；密码模块实际位于 {@link MainActivity} 内，因此导航时会清除其
 * 上方页面并复用栈中已有的首页实例。若首页已位于栈顶，{@code SINGLE_TOP} 会让参数经
 * {@code onNewIntent} 交付，避免创建重复首页。启动后结束本页，使返回键不会再次回到工具库。</p>
 */
public final class ToolsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tools);
        ((com.google.android.material.appbar.MaterialToolbar)findViewById(R.id.tools_toolbar))
                .setNavigationOnClickListener(view -> finish());
        findViewById(R.id.library_agent).setOnClickListener(view -> startActivity(new Intent(this, AgentActivity.class)));
        findViewById(R.id.library_notes).setOnClickListener(view -> startActivity(new Intent(this, NoteActivity.class)));
        findViewById(R.id.library_password).setOnClickListener(view -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("open_password", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }
}
