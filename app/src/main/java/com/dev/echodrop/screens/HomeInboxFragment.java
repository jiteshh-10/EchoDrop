package com.dev.echodrop.screens;

import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dev.echodrop.R;
import com.dev.echodrop.adapters.MessageAdapter;
import com.dev.echodrop.components.PostComposerSheet;
import com.dev.echodrop.databinding.ScreenHomeInboxBinding;
import com.dev.echodrop.models.Message;
import com.dev.echodrop.viewmodels.MessageViewModel;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HomeInboxFragment extends Fragment implements PostComposerSheet.OnPostListener {

    private ScreenHomeInboxBinding binding;
    private MessageAdapter adapter;
    private MessageViewModel viewModel;
    private Tab activeTab = Tab.ALL;
    private String query = "";
    private List<Message> allMessages = new ArrayList<>();
    private ObjectAnimator syncDotAnimator;

    private enum Tab {
        ALL,
        ALERTS,
        CHATS
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ScreenHomeInboxBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupToolbar();
        setupRecycler();
        setupSearch();
        setupTabs();
        setupFabs();
        setupViewModel();
        // Seed with simulated nearby devices for demo/static baseline
        updateSyncIndicator(3);
    }

    private void setupToolbar() {
        binding.homeToolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);
        binding.homeToolbar.setNavigationContentDescription(R.string.content_menu);
        binding.homeToolbar.inflateMenu(R.menu.home_menu);
        binding.homeToolbar.setOnMenuItemClickListener(this::onMenuItemClicked);
    }

    private boolean onMenuItemClicked(MenuItem item) {
        if (item.getItemId() == R.id.action_post) {
            openPostComposer();
            return true;
        }
        return false;
    }

    private void setupRecycler() {
        adapter = new MessageAdapter();
        binding.messageList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.messageList.setAdapter(adapter);

        // Add divider decoration between items
        DividerItemDecoration divider = new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL);
        binding.messageList.addItemDecoration(divider);

        // Hide scrollbar for clean look
        binding.messageList.setVerticalScrollBarEnabled(false);
    }

    private void setupSearch() {
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString();
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // Search focus border transition (180ms)
        binding.searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                binding.searchContainer.setBackgroundResource(R.drawable.bg_search_input_focused);
            } else {
                binding.searchContainer.setBackgroundResource(R.drawable.bg_search_input);
            }
        });
    }

    private void setupTabs() {
        binding.tabAllContainer.setOnClickListener(v -> selectTab(Tab.ALL));
        binding.tabAlertsContainer.setOnClickListener(v -> selectTab(Tab.ALERTS));
        binding.tabChatsContainer.setOnClickListener(v -> selectTab(Tab.CHATS));
        selectTab(Tab.ALL);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupFabs() {
        binding.fabPost.setOnClickListener(v -> openPostComposer());
        binding.fabChats.setOnClickListener(v -> selectTab(Tab.CHATS));

        // FAB press scale animation (scale to 0.95 over 40ms)
        addFabPressAnimation(binding.fabPost);
        addFabPressAnimation(binding.fabChats);

        // Secondary FAB entrance animation (OvershootInterpolator)
        binding.fabChats.setScaleX(0f);
        binding.fabChats.setScaleY(0f);
        binding.fabChats.post(() -> {
            binding.fabChats.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .start();
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addFabPressAnimation(FloatingActionButton fab) {
        fab.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(40).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    break;
            }
            return false;
        });
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(requireActivity()).get(MessageViewModel.class);
        viewModel.getMessages().observe(getViewLifecycleOwner(), messages -> {
            allMessages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
            applyFilters();
        });
    }

    private void selectTab(Tab tab) {
        activeTab = tab;
        binding.tabAll.setTextColor(getColorForTab(tab == Tab.ALL));
        binding.tabAlerts.setTextColor(getColorForTab(tab == Tab.ALERTS));
        binding.tabChats.setTextColor(getColorForTab(tab == Tab.CHATS));
        binding.tabAllIndicator.setVisibility(tab == Tab.ALL ? View.VISIBLE : View.INVISIBLE);
        binding.tabAlertsIndicator.setVisibility(tab == Tab.ALERTS ? View.VISIBLE : View.INVISIBLE);
        binding.tabChatsIndicator.setVisibility(tab == Tab.CHATS ? View.VISIBLE : View.INVISIBLE);
        applyFilters();
    }

    private int getColorForTab(boolean selected) {
        return selected
                ? ContextCompat.getColor(requireContext(), R.color.echo_primary_accent)
                : ContextCompat.getColor(requireContext(), R.color.echo_text_secondary);
    }

    private void applyFilters() {
        List<Message> filtered = new ArrayList<>();
        String normalized = query.toLowerCase(Locale.US).trim();
        int alertCount = 0;
        for (Message message : allMessages) {
            if (message.getPriority() == Message.Priority.ALERT) {
                alertCount++;
            }
            if (activeTab == Tab.ALERTS && message.getPriority() != Message.Priority.ALERT) {
                continue;
            }
            if (activeTab == Tab.CHATS) {
                continue;
            }
            if (!normalized.isEmpty() && !message.getText().toLowerCase(Locale.US).contains(normalized)) {
                continue;
            }
            filtered.add(message);
        }
        adapter.submitList(filtered);
        updateEmptyState(filtered.isEmpty());
        updateTabBadges(alertCount);
    }

    private void updateTabBadges(int alertCount) {
        if (alertCount > 0) {
            binding.tabAlerts.setText(getString(R.string.tab_alerts) + " (" + alertCount + ")");
        } else {
            binding.tabAlerts.setText(R.string.tab_alerts);
        }
    }

    private void updateEmptyState(boolean isEmpty) {
        if (isEmpty) {
            binding.messageList.setVisibility(View.GONE);
            if (binding.emptyState.getVisibility() != View.VISIBLE) {
                binding.emptyState.setAlpha(0f);
                binding.emptyState.setVisibility(View.VISIBLE);
                binding.emptyState.animate()
                        .alpha(1f)
                        .setDuration(400)
                        .setInterpolator(new FastOutSlowInInterpolator())
                        .start();
            }
        } else {
            binding.emptyState.setVisibility(View.GONE);
            binding.messageList.setVisibility(View.VISIBLE);
        }
        if (isEmpty && !query.isEmpty()) {
            binding.emptyStateTitle.setText(R.string.empty_state_search_title);
            binding.emptyStateSubtitle.setText(R.string.empty_state_search_sub);
        } else {
            binding.emptyStateTitle.setText(R.string.empty_state_title);
            binding.emptyStateSubtitle.setText(R.string.empty_state_sub);
        }
    }

    private void updateSyncIndicator(int count) {
        if (syncDotAnimator != null) {
            syncDotAnimator.cancel();
            syncDotAnimator = null;
        }

        if (count <= 0) {
            binding.syncDot.setVisibility(View.GONE);
            binding.syncText.setText(R.string.sync_no_devices);
            binding.syncText.setTextColor(ContextCompat.getColor(requireContext(), R.color.echo_text_secondary));
        } else if (count == 1) {
            binding.syncDot.setVisibility(View.VISIBLE);
            binding.syncDot.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.echo_positive_accent));
            binding.syncText.setText(R.string.sync_one_device);
            binding.syncText.setTextColor(ContextCompat.getColor(requireContext(), R.color.echo_positive_accent));
            startSyncDotPulse();
        } else {
            binding.syncDot.setVisibility(View.VISIBLE);
            binding.syncDot.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.echo_positive_accent));
            binding.syncText.setText(getString(R.string.sync_many_devices, count));
            binding.syncText.setTextColor(ContextCompat.getColor(requireContext(), R.color.echo_positive_accent));
            startSyncDotPulse();
        }
    }

    private void startSyncDotPulse() {
        syncDotAnimator = ObjectAnimator.ofFloat(binding.syncDot, "alpha", 1.0f, 0.3f, 1.0f);
        syncDotAnimator.setDuration(2000);
        syncDotAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        syncDotAnimator.setRepeatMode(ObjectAnimator.RESTART);
        syncDotAnimator.start();
    }

    private void openPostComposer() {
        PostComposerSheet sheet = new PostComposerSheet();
        sheet.setOnPostListener(this);
        sheet.show(getParentFragmentManager(), "post_composer");
    }

    @Override
    public void onPost(Message message) {
        if (viewModel != null) {
            viewModel.addMessage(message);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (syncDotAnimator != null) {
            syncDotAnimator.cancel();
            syncDotAnimator = null;
        }
        binding = null;
    }
}
