package com.dev.echodrop.screens;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.dev.echodrop.R;
import com.dev.echodrop.adapters.ChatListAdapter;
import com.dev.echodrop.databinding.ScreenChatListBinding;
import com.dev.echodrop.db.ChatEntity;
import com.dev.echodrop.viewmodels.ChatViewModel;
import com.google.android.material.snackbar.Snackbar;

/**
 * Screen showing the list of private chats.
 *
 * <p>Features:
 * <ul>
 *   <li>Back button → returns to inbox</li>
 *   <li>"Join" toolbar action → dialog to enter a code</li>
 *   <li>FAB → navigate to {@link CreateChatFragment}</li>
 *   <li>Empty state when no chats exist</li>
 * </ul>
 * </p>
 */
public class PrivateChatListFragment extends Fragment {

    private ScreenChatListBinding binding;
    private ChatViewModel viewModel;
    private ChatListAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenChatListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(ChatViewModel.class);

        setupRecyclerView();
        setupListeners();
        observeChats();
    }

    // ──────────────────── Setup ────────────────────

    private void setupRecyclerView() {
        adapter = new ChatListAdapter();
        adapter.setOnChatClickListener(this::navigateToConversation);
        binding.chatList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.chatList.setAdapter(adapter);
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> navigateBack());
        binding.btnJoin.setOnClickListener(v -> showJoinDialog());
        binding.fabNewChat.setOnClickListener(v -> navigateToCreateChat());
    }

    private void observeChats() {
        viewModel.getChats().observe(getViewLifecycleOwner(), chats -> {
            adapter.submitList(chats);
            final boolean empty = chats == null || chats.isEmpty();
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.chatList.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    // ──────────────────── Join dialog ────────────────────

    private void showJoinDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint(R.string.chat_join_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setTextColor(getResources().getColor(R.color.echo_text_primary, null));
        input.setHintTextColor(getResources().getColor(R.color.echo_text_muted, null));
        input.setFontFeatureSettings("monospace");

        final FrameLayout container = new FrameLayout(requireContext());
        final int padding = getResources().getDimensionPixelSize(R.dimen.spacing_2);
        container.setPadding(padding, padding, padding, 0);
        container.addView(input);

        new AlertDialog.Builder(requireContext(), R.style.Theme_EchoDrop_Dialog)
                .setTitle(R.string.chat_join_title)
                .setView(container)
                .setPositiveButton(R.string.chat_join_button, (dialog, which) -> {
                    final String raw = ChatEntity.stripCode(input.getText().toString());
                    if (raw.length() != 8) {
                        Snackbar.make(binding.getRoot(),
                                R.string.chat_join_invalid, Snackbar.LENGTH_SHORT).show();
                        return;
                    }
                    joinChat(raw);
                })
                .setNegativeButton(R.string.post_cancel, null)
                .show();
    }

    private void joinChat(@NonNull String code) {
        viewModel.joinChat(code, (chat, alreadyExisted) ->
                requireActivity().runOnUiThread(() -> {
                    if (alreadyExisted) {
                        Snackbar.make(binding.getRoot(),
                                R.string.chat_already_exists, Snackbar.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(binding.getRoot(),
                                R.string.chat_joined, Snackbar.LENGTH_SHORT).show();
                    }
                    navigateToConversation(chat);
                }));
    }

    // ──────────────────── Navigation ────────────────────

    private void navigateToConversation(@NonNull ChatEntity chat) {
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(
                        android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragment_container, ChatConversationFragment.newInstance(
                        chat.getId(), chat.getCode(), chat.getDisplayName()))
                .addToBackStack(null)
                .commit();
    }

    private void navigateToCreateChat() {
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(
                        android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragment_container, new CreateChatFragment())
                .addToBackStack(null)
                .commit();
    }

    private void navigateBack() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
