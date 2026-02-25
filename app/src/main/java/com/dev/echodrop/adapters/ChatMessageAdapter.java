package com.dev.echodrop.adapters;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.dev.echodrop.R;
import com.dev.echodrop.crypto.ChatCrypto;
import com.dev.echodrop.databinding.ItemChatMessageIncomingBinding;
import com.dev.echodrop.databinding.ItemChatMessageOutgoingBinding;
import com.dev.echodrop.db.ChatMessageEntity;

import java.util.Date;

import javax.crypto.SecretKey;

/**
 * RecyclerView adapter for chat messages.
 *
 * <p>Two view types: outgoing (right-aligned, type 0) and incoming
 * (left-aligned with accent border, type 1). Decrypts ciphertext
 * on bind using the supplied {@link SecretKey}.</p>
 */
public class ChatMessageAdapter extends ListAdapter<ChatMessageEntity, RecyclerView.ViewHolder> {

    private static final int TYPE_OUTGOING = 0;
    private static final int TYPE_INCOMING = 1;

    @Nullable
    private SecretKey decryptionKey;

    public ChatMessageAdapter() {
        super(DIFF_CALLBACK);
    }

    /** Sets the AES key used to decrypt message text on bind. */
    public void setDecryptionKey(@Nullable SecretKey key) {
        this.decryptionKey = key;
    }

    // ──────────────────── View types ────────────────────

    @Override
    public int getItemViewType(int position) {
        return getItem(position).isOutgoing() ? TYPE_OUTGOING : TYPE_INCOMING;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_OUTGOING) {
            return new OutgoingHolder(
                    ItemChatMessageOutgoingBinding.inflate(inflater, parent, false));
        } else {
            return new IncomingHolder(
                    ItemChatMessageIncomingBinding.inflate(inflater, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final ChatMessageEntity msg = getItem(position);
        if (holder instanceof OutgoingHolder) {
            ((OutgoingHolder) holder).bind(msg);
        } else if (holder instanceof IncomingHolder) {
            ((IncomingHolder) holder).bind(msg);
        }
    }

    // ──────────────────── Helpers ────────────────────

    /** Decrypts the message text or returns a fallback. */
    @NonNull
    private String decryptText(@NonNull ChatMessageEntity msg) {
        if (decryptionKey == null) return "[encrypted]";
        try {
            return ChatCrypto.decrypt(msg.getText(), decryptionKey);
        } catch (Exception e) {
            return "[decryption error]";
        }
    }

    @NonNull
    private String formatTime(long millis) {
        return DateFormat.format("h:mm a", new Date(millis)).toString();
    }

    // ──────────────────── Outgoing holder ────────────────────

    class OutgoingHolder extends RecyclerView.ViewHolder {
        private final ItemChatMessageOutgoingBinding binding;

        OutgoingHolder(@NonNull ItemChatMessageOutgoingBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull ChatMessageEntity msg) {
            binding.messageText.setText(decryptText(msg));
            binding.messageTime.setText(formatTime(msg.getCreatedAt()));

            // Sync indicator
            final Context ctx = binding.getRoot().getContext();
            switch (msg.getSyncState()) {
                case ChatMessageEntity.SYNC_SYNCED:
                    binding.syncIndicator.setImageResource(R.drawable.ic_double_tick);
                    binding.syncIndicator.setColorFilter(
                            ContextCompat.getColor(ctx, R.color.echo_positive_accent));
                    break;
                case ChatMessageEntity.SYNC_SENT:
                    binding.syncIndicator.setImageResource(R.drawable.ic_tick);
                    binding.syncIndicator.setColorFilter(
                            ContextCompat.getColor(ctx, R.color.echo_text_secondary));
                    break;
                default: // PENDING
                    binding.syncIndicator.setImageResource(R.drawable.ic_tick);
                    binding.syncIndicator.setColorFilter(
                            ContextCompat.getColor(ctx, R.color.echo_text_muted));
                    break;
            }
        }
    }

    // ──────────────────── Incoming holder ────────────────────

    class IncomingHolder extends RecyclerView.ViewHolder {
        private final ItemChatMessageIncomingBinding binding;

        IncomingHolder(@NonNull ItemChatMessageIncomingBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull ChatMessageEntity msg) {
            binding.messageText.setText(decryptText(msg));
            binding.messageTime.setText(formatTime(msg.getCreatedAt()));
        }
    }

    // ──────────────────── DiffUtil ────────────────────

    private static final DiffUtil.ItemCallback<ChatMessageEntity> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ChatMessageEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull ChatMessageEntity oldItem,
                                               @NonNull ChatMessageEntity newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull ChatMessageEntity oldItem,
                                                  @NonNull ChatMessageEntity newItem) {
                    return oldItem.getSyncState() == newItem.getSyncState()
                            && oldItem.getText().equals(newItem.getText());
                }
            };
}
