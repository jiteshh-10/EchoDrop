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

    /** Long-press listener for room management actions. */
    public interface OnChatLongPressListener {
        void onChatLongPress(@NonNull ChatEntity chat);
    }

    private OnChatClickListener listener;
    private OnChatLongPressListener longPressListener;

    public ChatListAdapter() {
        super(DIFF_CALLBACK);
    }

    public void setOnChatClickListener(OnChatClickListener listener) {
        this.listener = listener;
    }

    public void setOnChatLongPressListener(OnChatLongPressListener listener) {
        this.longPressListener = listener;
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
            binding.getRoot().setOnLongClickListener(v -> {
                final int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && longPressListener != null) {
                    longPressListener.onChatLongPress(getItem(pos));
                    return true;
                }
                return false;
            });
        }

        void bind(@NonNull ChatEntity chat) {
            final android.content.Context context = binding.getRoot().getContext();
            // Avatar initial
            binding.avatarInitial.setText(String.valueOf(chat.getInitial()));

            // Name — display name or formatted code
            binding.chatName.setText(chat.getDisplayName());
            binding.chatCode.setText(ChatEntity.formatCode(chat.getCode()));
                binding.getRoot().setContentDescription(context.getString(
                    com.dev.echodrop.R.string.a11y_room_item,
                    chat.getDisplayName(),
                    ChatEntity.formatCode(chat.getCode())));

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
                binding.unreadBadge.setContentDescription(context.getString(
                        com.dev.echodrop.R.string.a11y_room_unread,
                        chat.getUnreadCount()));
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
