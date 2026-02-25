package com.dev.echodrop.screens;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
        observeMessages();
    }

    // ──────────────────── Setup ────────────────────

    private void setupToolbar() {
        binding.toolbarTitle.setText(chatName);
        binding.toolbarCode.setText(ChatEntity.formatCode(chatCode));

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

    private void observeMessages() {
        viewModel.getMessages(chatId).observe(getViewLifecycleOwner(), messages -> {
            adapter.submitList(messages, () -> {
                // Scroll to bottom on new messages
                if (messages != null && !messages.isEmpty()) {
                    binding.messageList.scrollToPosition(messages.size() - 1);
                }
            });

            // Empty state
            final boolean empty = messages == null || messages.isEmpty();
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
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
        binding = null;
        decryptionKey = null; // Clear key from memory
    }
}
