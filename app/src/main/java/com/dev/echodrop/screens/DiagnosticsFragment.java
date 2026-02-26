package com.dev.echodrop.screens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.dev.echodrop.databinding.ScreenDiagnosticsBinding;
import com.dev.echodrop.util.DiagnosticsLog;

import java.util.List;

/**
 * In-app diagnostics screen showing the ring buffer log.
 *
 * <p>Accessible via Settings → 7-tap easter egg → Discovery Status,
 * or directly from the discovery debug screen. Displays timestamped
 * ED: log entries with copy-to-clipboard and clear actions.</p>
 */
public class DiagnosticsFragment extends Fragment {

    private ScreenDiagnosticsBinding binding;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private static final long REFRESH_INTERVAL_MS = 2_000;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenDiagnosticsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnBack.setOnClickListener(v -> navigateBack());
        binding.btnCopy.setOnClickListener(v -> copyToClipboard());
        binding.btnClear.setOnClickListener(v -> {
            DiagnosticsLog.clear();
            refreshLog();
            Toast.makeText(requireContext(), "Log cleared", Toast.LENGTH_SHORT).show();
        });

        refreshLog();
        startAutoRefresh();
    }

    private void refreshLog() {
        if (binding == null) return;

        final List<String> entries = DiagnosticsLog.getEntries();
        binding.entryCount.setText(entries.size() + " entries (max 500)");

        if (entries.isEmpty()) {
            binding.logText.setText("No log entries yet.\n\nED: structured logs will appear here " +
                    "as BLE discovery, Wi-Fi Direct, and transfers occur.");
        } else {
            final StringBuilder sb = new StringBuilder();
            for (final String entry : entries) {
                sb.append(entry).append('\n');
            }
            binding.logText.setText(sb.toString());
        }

        // Auto-scroll to bottom
        binding.scrollView.post(() -> {
            if (binding != null) {
                binding.scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void copyToClipboard() {
        final String text = DiagnosticsLog.getAllText();
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }

        final ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("EchoDrop Diagnostics", text));
            Toast.makeText(requireContext(), "Copied " + DiagnosticsLog.size() + " entries",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private void startAutoRefresh() {
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void navigateBack() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        refreshHandler.removeCallbacks(refreshRunnable);
        binding = null;
    }
}
