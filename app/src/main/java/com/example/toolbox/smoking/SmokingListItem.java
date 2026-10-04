package com.example.toolbox.smoking;

/**
 * 抽烟列表项的包装类，支持日期分组头和记录两种类型。
 */
public class SmokingListItem {
    
    public static final int TYPE_DATE_HEADER = 0;
    public static final int TYPE_RECORD = 1;
    
    private final int type;
    private final String dateText;
    private final int dayCount;
    private final SmokingRecord record;
    
    /** 创建日期分组头。 */
    public static SmokingListItem header(String dateText, int dayCount) {
        return new SmokingListItem(TYPE_DATE_HEADER, dateText, dayCount, null);
    }
    
    /** 创建普通记录项。 */
    public static SmokingListItem record(SmokingRecord record) {
        return new SmokingListItem(TYPE_RECORD, null, 0, record);
    }
    
    private SmokingListItem(int type, String dateText, int dayCount, SmokingRecord record) {
        this.type = type;
        this.dateText = dateText;
        this.dayCount = dayCount;
        this.record = record;
    }
    
    public int getType() {
        return type;
    }
    
    public String getDateText() {
        return dateText;
    }
    
    public int getDayCount() {
        return dayCount;
    }
    
    public SmokingRecord getRecord() {
        return record;
    }
}
