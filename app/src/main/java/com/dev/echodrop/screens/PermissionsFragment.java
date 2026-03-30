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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenPermissionsBinding;
import com.dev.echodrop.service.EchoService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
                    this::handlePermissionResult);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ScreenPermissionsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.permissionsToolbar.setNavigationIcon(R.drawable.ic_back);
        binding.permissionsToolbar.setNavigationContentDescription(R.string.content_back);
        binding.permissionsToolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
        binding.permissionsAllowButton.setOnClickListener(v -> requestAllPermissions());
        binding.permissionsLaterLink.setOnClickListener(v -> navigateToHome());
    }

    /**
     * Requests runtime permissions needed for BLE scan/advertise/connect + location.
     */
    private void requestAllPermissions() {
        final List<String> missingRequired = getMissingRequiredPermissions();
        final List<String> missingOptional = getMissingOptionalPermissions();
        final List<String> permissionsToRequest = new ArrayList<>(
                missingRequired.size() + missingOptional.size());
        permissionsToRequest.addAll(missingRequired);
        permissionsToRequest.addAll(missingOptional);

        if (permissionsToRequest.isEmpty()) {
            proceedAfterRequiredPermissionsGranted();
            return;
        }

        permissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
    }

    private void handlePermissionResult(Map<String, Boolean> result) {
        Timber.tag("ED:Perms").i("ED:PERM_RESULT %s", result);

        if (EchoService.hasBlePermissions(requireContext())) {
            Timber.tag("ED:Perms").i("ED:PERMS_REQUIRED_GRANTED");
            if (!getMissingOptionalPermissions().isEmpty()) {
                Toast.makeText(requireContext(),
                        R.string.permissions_notifications_optional_notice,
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(),
                        R.string.permissions_granted_mesh_active,
                        Toast.LENGTH_SHORT).show();
            }
            proceedAfterRequiredPermissionsGranted();
            return;
        }

        if (hasPermanentlyDeniedRequiredPermission(result)) {
            Timber.tag("ED:Perms").w("ED:PERMS_PERMANENT_DENIAL");
            showOpenSettingsDialog();
            return;
        }

        showRetryPermissionsDialog();
    }

    private void proceedAfterRequiredPermissionsGranted() {
        EchoService.setBackgroundEnabled(requireContext(), true);
        EchoService.startService(requireContext());
        navigateToHome();
    }

    private List<String> getMissingRequiredPermissions() {
        final List<String> missing = new ArrayList<>();
        for (String permission : EchoService.getBlePermissions()) {
            if (!isGranted(permission)) {
                missing.add(permission);
            }
        }
        return missing;
    }

    private List<String> getMissingOptionalPermissions() {
        final List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing;
    }

    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(requireContext(), permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
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
    private boolean hasPermanentlyDeniedRequiredPermission(Map<String, Boolean> result) {
        for (String permission : EchoService.getBlePermissions()) {
            if (Boolean.FALSE.equals(result.get(permission))) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        requireActivity(), permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void showRetryPermissionsDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.permissions_rationale_title)
                .setMessage(R.string.permissions_rationale_body)
                .setPositiveButton(R.string.permissions_try_again,
                        (dialog, which) -> requestAllPermissions())
                .setNegativeButton(R.string.permissions_not_now, null)
                .show();
    }

    private void showOpenSettingsDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.permissions_settings_title)
                .setMessage(R.string.permissions_settings_body)
                .setPositiveButton(R.string.permissions_open_settings,
                        (dialog, which) -> openAppSettings())
                .setNegativeButton(R.string.permissions_not_now, null)
                .show();
    }

    /** Opens the system app settings page for this app. */
    private void openAppSettings() {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (EchoService.hasBlePermissions(requireContext())) {
            proceedAfterRequiredPermissionsGranted();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
