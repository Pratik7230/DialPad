package com.pratikpatil.dialpad;

import android.provider.CallLog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * RecyclerView adapter for displaying call log entries.
 */
public class CallLogAdapter extends RecyclerView.Adapter<CallLogAdapter.ViewHolder> {

    /**
     * Listener for click events on call log items.
     */
    public interface OnItemActionListener {
        void onItemClick(CallLogEntry entry);

        void onDeleteClick(CallLogEntry entry);
    }

    private List<CallLogEntry> callLogs;
    private OnItemActionListener listener;
    private boolean showDelete;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());

    public CallLogAdapter(List<CallLogEntry> callLogs, OnItemActionListener listener) {
        this(callLogs, listener, true);
    }

    public CallLogAdapter(List<CallLogEntry> callLogs, OnItemActionListener listener, boolean showDelete) {
        this.callLogs = callLogs;
        this.listener = listener;
        this.showDelete = showDelete;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_call_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CallLogEntry entry = callLogs.get(position);

        // Set caller name / number
        holder.tvCallerName.setText(entry.getDisplayName());

        // Set call type icon and label
        switch (entry.getCallType()) {
            case CallLog.Calls.INCOMING_TYPE:
                holder.ivCallType.setImageResource(R.drawable.ic_call_incoming);
                holder.tvCallTypeLabel.setText(R.string.call_type_incoming);
                holder.tvCallerName.setTextColor(
                        holder.itemView.getContext().getColor(R.color.number_text_color));
                break;
            case CallLog.Calls.OUTGOING_TYPE:
                holder.ivCallType.setImageResource(R.drawable.ic_call_outgoing);
                holder.tvCallTypeLabel.setText(R.string.call_type_outgoing);
                holder.tvCallerName.setTextColor(
                        holder.itemView.getContext().getColor(R.color.number_text_color));
                break;
            case CallLog.Calls.MISSED_TYPE:
                holder.ivCallType.setImageResource(R.drawable.ic_call_missed);
                holder.tvCallTypeLabel.setText(R.string.call_type_missed);
                holder.tvCallerName.setTextColor(
                        holder.itemView.getContext().getColor(R.color.missed_call_red));
                break;
            case CallLog.Calls.REJECTED_TYPE:
                holder.ivCallType.setImageResource(R.drawable.ic_call_missed);
                holder.tvCallTypeLabel.setText(R.string.call_type_rejected);
                holder.tvCallerName.setTextColor(
                        holder.itemView.getContext().getColor(R.color.missed_call_red));
                break;
            default:
                holder.ivCallType.setImageResource(R.drawable.ic_call_incoming);
                holder.tvCallTypeLabel.setText(R.string.call_type_unknown);
                holder.tvCallerName.setTextColor(
                        holder.itemView.getContext().getColor(R.color.number_text_color));
                break;
        }

        // Set call duration
        holder.tvCallDuration.setText(formatDuration(entry.getDuration()));

        // Set date and time
        holder.tvCallDate.setText(formatDate(entry.getDate()));
        holder.tvCallTime.setText(timeFormat.format(new Date(entry.getDate())));

        // Click listener to dial the number
        if (listener != null) {
            holder.itemView.setClickable(true);
            holder.itemView.setOnClickListener(v -> listener.onItemClick(entry));
        } else {
            holder.itemView.setClickable(false);
            holder.itemView.setOnClickListener(null);
        }

        if (showDelete) {
            holder.btnDeleteLog.setVisibility(View.VISIBLE);
            holder.btnDeleteLog.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClick(entry);
                }
            });
        } else {
            holder.btnDeleteLog.setVisibility(View.GONE);
            holder.btnDeleteLog.setOnClickListener(null);
        }
    }

    @Override
    public int getItemCount() {
        return callLogs != null ? callLogs.size() : 0;
    }

    /**
     * Updates the adapter data and refreshes the list.
     */
    public void updateData(List<CallLogEntry> newData) {
        this.callLogs = newData;
        notifyDataSetChanged();
    }

    /**
     * Formats call duration in a human-readable way.
     */
    private String formatDuration(long seconds) {
        if (seconds == 0) {
            return "0 sec";
        }
        long hours = TimeUnit.SECONDS.toHours(seconds);
        long minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format(Locale.getDefault(), "%d min %d sec", minutes, secs);
        } else {
            return String.format(Locale.getDefault(), "%d sec", secs);
        }
    }

    /**
     * Formats the date relative to today (Today, Yesterday, or a specific date).
     */
    private String formatDate(long dateMillis) {
        Calendar callCal = Calendar.getInstance();
        callCal.setTimeInMillis(dateMillis);

        Calendar todayCal = Calendar.getInstance();

        if (isSameDay(callCal, todayCal)) {
            return "Today";
        }

        todayCal.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(callCal, todayCal)) {
            return "Yesterday";
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM", Locale.getDefault());
        return dateFormat.format(new Date(dateMillis));
    }

    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCallType;
        TextView tvCallerName;
        TextView tvCallTypeLabel;
        TextView tvCallDuration;
        TextView tvCallDate;
        TextView tvCallTime;
        ImageButton btnDeleteLog;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCallType = itemView.findViewById(R.id.ivCallType);
            tvCallerName = itemView.findViewById(R.id.tvCallerName);
            tvCallTypeLabel = itemView.findViewById(R.id.tvCallTypeLabel);
            tvCallDuration = itemView.findViewById(R.id.tvCallDuration);
            tvCallDate = itemView.findViewById(R.id.tvCallDate);
            tvCallTime = itemView.findViewById(R.id.tvCallTime);
            btnDeleteLog = itemView.findViewById(R.id.btnDeleteLog);
        }
    }
}
