package com.dev.echodrop.screens;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import com.dev.echodrop.MainActivity;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import timber.log.Timber;

import com.dev.echodrop.R;
import com.dev.echodrop.adapters.MessageAdapter;
import com.dev.echodrop.components.PostComposerSheet;
import com.dev.echodrop.databinding.ScreenHomeInboxBinding;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.repository.MessageRepo;
import com.dev.echodrop.service.EchoService;
import com.dev.echodrop.util.ToolbarLogoAnimator;
import com.dev.echodrop.viewmodels.MessageViewModel;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home inbox screen displaying all active messages from Room database.
 *
 * <p>Updated in Iteration 2:
 * <ul>
 *   <li>Uses MessageEntity instead of Message POJO</li>
 *   <li>Room-backed LiveData for reactive updates</li>
 *   <li>Click on message card → MessageDetailFragment</li>
 *   <li>Post composer inserts via MessageRepo with dedup</li>
 * </ul>
 * </p>
 *
 * <p>Updated in Iteration 3:
 * <ul>
 *   <li>Reactive alert count badge on Alerts tab (fade animation)</li>
 *   <li>DAO sort by priority (ALERT &gt; NORMAL &gt; BULK) then created_at</li>
 * </ul>
 * </p>
 */
public class HomeInboxFragment extends Fragment implements PostComposerSheet.OnPostListener {

    private ScreenHomeInboxBinding binding;
    private MessageAdapter adapter;
    private MessageViewModel viewModel;
    private Tab activeTab = Tab.ALL;
    private String query = "";
    private List<MessageEntity> allMessages = new ArrayList<>();
    private ObjectAnimator syncDotAnimator;
    private boolean transferActive;
    private Handler ttlRefreshHandler;
    private static final long TTL_REFRESH_INTERVAL_MS = 60_000L;

    /** Whether we already requested permissions this fragment instance. */
    private boolean permissionsRequested;

