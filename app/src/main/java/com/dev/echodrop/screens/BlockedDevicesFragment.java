package com.dev.echodrop.screens;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenBlockedDevicesBinding;
import com.dev.echodrop.util.BlockedDeviceStore;
import com.dev.echodrop.util.DeviceIdHelper;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Themed blocked-device management screen using card rows.
 */
public class BlockedDevicesFragment extends Fragment {

    private ScreenBlockedDevicesBinding binding;
    private final BlockedAdapter adapter = new BlockedAdapter(this::unblock);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenBlockedDevicesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.blockedToolbar.setNavigationOnClickListener(v -> navigateBack());
        binding.blockedList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.blockedList.setAdapter(adapter);
        binding.addBlockedButton.setOnClickListener(v -> showAddDialog());
        refreshBlockedDevices();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBlockedDevices();
    }

    private void showAddDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint(R.string.settings_block_device_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);

        final FrameLayout container = new FrameLayout(requireContext());
        final int horizontal = getResources().getDimensionPixelSize(R.dimen.spacing_2);
        container.setPadding(horizontal, horizontal, horizontal, 0);
        container.addView(input);

        new AlertDialog.Builder(requireContext(), R.style.Theme_EchoDrop_Dialog)
                .setTitle(R.string.blocked_devices_add)
                .setView(container)
                .setPositiveButton(R.string.settings_block_device_save, (d, w) -> {
                    final String id = input.getText() != null
                            ? input.getText().toString().trim()
                            : "";
                    if (id.isEmpty()) {
                        return;
                    }

                    if (id.equals(DeviceIdHelper.getDeviceId(requireContext()))) {
                        Toast.makeText(requireContext(),
                                R.string.settings_block_device_self_forbidden,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    final boolean added = BlockedDeviceStore.addBlockedId(requireContext(), id);
                    Toast.makeText(requireContext(),
                            added ? R.string.settings_block_device_added
                                    : R.string.settings_block_device_exists,
                            Toast.LENGTH_SHORT).show();
                    refreshBlockedDevices();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void unblock(@NonNull String deviceId) {
        final boolean removed = BlockedDeviceStore.removeBlockedId(requireContext(), deviceId);
        if (removed) {
            Toast.makeText(requireContext(), R.string.settings_unblock_done, Toast.LENGTH_SHORT).show();
        }
        refreshBlockedDevices();
    }

    private void refreshBlockedDevices() {
        final String localId = DeviceIdHelper.getDeviceId(requireContext());
        final Set<String> blockedSet = BlockedDeviceStore.getBlockedIds(requireContext());

        // Clean up any legacy self-block entries from older builds.
        if (blockedSet.contains(localId)) {
            BlockedDeviceStore.removeBlockedId(requireContext(), localId);
            blockedSet.remove(localId);
        }

        final List<String> blocked = new ArrayList<>(blockedSet);
        Collections.sort(blocked);

        adapter.submitList(blocked);
        binding.blockedCountText.setText(getString(R.string.settings_blocked_count, blocked.size()));

        final boolean empty = blocked.isEmpty();
        binding.blockedList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void navigateBack() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static final class BlockedAdapter
            extends RecyclerView.Adapter<BlockedAdapter.ViewHolder> {

        interface OnUnblockListener {
            void onUnblock(@NonNull String deviceId);
        }

        private final OnUnblockListener listener;
        private List<String> blockedIds = new ArrayList<>();

        private BlockedAdapter(@NonNull OnUnblockListener listener) {
            this.listener = listener;
        }

        void submitList(@NonNull List<String> ids) {
            blockedIds = new ArrayList<>(ids);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_blocked_device, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final String id = blockedIds.get(position);
            holder.deviceIdText.setText(id);
            holder.unblockButton.setOnClickListener(v -> listener.onUnblock(id));
        }

        @Override
        public int getItemCount() {
            return blockedIds.size();
        }

        static final class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView deviceIdText;
            private final MaterialButton unblockButton;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                deviceIdText = itemView.findViewById(R.id.blocked_device_id_text);
                unblockButton = itemView.findViewById(R.id.unblock_button);
            }
        }
    }
}
