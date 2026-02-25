package com.dev.echodrop.adapters;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.dev.echodrop.databinding.ItemChatListBinding;
import com.dev.echodrop.db.ChatEntity;

/**
 * RecyclerView adapter for the private chat list.
 *
 * <p>Displays avatar initial, chat name/code, last message preview,
 * relative timestamp, and unread badge.</p>
 */
public class ChatListAdapter extends ListAdapter<ChatEntity, ChatListAdapter.ChatViewHolder> {

    /** Click listener for chat items. */
    public interface OnChatClickListener {
        void onChatClick(@NonNull ChatEntity chat);
    }

    private OnChatClickListener listener;

    public ChatListAdapter() {
        super(DIFF_CALLBACK);
    }

    public void setOnChatClickListener(OnChatClickListener listener) {
        this.listener = listener;
    }

    // ──────────────────── ViewHolder ────────────────────

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final ItemChatListBinding binding = ItemChatListBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ChatViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ChatViewHolder extends RecyclerView.ViewHolder {

        private final ItemChatListBinding binding;

        ChatViewHolder(@NonNull ItemChatListBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setOnClickListener(v -> {
                final int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onChatClick(getItem(pos));
                }
            });
        }

        void bind(@NonNull ChatEntity chat) {
            // Avatar initial
            binding.avatarInitial.setText(String.valueOf(chat.getInitial()));

            // Name — display name or formatted code
            binding.chatName.setText(chat.getDisplayName());

            // Preview
            final String preview = chat.getLastMessagePreview();
            if (preview != null && !preview.isEmpty()) {
                binding.chatPreview.setText(preview);
                binding.chatPreview.setVisibility(android.view.View.VISIBLE);
            } else {
                binding.chatPreview.setVisibility(android.view.View.GONE);
            }

            // Relative timestamp
            final long time = chat.getLastMessageTime() > 0
                    ? chat.getLastMessageTime()
                    : chat.getCreatedAt();
            if (time > 0) {
                binding.chatTime.setText(DateUtils.getRelativeTimeSpanString(
                        time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE));
                binding.chatTime.setVisibility(android.view.View.VISIBLE);
            } else {
                binding.chatTime.setVisibility(android.view.View.GONE);
            }

            // Unread badge
            if (chat.getUnreadCount() > 0) {
                binding.unreadBadge.setText(String.valueOf(chat.getUnreadCount()));
                binding.unreadBadge.setVisibility(android.view.View.VISIBLE);
            } else {
                binding.unreadBadge.setVisibility(android.view.View.GONE);
            }
        }
    }

    // ──────────────────── DiffUtil ────────────────────

    private static final DiffUtil.ItemCallback<ChatEntity> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ChatEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull ChatEntity oldItem,
                                               @NonNull ChatEntity newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull ChatEntity oldItem,
                                                  @NonNull ChatEntity newItem) {
                    return oldItem.getLastMessageTime() == newItem.getLastMessageTime()
                            && oldItem.getUnreadCount() == newItem.getUnreadCount()
                            && java.util.Objects.equals(
                                    oldItem.getLastMessagePreview(),
                                    newItem.getLastMessagePreview());
                }
            };
}