    /** Launcher for inline permission request (replaces PermissionsFragment). */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Timber.i("ED:PERM_RESULT %s", result);
                        if (EchoService.hasBlePermissions(requireContext())) {
                            EchoService.setBackgroundEnabled(requireContext(), true);
                            EchoService.startService(requireContext());
                            Timber.i("ED:PERM_INLINE all_granted — mesh sharing started");
                        } else if (hasPermanentlyDeniedPermission(result)) {
                            Timber.w("ED:PERM_INLINE permanent_denial -> app_settings");
                            Toast.makeText(requireContext(),
                                    "Enable permissions in App Settings to keep mesh running",
                                    Toast.LENGTH_LONG).show();
                            openAppSettings();
                        } else {
                            Timber.w("ED:PERM_INLINE incomplete — user can enable later in Settings");
                            Toast.makeText(requireContext(),
                                    "Permissions needed for mesh sharing — enable in Settings",
                                    Toast.LENGTH_LONG).show();
                        }
                    });

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

        // Handle back press — finish the activity (home screen is the root)
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        requireActivity().finish();
                    }
                });

        setupToolbar();
        setupRecycler();
        setupSearch();
        setupTabs();
        setupFabs();
        setupViewModel();
        // Start with 0 peers — real count will come from EchoService
        updateSyncIndicator(0);

        // Listen for transfer state → faster pulse
        EchoService.setTransferStateListener(inProgress -> {
            if (binding == null) return;
            transferActive = inProgress;
            restartSyncDotPulse();
        });

        // Listen for real peer count from BLE scanner
        EchoService.setPeerCountListener(count -> {
            if (binding == null) return;
            updateSyncIndicator(count);
        });

        // Listen for BT/P2P prerequisite changes → show warning in sync indicator
        EchoService.setPrerequisiteListener((btOn, p2pOn) -> {
            if (binding == null) return;
            updatePrerequisiteWarning(btOn, p2pOn);
        });

        // Periodic TTL refresh so message card timestamps stay current
        startTtlRefresh();

    }

    /**
     * Requests all required runtime permissions if not already granted.
     * Called once per fragment instance from onViewCreated.
     */
    private void requestPermissionsIfNeeded() {
        if (permissionsRequested) return;
        if (EchoService.hasBlePermissions(requireContext())) {
            // Already granted — make sure service is running
            if (EchoService.isBackgroundEnabled(requireContext())) {
                EchoService.startService(requireContext());
            }
            return;
        }
        permissionsRequested = true;
        final java.util.List<String> perms = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            Timber.i("ED:PERM_INLINE requesting %d permissions", perms.size());
            permissionLauncher.launch(perms.toArray(new String[0]));
        } else {
            // Pre-S: no runtime BLE permissions needed
            EchoService.setBackgroundEnabled(requireContext(), true);
            EchoService.startService(requireContext());
        }
    }

    /** Periodic task that refreshes the adapter so TTL labels update. */
    private final Runnable ttlRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding != null && adapter != null) {
                adapter.notifyDataSetChanged();
            }
            ttlRefreshHandler.postDelayed(this, TTL_REFRESH_INTERVAL_MS);
        }
    };

    private void startTtlRefresh() {
        if (ttlRefreshHandler == null) {
            ttlRefreshHandler = new Handler(Looper.getMainLooper());
        }
        ttlRefreshHandler.postDelayed(ttlRefreshRunnable, TTL_REFRESH_INTERVAL_MS);
    }

    private void stopTtlRefresh() {
        if (ttlRefreshHandler != null) {
            ttlRefreshHandler.removeCallbacks(ttlRefreshRunnable);
        }
    }

    private boolean hasPermanentlyDeniedPermission(java.util.Map<String, Boolean> result) {
        for (java.util.Map.Entry<String, Boolean> entry : result.entrySet()) {
            if (!entry.getValue() && !ActivityCompat.shouldShowRequestPermissionRationale(
                    requireActivity(), entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private void openAppSettings() {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void setupToolbar() {
        ToolbarLogoAnimator.apply(binding.homeToolbar);
        binding.homeToolbar.inflateMenu(R.menu.home_menu);
        binding.homeToolbar.setOnMenuItemClickListener(this::onMenuItemClicked);
    }

    private boolean onMenuItemClicked(MenuItem item) {
        if (item.getItemId() == R.id.action_saved) {
            navigateToSaved();
            return true;
        }
        if (item.getItemId() == R.id.action_settings) {
            navigateToSettings();
            return true;
        }
        return false;
    }

    private void setupRecycler() {
        adapter = new MessageAdapter();
        adapter.setOnMessageClickListener(this::onMessageClicked);
        binding.messageList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.messageList.setAdapter(adapter);

        // Add divider decoration between items
        DividerItemDecoration divider = new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL);
        binding.messageList.addItemDecoration(divider);

        // Hide scrollbar for clean look
        binding.messageList.setVerticalScrollBarEnabled(false);
    }

    private void onMessageClicked(MessageEntity message) {
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
        binding.tabChatsContainer.setOnClickListener(v -> navigateToChatList());
        selectTab(Tab.ALL);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupFabs() {
        binding.fabPost.setOnClickListener(v -> openPostComposer());

        // FAB press scale animation (scale to 0.95 over 40ms)
        addFabPressAnimation(binding.fabPost);
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
        // Reactive alert count badge
        viewModel.getAlertCount().observe(getViewLifecycleOwner(), this::updateAlertBadge);
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
        List<MessageEntity> filtered = new ArrayList<>();
        String normalized = query.toLowerCase(Locale.US).trim();
        for (MessageEntity message : allMessages) {
            if (activeTab == Tab.ALERTS && message.getPriorityEnum() != MessageEntity.Priority.ALERT) {
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
    }

    /**
     * Update the Alerts tab badge with fade animation.
     * Badge fades in over 180ms when count > 0, fades out when count drops to 0.
     */
    private void updateAlertBadge(int alertCount) {
        if (alertCount > 0) {
            final String label = getString(R.string.tab_alerts) + " (" + alertCount + ")";
            if (binding.tabAlerts.getVisibility() == View.VISIBLE
                    && binding.tabAlerts.getAlpha() == 1f) {
                // Crossfade the text update
                binding.tabAlerts.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                    if (binding != null) {
                        binding.tabAlerts.setText(label);
                        binding.tabAlerts.animate().alpha(1f).setDuration(90).start();
                    }
                }).start();
            } else {
                binding.tabAlerts.setText(label);
                binding.tabAlerts.setAlpha(0f);
                binding.tabAlerts.animate().alpha(1f).setDuration(180).start();
            }
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
        final long duration = transferActive ? 500 : 2000;
        syncDotAnimator = ObjectAnimator.ofFloat(binding.syncDot, "alpha", 1.0f, 0.3f, 1.0f);
        syncDotAnimator.setDuration(duration);
        syncDotAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        syncDotAnimator.setRepeatMode(ObjectAnimator.RESTART);
        syncDotAnimator.start();
    }

    /** Restarts the sync dot pulse with the correct duration for transfer state. */
    private void restartSyncDotPulse() {
        if (syncDotAnimator != null && syncDotAnimator.isRunning()) {
            syncDotAnimator.cancel();
            startSyncDotPulse();
        }
    }

    /**
     * Shows a warning in the sync indicator when Bluetooth is off.
     * Overrides the normal peer count display until prerequisites are met.
     */
    private void updatePrerequisiteWarning(boolean btOn, boolean p2pOn) {
        if (btOn && p2pOn) {
            // Prerequisites met — restore normal peer count display
            // Let the next peer count callback handle it
            return;
        }
        // Show warning
        if (syncDotAnimator != null) {
            syncDotAnimator.cancel();
            syncDotAnimator = null;
        }
        binding.syncDot.setVisibility(View.VISIBLE);
        binding.syncDot.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.echo_amber_accent));
        binding.syncText.setText(btOn ? R.string.sync_wifi_off : R.string.sync_bt_off);
        binding.syncText.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.echo_amber_accent));
    }

    private void openPostComposer() {
        PostComposerSheet sheet = new PostComposerSheet();
        sheet.setOnPostListener(this);
        sheet.show(getParentFragmentManager(), "post_composer");
    }

    @Override
    public void onPost(MessageEntity entity) {
        if (viewModel != null) {
            viewModel.addMessage(entity, new MessageRepo.InsertCallback() {
                @Override
                public void onInserted() {
                    // Receiver-only notification policy: local sends do not notify.
                }

                @Override
                public void onDuplicate() {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            View rootView = getView();
                            if (rootView != null) {
                                Snackbar snackbar = Snackbar.make(rootView,
                                        R.string.post_dedup_snackbar, Snackbar.LENGTH_LONG);
                                snackbar.getView().setBackgroundColor(
                                        ContextCompat.getColor(requireContext(), R.color.echo_card_surface));
                                snackbar.setTextColor(
                                        ContextCompat.getColor(requireContext(), R.color.echo_amber_accent));
                                snackbar.setDuration(3000);
                                snackbar.show();
                            }
                        });
                    }
                }
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EchoService.setTransferStateListener(null);
        EchoService.setPeerCountListener(null);
        EchoService.setPrerequisiteListener(null);
        stopTtlRefresh();
        if (syncDotAnimator != null) {
            syncDotAnimator.cancel();
            syncDotAnimator = null;
        }
        binding = null;
    }

    /** Navigate to the Settings screen. */
    private void navigateToSettings() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showSettings();
        }
    }

    /** Navigate to the Private Chat list screen. */
    private void navigateToChatList() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showChatList();
        }
    }

    /** Navigate to the Saved messages screen. */
    private void navigateToSaved() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showSavedMessages();
        }
    }
}
