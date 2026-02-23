package com.dev.echodrop.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ItemMessageCardBinding;
import com.dev.echodrop.models.Message;

import java.util.concurrent.TimeUnit;

public class MessageAdapter extends ListAdapter<Message, MessageAdapter.MessageViewHolder> {

    public MessageAdapter() {
        super(DIFF_CALLBACK);
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        ItemMessageCardBinding binding = ItemMessageCardBinding.inflate(inflater, parent, false);
        return new MessageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageCardBinding binding;

        MessageViewHolder(ItemMessageCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Message message) {
            Context context = binding.getRoot().getContext();
            binding.messagePreview.setText(message.getText());
            binding.scopeBadge.setText(getScopeLabel(context, message.getScope()));
            if (message.getScope() == Message.Scope.LOCAL) {
                binding.scopeBadge.setBackgroundResource(R.drawable.bg_badge_positive);
                binding.scopeBadge.setTextColor(ContextCompat.getColor(context, R.color.echo_positive_accent));
            } else {
                binding.scopeBadge.setBackgroundResource(R.drawable.bg_badge_primary);
                binding.scopeBadge.setTextColor(ContextCompat.getColor(context, R.color.echo_primary_accent));
            }
            binding.ttlText.setText(context.getString(R.string.message_ttl_expires_in, formatTtl(message.getExpiresAt())));

            if (message.getPriority() == Message.Priority.ALERT) {
                binding.priorityLabel.setVisibility(View.VISIBLE);
                binding.priorityLabel.setText(R.string.message_priority_urgent);
                binding.priorityLabel.setTextColor(ContextCompat.getColor(context, R.color.echo_alert_accent));
                binding.priorityDot.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.echo_alert_accent));
                binding.unreadBorder.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.echo_alert_accent));
            } else if (message.getPriority() == Message.Priority.BULK) {
                binding.priorityLabel.setVisibility(View.GONE);
                binding.priorityDot.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.echo_muted_disabled));
                binding.unreadBorder.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.echo_muted_disabled));
            } else {
                binding.priorityLabel.setVisibility(View.GONE);
                binding.priorityDot.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.echo_primary_accent));
                binding.unreadBorder.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.echo_primary_accent));
            }

            binding.unreadBorder.setVisibility(message.isRead() ? View.INVISIBLE : View.VISIBLE);
        }

        private String getScopeLabel(Context context, Message.Scope scope) {
            if (scope == Message.Scope.LOCAL) {
                return context.getString(R.string.message_scope_nearby);
            }
            if (scope == Message.Scope.ZONE) {
                return context.getString(R.string.message_scope_area);
            }
            return context.getString(R.string.message_scope_event);
        }

        private String formatTtl(long expiresAt) {
            long remaining = Math.max(0, expiresAt - System.currentTimeMillis());
            long hours = TimeUnit.MILLISECONDS.toHours(remaining);
            long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) - TimeUnit.HOURS.toMinutes(hours);
            if (hours > 0) {
                if (minutes > 0) {
                    return hours + "h " + minutes + "m";
                }
                return hours + "h";
            }
            return minutes + "m";
        }
    }

    private static final DiffUtil.ItemCallback<Message> DIFF_CALLBACK = new DiffUtil.ItemCallback<Message>() {
        @Override
        public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.getId().equals(newItem.getId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.getText().equals(newItem.getText())
                    && oldItem.getScope() == newItem.getScope()
                    && oldItem.getPriority() == newItem.getPriority()
                    && oldItem.getExpiresAt() == newItem.getExpiresAt()
                    && oldItem.isRead() == newItem.isRead();
        }
    };
}
