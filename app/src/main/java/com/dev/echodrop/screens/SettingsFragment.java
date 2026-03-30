package com.dev.echodrop.screens;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import androidx.fragment.app.Fragment;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenSettingsBinding;
import com.dev.echodrop.service.EchoService;
import com.dev.echodrop.util.BlockedDeviceStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Settings screen with background sharing toggle, battery guide link,
 * and a 7-tap easter egg on the version text that opens the discovery
 * debug screen.
 */
public class SettingsFragment extends Fragment {

    private ScreenSettingsBinding binding;
    private int versionTapCount;

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
        setupToolbar();
        setupToggle();
        setupBatteryGuide();
        setupHowItWorks();
        setupDiagnostics();
        setupBlockDevices();
        setupVersion();
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
        binding.blockDeviceRow.setOnClickListener(v -> showBlockDialog());
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
