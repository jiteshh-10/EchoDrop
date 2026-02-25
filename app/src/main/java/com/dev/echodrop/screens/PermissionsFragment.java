package com.dev.echodrop.screens;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenPermissionsBinding;
import com.dev.echodrop.service.EchoService;

import java.util.ArrayList;
import java.util.List;

/**
 * Permissions screen that requests BLE, location, and notification permissions
 * at onboarding. On grant the EchoService is auto-started so that BLE
 * discovery and Wi-Fi Direct transfer are active immediately.
 */
public class PermissionsFragment extends Fragment {

    private ScreenPermissionsBinding binding;

    /** Launcher that requests all required runtime permissions. */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean anyGranted = result.containsValue(Boolean.TRUE);
                        if (anyGranted) {
                            // Enable background sharing and start the service
                            EchoService.setBackgroundEnabled(requireContext(), true);
                            EchoService.startService(requireContext());
                            Toast.makeText(requireContext(),
                                    "Permissions granted — mesh sharing active",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(requireContext(),
                                    "Permissions denied — you can enable later in Settings",
                                    Toast.LENGTH_LONG).show();
                        }
                        navigateToHome();
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ScreenPermissionsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.permissionsToolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        binding.permissionsToolbar.setNavigationContentDescription(R.string.content_back);
        binding.permissionsToolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
        binding.permissionsAllowButton.setOnClickListener(v -> requestAllPermissions());
        binding.permissionsLaterLink.setOnClickListener(v -> navigateToHome());
    }

    /**
     * Requests all runtime permissions needed for the mesh networking stack:
     * BLE (API 31+), Location (for BLE on older APIs + Wi-Fi Direct), Notifications (API 33+).
     */
    private void requestAllPermissions() {
        final List<String> perms = new ArrayList<>();

        // BLE permissions (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }

        // Location — required for BLE scanning on API < 31 and for Wi-Fi Direct
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        // Nearby Wi-Fi Devices (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        // Notification permission (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (perms.isEmpty()) {
            // Pre-S: no runtime BLE permissions needed, just start the service
            EchoService.setBackgroundEnabled(requireContext(), true);
            EchoService.startService(requireContext());
            navigateToHome();
        } else {
            permissionLauncher.launch(perms.toArray(new String[0]));
        }
    }

    private void navigateToHome() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showHomeInbox();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
