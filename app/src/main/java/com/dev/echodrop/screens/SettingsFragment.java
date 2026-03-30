package com.dev.echodrop.screens;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenSettingsBinding;
import com.dev.echodrop.service.EchoService;
import com.dev.echodrop.util.AppPreferences;
import com.dev.echodrop.util.BlockedDeviceStore;
import com.dev.echodrop.util.DeviceIdHelper;
import com.dev.echodrop.util.MessageStorageCapManager;
import com.dev.echodrop.viewmodels.ChatViewModel;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings screen with background sharing toggle, battery guide link,
 * and a 7-tap easter egg on the version text that opens the discovery
 * debug screen.
 */
public class SettingsFragment extends Fragment {

    private static final ExecutorService SETTINGS_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                final Thread thread = new Thread(r, "SettingsExecutor");
                thread.setDaemon(true);
                return thread;
            });

    private ScreenSettingsBinding binding;
    private ChatViewModel chatViewModel;
    private int versionTapCount;
    private boolean isUpdatingIncomingAlertsSwitch;

    /** Launcher that requests BLE permissions, then starts the service on grant. */
    private final ActivityResultLauncher<String[]> blePermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (EchoService.hasBlePermissions(requireContext())) {
                            EchoService.setBackgroundEnabled(requireContext(), true);
                            EchoService.startService(requireContext());
                        } else {
                            // Permissions denied — flip switch back off
                            Toast.makeText(requireContext(),
                                    "Bluetooth permissions required for nearby sharing",
                                    Toast.LENGTH_LONG).show();
                            binding.switchBackground.setOnCheckedChangeListener(null);
                            binding.switchBackground.setChecked(false);
                            setupToggle();
                        }
                    });

    /** Launcher that requests runtime notification permission (API 33+). */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (binding == null) return;

                        if (granted) {
                            AppPreferences.setMessageAlertsEnabled(requireContext(), true);
                            setIncomingAlertsSwitchChecked(true);
                            Toast.makeText(requireContext(),
                                    R.string.settings_alerts_enabled,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            AppPreferences.setMessageAlertsEnabled(requireContext(), false);
                            setIncomingAlertsSwitchChecked(false);

                            final boolean permanentDenial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                                    requireActivity(), Manifest.permission.POST_NOTIFICATIONS);

                            if (permanentDenial) {
                                Toast.makeText(requireContext(),
                                        R.string.settings_alerts_permission_settings_hint,
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(requireContext(),
                                        R.string.settings_alerts_permission_required,
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        chatViewModel = new ViewModelProvider(requireActivity()).get(ChatViewModel.class);
        setupToolbar();
        setupToggle();
        setupIncomingAlerts();
        setupStorageCap();
        setupBatteryGuide();
        setupHowItWorks();
        setupDiagnostics();
        setupBlockDevices();
        setupRooms();
        setupVersion();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) {
            return;
        }
        refreshIncomingAlertsState();
        final int capMb = AppPreferences.getStorageCapMb(requireContext());
        binding.storageCapSlider.setValue(capMb);
        updateStorageCapLabel(capMb);
    }

    private void setupToolbar() {
        binding.settingsToolbar.setNavigationOnClickListener(v -> navigateBack());
    }

    private void setupToggle() {
        // Reflect current preference
        binding.switchBackground.setChecked(EchoService.isBackgroundEnabled(requireContext()));

        binding.switchBackground.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                // Confirm before disabling
                new AlertDialog.Builder(requireContext(), R.style.Theme_EchoDrop_Dialog)
                        .setTitle(R.string.settings_stop_title)
                        .setMessage(R.string.settings_stop_body)
                        .setPositiveButton(R.string.settings_stop_confirm, (d, w) -> {
                            EchoService.setBackgroundEnabled(requireContext(), false);
                            EchoService.stopService(requireContext());
                        })
                        .setNegativeButton(R.string.settings_stop_cancel, (d, w) -> {
                            // Re-check the switch without triggering listener
                            binding.switchBackground.setOnCheckedChangeListener(null);
                            binding.switchBackground.setChecked(true);
                            setupToggle(); // Re-attach listener
                        })
                        .setCancelable(false)
                        .show();
            } else {
                // Request BLE permissions first, then start service
                if (EchoService.hasBlePermissions(requireContext())) {
                    EchoService.setBackgroundEnabled(requireContext(), true);
                    EchoService.startService(requireContext());
                } else {
                    String[] perms = EchoService.getBlePermissions();
                    if (perms.length > 0) {
                        blePermissionLauncher.launch(perms);
                    } else {
                        EchoService.setBackgroundEnabled(requireContext(), true);
                        EchoService.startService(requireContext());
                    }
                }
            }
        });
    }

    private void setupIncomingAlerts() {
        refreshIncomingAlertsState();

        binding.switchIncomingAlerts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingIncomingAlertsSwitch) {
                return;
            }

            if (!isChecked) {
                AppPreferences.setMessageAlertsEnabled(requireContext(), false);
                return;
            }

            if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
                AppPreferences.setMessageAlertsEnabled(requireContext(), false);
                setIncomingAlertsSwitchChecked(false);
                Toast.makeText(requireContext(),
                        R.string.settings_alerts_system_disabled,
                        Toast.LENGTH_LONG).show();
                openSystemNotificationSettings();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && !hasRuntimeNotificationPermission()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }

            AppPreferences.setMessageAlertsEnabled(requireContext(), true);
            Toast.makeText(requireContext(),
                    R.string.settings_alerts_enabled,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshIncomingAlertsState() {
        final boolean enabled = AppPreferences.isMessageAlertsEnabled(requireContext())
                && hasRuntimeNotificationPermission()
                && NotificationManagerCompat.from(requireContext()).areNotificationsEnabled();

        if (!enabled && AppPreferences.isMessageAlertsEnabled(requireContext())) {
            AppPreferences.setMessageAlertsEnabled(requireContext(), false);
        }

        setIncomingAlertsSwitchChecked(enabled);
    }

    private boolean hasRuntimeNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void setIncomingAlertsSwitchChecked(boolean checked) {
        isUpdatingIncomingAlertsSwitch = true;
        binding.switchIncomingAlerts.setChecked(checked);
        isUpdatingIncomingAlertsSwitch = false;
    }

    private void setupStorageCap() {
        final int currentCapMb = AppPreferences.getStorageCapMb(requireContext());
        binding.storageCapSlider.setValue(currentCapMb);
        updateStorageCapLabel(currentCapMb);

        binding.storageCapSlider.addOnChangeListener((slider, value, fromUser) ->
                updateStorageCapLabel(Math.round(value)));

        binding.storageCapSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                // No-op.
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                final int selectedCapMb = Math.round(slider.getValue());
                AppPreferences.setStorageCapMb(requireContext(), selectedCapMb);

                final int normalizedCapMb = AppPreferences.getStorageCapMb(requireContext());
                slider.setValue(normalizedCapMb);
                updateStorageCapLabel(normalizedCapMb);

                final android.content.Context appContext = requireContext().getApplicationContext();
                SETTINGS_EXECUTOR.execute(() -> MessageStorageCapManager.enforceNow(appContext));

                Toast.makeText(requireContext(),
                        getString(R.string.settings_storage_cap_saved, normalizedCapMb),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateStorageCapLabel(int capMb) {
        binding.storageCapValue.setText(getString(R.string.settings_storage_cap_value, capMb));
    }

    private void openSystemNotificationSettings() {
        final Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        }
        startActivity(intent);
    }

    private void setupBatteryGuide() {
        binding.batteryGuideRow.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showBatteryGuide();
            }
        });
    }

    private void setupHowItWorks() {
        binding.howItWorksRow.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showHowItWorksFromSettings();
            }
        });
    }

    private void setupDiagnostics() {
        binding.diagnosticsRow.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showDiagnostics();
            }
        });
    }

    private void setupBlockDevices() {
        refreshBlockedSummary();
        binding.blockDeviceRow.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showBlockedDevices();
            }
        });
    }

    private void setupRooms() {
        binding.roomsRow.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showChatList();
            }
        });

        chatViewModel.getChats().observe(getViewLifecycleOwner(), chats -> {
            final int count = chats == null ? 0 : chats.size();
            if (count <= 0) {
                binding.roomsSummaryText.setText(R.string.settings_rooms_sub_empty);
            } else {
                binding.roomsSummaryText.setText(getString(R.string.settings_rooms_count, count));
            }
        });
    }

    private void refreshBlockedSummary() {
        final int count = BlockedDeviceStore.getBlockedIds(requireContext()).size();
        if (count <= 0) {
            binding.blockedSummaryText.setText(R.string.settings_blocked_none);
        } else {
            binding.blockedSummaryText.setText(getString(R.string.settings_blocked_count, count));
        }
    }

    private void showBlockDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint(R.string.settings_block_device_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);

        new AlertDialog.Builder(requireContext(), R.style.Theme_EchoDrop_Dialog)
                .setTitle(R.string.settings_block_device_title)
                .setView(input)
                .setPositiveButton(R.string.settings_block_device_save, (d, w) -> {
                    final String id = input.getText() != null ? input.getText().toString().trim() : "";
                    if (id.isEmpty()) return;

                    if (id.equalsIgnoreCase(DeviceIdHelper.getDeviceId(requireContext()))) {
                        Toast.makeText(requireContext(),
                                R.string.settings_block_device_self_forbidden,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    final boolean added = BlockedDeviceStore.addBlockedId(requireContext(), id);
                    Toast.makeText(requireContext(),
                            added ? R.string.settings_block_device_added : R.string.settings_block_device_exists,
                            Toast.LENGTH_SHORT).show();
                    refreshBlockedSummary();
                })
                .setNeutralButton(R.string.settings_block_device_manage, (d, w) -> showUnblockDialog())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showUnblockDialog() {
        final Set<String> blocked = BlockedDeviceStore.getBlockedIds(requireContext());
        if (blocked.isEmpty()) {
            Toast.makeText(requireContext(), R.string.settings_blocked_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> ids = new ArrayList<>(blocked);
        Collections.sort(ids);
        final String[] items = ids.toArray(new String[0]);

        new AlertDialog.Builder(requireContext(), R.style.Theme_EchoDrop_Dialog)
                .setTitle(R.string.settings_unblock_title)
                .setItems(items, (dialog, which) -> {
                    final String id = items[which];
                    BlockedDeviceStore.removeBlockedId(requireContext(), id);
                    Toast.makeText(requireContext(), R.string.settings_unblock_done, Toast.LENGTH_SHORT).show();
                    refreshBlockedSummary();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setupVersion() {
        String versionName = "1.0";
        try {
            final PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            versionName = info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }

        binding.versionText.setText(getString(R.string.settings_version, versionName));

        // 7-tap easter egg
        versionTapCount = 0;
        binding.versionText.setOnClickListener(v -> {
            versionTapCount++;
            if (versionTapCount >= 7) {
                versionTapCount = 0;
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).showDiscoveryStatus();
                }
            } else if (versionTapCount >= 4) {
                final int remaining = 7 - versionTapCount;
                Toast.makeText(requireContext(),
                        remaining + " taps to developer mode",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void navigateBack() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
