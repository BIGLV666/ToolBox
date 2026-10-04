package com.example.toolbox.smoking;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.toolbox.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 抽烟记录列表适配器，支持日期分组头。
 *
 * <p>每个日期分组显示「日期 + 当天总数」，下面展示该日所有记录的精确时间；
 * 长按记录行触发删除回调，方便误操作修正。</p>
 */
public class SmokingListAdapter extends ListAdapter<SmokingListItem, RecyclerView.ViewHolder> {

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private final OnDeleteListener onDelete;

    public interface OnDeleteListener {
        void onDelete(SmokingRecord record);
    }

    public SmokingListAdapter(OnDeleteListener onDelete) {
        super(DIFF);
        this.onDelete = onDelete;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == SmokingListItem.TYPE_DATE_HEADER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_smoking_date_header, parent, false);
            return new DateHeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_smoking_record, parent, false);
            return new RecordViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SmokingListItem item = getItem(position);
        if (holder instanceof DateHeaderViewHolder) {
            DateHeaderViewHolder h = (DateHeaderViewHolder) holder;
            h.tvDate.setText(item.getDateText());
            h.tvCount.setText(item.getDayCount() + "支");
        } else if (holder instanceof RecordViewHolder) {
            RecordViewHolder h = (RecordViewHolder) holder;
            SmokingRecord record = item.getRecord();
            h.tvTime.setText(TIME_FMT.format(new Date(record.getSmokedAt())));
            h.tvIndex.setText(String.valueOf(position));
            h.itemView.setOnLongClickListener(v -> {
                onDelete.onDelete(record);
                return true;
            });
        }
    }

    static class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView tvDate;
        final TextView tvCount;

        DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.smoking_date_text);
            tvCount = itemView.findViewById(R.id.smoking_date_count);
        }
    }

    static class RecordViewHolder extends RecyclerView.ViewHolder {
        final TextView tvIndex;
        final TextView tvTime;

        RecordViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.smoking_item_index);
            tvTime = itemView.findViewById(R.id.smoking_item_time);
        }
    }

    private static final DiffUtil.ItemCallback<SmokingListItem> DIFF =
            new DiffUtil.ItemCallback<SmokingListItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull SmokingListItem a, @NonNull SmokingListItem b) {
                    if (a.getType() != b.getType()) return false;
                    if (a.getType() == SmokingListItem.TYPE_DATE_HEADER) {
                        return a.getDateText().equals(b.getDateText());
                    } else {
                        return a.getRecord().getRecordId() == b.getRecord().getRecordId();
                    }
                }

                @Override
                public boolean areContentsTheSame(@NonNull SmokingListItem a, @NonNull SmokingListItem b) {
                    if (a.getType() == SmokingListItem.TYPE_DATE_HEADER) {
                        return a.getDayCount() == b.getDayCount();
                    } else {
                        return a.getRecord().getSmokedAt() == b.getRecord().getSmokedAt();
                    }
                }
            };
}
