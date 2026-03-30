package com.dev.echodrop.screens;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.dev.echodrop.R;
import com.dev.echodrop.adapters.MessageAdapter;
import com.dev.echodrop.databinding.ScreenSavedMessagesBinding;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.util.ToolbarLogoAnimator;
import com.dev.echodrop.viewmodels.MessageViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays all user-saved broadcast messages.
 */
public class SavedMessagesFragment extends Fragment {

    private ScreenSavedMessagesBinding binding;
    private MessageAdapter adapter;
    private MessageViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenSavedMessagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupToolbar();
        setupRecycler();
        observeSavedMessages();
    }

    private void setupToolbar() {
        ToolbarLogoAnimator.apply(binding.savedToolbar);
        binding.savedToolbar.setNavigationOnClickListener(v -> navigateBack());
    }

    private void setupRecycler() {
        adapter = new MessageAdapter();
        adapter.setOnMessageClickListener(this::openMessageDetail);

        binding.savedMessageList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.savedMessageList.setAdapter(adapter);

        DividerItemDecoration divider = new DividerItemDecoration(
                requireContext(), DividerItemDecoration.VERTICAL);
        binding.savedMessageList.addItemDecoration(divider);
        binding.savedMessageList.setVerticalScrollBarEnabled(false);
    }

    private void observeSavedMessages() {
        viewModel = new ViewModelProvider(requireActivity()).get(MessageViewModel.class);
        viewModel.getSavedMessages().observe(getViewLifecycleOwner(), messages -> {
            final List<MessageEntity> safeList = messages == null
                    ? new ArrayList<>()
                    : new ArrayList<>(messages);
            adapter.submitList(safeList);
            updateEmptyState(safeList.isEmpty());
        });
    }

    private void updateEmptyState(boolean isEmpty) {
        binding.savedEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.savedMessageList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void openMessageDetail(@NonNull MessageEntity message) {
        MessageDetailFragment detailFragment = MessageDetailFragment.newInstance(message.getId());
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, detailFragment)
                .addToBackStack("detail")
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