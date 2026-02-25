package com.dev.echodrop.screens;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.dev.echodrop.R;
import com.dev.echodrop.ble.BleScanner;
import com.dev.echodrop.databinding.ScreenDiscoveryStatusBinding;
import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.mesh.ManifestManager;
import com.dev.echodrop.viewmodels.MessageViewModel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Developer-only debug screen showing live BLE discovery stats,
 * connection status, manifest size, and detected peers.
 *
 * <p>Reached via 7-tap easter egg on Settings version text.</p>
 */
public class DiscoveryStatusFragment extends Fragment {

    private ScreenDiscoveryStatusBinding binding;
    private MessageViewModel viewModel;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private static final long REFRESH_INTERVAL_MS = 5_000;

    private long lastExchangeMs;
    private byte[] lastManifest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenDiscoveryStatusBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupToolbar();
        setupViewModel();
        refreshStats();
        startPeriodicRefresh();
    }

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> navigateBack());
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(requireActivity()).get(MessageViewModel.class);
        viewModel.getMessages().observe(getViewLifecycleOwner(), messages -> {
            if (messages != null) {
                binding.statMessageCount.setText(String.valueOf(messages.size()));
            }
        });
    }

    private void refreshStats() {
        // Nearby nodes
        binding.statNearbyCount.setText("0");

        // Last exchange
        if (lastExchangeMs > 0) {
            final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            binding.statLastExchange.setText(sdf.format(new Date(lastExchangeMs)));
        } else {
            binding.statLastExchange.setText(R.string.discovery_never);
        }

        // Manifest size (build on background thread)
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                final ManifestManager manager = new ManifestManager(requireContext());
                lastManifest = manager.buildManifest();
                final int size = ManifestManager.manifestSizeBytes(lastManifest);
                final int entryCount = ManifestManager.peekEntryCount(lastManifest);
                if (binding != null && isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            binding.statManifestSize.setText(formatBytes(size));
                        }
                    });
                }
            } catch (Exception e) {
                // Database may not be ready
            }
        });

        // Message count — handled by LiveData observer
        // BLE status
        updateBleStatus(false);
        // Wi-Fi Direct status (not implemented yet)
        updateWifiStatus(false);

        // Peers
        binding.peerList.setVisibility(View.GONE);
        binding.emptyPeers.setVisibility(View.VISIBLE);
    }

    private void updateBleStatus(boolean active) {
        if (binding == null) return;
        if (active) {
            binding.bleStatusText.setText(R.string.discovery_active);
            binding.bleStatusText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.echo_positive_accent));
            binding.bleStatusDot.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), R.color.echo_positive_accent));
        } else {
            binding.bleStatusText.setText(R.string.discovery_off);
            binding.bleStatusText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.echo_text_secondary));
            binding.bleStatusDot.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), R.color.echo_text_secondary));
        }
    }

    private void updateWifiStatus(boolean active) {
        if (binding == null) return;
        if (active) {
            binding.wifiStatusText.setText(R.string.discovery_active);
            binding.wifiStatusText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.echo_positive_accent));
            binding.wifiStatusDot.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), R.color.echo_positive_accent));
        } else {
            binding.wifiStatusText.setText(R.string.discovery_off);
            binding.wifiStatusText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.echo_text_secondary));
            binding.wifiStatusDot.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), R.color.echo_text_secondary));
        }
    }

    private void startPeriodicRefresh() {
        refreshHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (binding != null && isAdded()) {
                    refreshStats();
                    refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
                }
            }
        }, REFRESH_INTERVAL_MS);
    }

    private String formatBytes(int bytes) {
        if (bytes < 1024) return bytes + " B";
        return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    }

    private void navigateBack() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        refreshHandler.removeCallbacksAndMessages(null);
        binding = null;
    }
}
