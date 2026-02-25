package com.dev.echodrop.screens;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
                        boolean anyGranted = result.containsValue(Boolean.TRUE);
                        if (anyGranted) {
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
