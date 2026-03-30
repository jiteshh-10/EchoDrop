package com.dev.echodrop.screens;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.dev.echodrop.R;
import com.dev.echodrop.adapters.ChatMessageAdapter;
import com.dev.echodrop.crypto.ChatCrypto;
import com.dev.echodrop.databinding.ScreenChatConversationBinding;
import com.dev.echodrop.db.ChatEntity;
import com.dev.echodrop.repository.ChatRepo;
import com.dev.echodrop.viewmodels.ChatViewModel;

import javax.crypto.SecretKey;

/**
 * Screen for a single chat conversation.
 *
 * <p>Decrypts messages on-the-fly using a key derived from the chat code.
 * The key is held in memory only for the fragment's lifecycle and never
 * persisted.</p>
 */
public class ChatConversationFragment extends Fragment {

    private static final String ARG_CHAT_ID = "chat_id";
    private static final String ARG_CHAT_CODE = "chat_code";
    private static final String ARG_CHAT_NAME = "chat_name";

    private ScreenChatConversationBinding binding;
    private ChatViewModel viewModel;
    private ChatMessageAdapter adapter;

    private String chatId;
    private String chatCode;
    private String chatName;
    private SecretKey decryptionKey;

    /** Timestamp of the last sync event (0 = never synced). */
    private long lastSyncTimestamp;

    /** Handler for periodic sync bar updates. */
    private final Handler syncBarHandler = new Handler(Looper.getMainLooper());

    /** Runnable that refreshes the sync bar text periodically. */
    private final Runnable syncBarUpdater = new Runnable() {
        @Override
        public void run() {
            updateSyncBarText();
            syncBarHandler.postDelayed(this, 30_000); // Update every 30 seconds
        }
    };

    /** Previous message count for detecting new incoming messages. */
    private int previousMessageCount;

    // ──────────────────── Factory ────────────────────

    @NonNull
    public static ChatConversationFragment newInstance(@NonNull String chatId,
                                                       @NonNull String chatCode,
                                                       @NonNull String chatName) {
        final ChatConversationFragment fragment = new ChatConversationFragment();
        final Bundle args = new Bundle();
        args.putString(ARG_CHAT_ID, chatId);
        args.putString(ARG_CHAT_CODE, chatCode);
        args.putString(ARG_CHAT_NAME, chatName);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenChatConversationBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final Bundle args = requireArguments();
        chatId = args.getString(ARG_CHAT_ID, "");
        chatCode = args.getString(ARG_CHAT_CODE, "");
        chatName = args.getString(ARG_CHAT_NAME, "");

        // Derive key once for this session
        decryptionKey = ChatCrypto.deriveKey(chatCode);

        viewModel = new ViewModelProvider(requireActivity()).get(ChatViewModel.class);
        viewModel.clearUnread(chatId);

        setupToolbar();
        setupRecyclerView();
        setupInput();
        setupSyncBar();
        observeMessages();
    }

    // ──────────────────── Setup ────────────────────

    private void setupToolbar() {
        binding.toolbarTitle.setText(chatName);
        binding.toolbarCode.setText(getString(
            R.string.chat_toolbar_code_format,
            ChatEntity.formatCode(chatCode)));

        // Avatar initial
        final char initial = chatName != null && !chatName.isEmpty()
                ? Character.toUpperCase(chatName.charAt(0))
                : chatCode.charAt(0);
        binding.toolbarAvatar.setText(String.valueOf(initial));

        binding.btnBack.setOnClickListener(v -> navigateBack());
    }

    private void setupRecyclerView() {
        adapter = new ChatMessageAdapter();
        adapter.setDecryptionKey(decryptionKey);

        final LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true); // Start from the bottom
        binding.messageList.setLayoutManager(lm);
        binding.messageList.setAdapter(adapter);
    }

    private void setupInput() {
        // Enable/disable send button based on input
        binding.inputMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                final boolean hasText = s != null && s.toString().trim().length() > 0;
                binding.btnSend.setAlpha(hasText ? 1.0f : 0.5f);
                binding.btnSend.setEnabled(hasText);
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        binding.btnSend.setOnClickListener(v -> sendMessage());
        binding.btnSend.setEnabled(false);
    }

    private void setupSyncBar() {
        // Register for sync events from ChatRepo
        final ChatRepo repo = viewModel.getRepo();
        repo.setSyncEventListener((syncedChatId, timestamp) -> {
            if (chatId.equals(syncedChatId)) {
                lastSyncTimestamp = timestamp;
                syncBarHandler.post(this::updateSyncBarText);
            }
        });

        // Start periodic updates
        syncBarHandler.post(syncBarUpdater);
    }

    /** Updates the sync bar text based on time since last sync. */
    private void updateSyncBarText() {
        if (binding == null) return;

        if (lastSyncTimestamp == 0) {
            binding.syncText.setText(R.string.chat_sync_hint);
            binding.syncDot.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.echo_text_muted)));
            return;
        }

        final long elapsed = System.currentTimeMillis() - lastSyncTimestamp;
        final long minutes = elapsed / (60 * 1000L);
        final long hours = elapsed / (60 * 60 * 1000L);

        final String text;
        if (minutes < 1) {
            text = getString(R.string.chat_sync_just_now);
        } else if (hours < 1) {
            text = getString(R.string.chat_sync_minutes_ago, (int) minutes);
        } else {
            text = getString(R.string.chat_sync_hours_ago, (int) hours);
        }

        binding.syncText.setText(text);
        // Green dot when recently synced
        binding.syncDot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.echo_positive_accent)));
    }

    private void observeMessages() {
        viewModel.getMessages(chatId).observe(getViewLifecycleOwner(), messages -> {
            final int newCount = messages != null ? messages.size() : 0;
            final boolean hasNewIncoming = newCount > previousMessageCount
                    && previousMessageCount > 0;

            adapter.submitList(messages, () -> {
                // Scroll to bottom on new messages
                if (messages != null && !messages.isEmpty()) {
                    binding.messageList.scrollToPosition(messages.size() - 1);
                }
            });

            // Animate incoming messages with fade/slide if this is a sync event
            if (hasNewIncoming) {
                animateNewMessages();
            }

            previousMessageCount = newCount;

            // Empty state
            final boolean empty = messages == null || messages.isEmpty();
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        });
    }

    /**
     * Animates newly arrived messages with a fade + slide (250ms).
     * Applied to the last visible item to communicate arrival.
     */
    private void animateNewMessages() {
        binding.messageList.post(() -> {
            final int childCount = binding.messageList.getChildCount();
            if (childCount == 0) return;

            // Animate the last (newest) child
            final View lastChild = binding.messageList.getChildAt(childCount - 1);
            if (lastChild == null) return;

            lastChild.setAlpha(0f);
            lastChild.setTranslationY(-8 * getResources().getDisplayMetrics().density);

            lastChild.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        });
    }

    // ──────────────────── Actions ────────────────────

    private void sendMessage() {
        final Editable editable = binding.inputMessage.getText();
        if (editable == null) return;

        final String text = editable.toString().trim();
        if (text.isEmpty()) return;

        viewModel.sendMessage(chatId, text, chatCode);
        binding.inputMessage.setText("");
    }

    // ──────────────────── Navigation ────────────────────

    private void navigateBack() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        syncBarHandler.removeCallbacks(syncBarUpdater);
        // Unregister sync listener
        final ChatRepo repo = viewModel.getRepo();
        repo.setSyncEventListener(null);
        binding = null;
        decryptionKey = null; // Clear key from memory
    }
}
