package com.example.toolbox.smoking;

import com.example.toolbox.enums.passwordenum.ColumType;
import com.example.toolbox.interfases.Column;
import com.example.toolbox.interfases.Table;
import com.example.toolbox.interfases.TableFiledId;

/**
 * 抽烟记录实体。
 *
 * <p>每条记录对应一支烟，{@code smoked_at} 存毫秒时间戳，精确到秒足够统计使用。
 * 数据极简：只记时间，不存冗余字段，日/月聚合由查询层完成。</p>
 */
@Table(value = "smoking_record")
public class SmokingRecord {

    @TableFiledId
    private long recordId;

    /**
     * 抽这支烟的时间戳（Unix 毫秒）。由插入方填入，不依赖数据库时间函数，
     * 方便将来支持手动补录历史记录。
     */
    @Column(value = "smoked_at", type = ColumType.BIGINT, nullable = false)
    private long smokedAt;

    public SmokingRecord() {}

    public SmokingRecord(long smokedAt) {
        this.smokedAt = smokedAt;
    }

    public long getRecordId() { return recordId; }

    public long getSmokedAt() { return smokedAt; }

    public void setSmokedAt(long smokedAt) { this.smokedAt = smokedAt; }
}
