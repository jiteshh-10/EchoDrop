package com.dev.echodrop.screens;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenPermissionsBinding;
import com.dev.echodrop.service.EchoService;

import timber.log.Timber;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Permissions screen that requests BLE + location permissions at onboarding.
 * On grant the EchoService is auto-started so BLE discovery + GATT transfer
 * become active immediately.
 */
public class PermissionsFragment extends Fragment {

    private ScreenPermissionsBinding binding;

    /** Launcher that requests all required runtime permissions. */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Timber.tag("ED:Perms").i("ED:PERM_RESULT %s", result);
                        if (EchoService.hasBlePermissions(requireContext())) {
                            Timber.tag("ED:Perms").i("ED:PERMS_ALL_GRANTED");
                            // Enable background sharing and start the service
                            EchoService.setBackgroundEnabled(requireContext(), true);
                            EchoService.startService(requireContext());
                            Toast.makeText(requireContext(),
                                    "Permissions granted — mesh sharing active",
                                    Toast.LENGTH_SHORT).show();
                            navigateToHome();
                        } else if (hasPermanentlyDeniedPermission(result)) {
                            // User selected "Don't ask again" — direct to app settings
                            Timber.tag("ED:Perms").w("ED:PERMS_PERMANENT_DENIAL");
                            Toast.makeText(requireContext(),
                                    "Please enable permissions in App Settings to use mesh sharing",
                                    Toast.LENGTH_LONG).show();
                            openAppSettings();
                        } else {
                            Toast.makeText(requireContext(),
                                    "Permissions denied — you can enable later in Settings",
                                    Toast.LENGTH_LONG).show();
                            navigateToHome();
                        }
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
     * Requests runtime permissions needed for BLE scan/advertise/connect + location.
     */
    private void requestAllPermissions() {
        final List<String> perms = new ArrayList<>();

        // BLE permissions (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }

        // Location — required for BLE scan reliability across Android versions
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

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

    /**
     * Detects if any denied permission was permanently denied ("Don't ask again").
     * A permission is permanently denied when the result is false AND
     * shouldShowRequestPermissionRationale also returns false.
     */
    private boolean hasPermanentlyDeniedPermission(Map<String, Boolean> result) {
        for (Map.Entry<String, Boolean> entry : result.entrySet()) {
            if (!entry.getValue()) {
                // Permission denied — check if permanently denied
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        requireActivity(), entry.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Opens the system app settings page for this app. */
    private void openAppSettings() {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
