package com.dev.echodrop.screens;

import android.content.res.ColorStateList;
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
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.dev.echodrop.R;
import com.dev.echodrop.databinding.FragmentMessageDetailBinding;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.repository.MessageRepo;
import com.dev.echodrop.util.ScopeLabelCodec;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Detail screen for a single message.
 *
 * <p>Displays:
 * <ul>
 *   <li>Message text, scope badge, priority badge</li>
 *   <li>"Visible to" label based on scope</li>
 *   <li>TTL progress bar (color: green &gt;50%, amber 20-50%, red &lt;20%)</li>
 *   <li>TTL label: "Expires in 3h 24m"</li>
 *   <li>"Got it" button that deletes from DB and navigates back</li>
 * </ul>
 * </p>
 *
 * <p>The TTL progress bar updates every 30 seconds via a Handler.</p>
 */
public class MessageDetailFragment extends Fragment {

    private static final String ARG_MESSAGE_ID = "message_id";
    private static final long TTL_UPDATE_INTERVAL_MS = 30_000L;

    private FragmentMessageDetailBinding binding;
    private MessageRepo repo;
    private String messageId;
    private final Handler ttlHandler = new Handler(Looper.getMainLooper());
    private Runnable ttlUpdateRunnable;

    /**
     * Factory method to create a new instance with the given message ID.
     */
    public static MessageDetailFragment newInstance(@NonNull String messageId) {
        MessageDetailFragment fragment = new MessageDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_MESSAGE_ID, messageId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMessageDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        repo = new MessageRepo(requireContext());
        messageId = getArguments() != null ? getArguments().getString(ARG_MESSAGE_ID) : null;

        if (messageId == null) {
            navigateBack();
            return;
        }

        setupToolbar();
        setupGotItButton();
        observeMessage();
    }

    private void setupToolbar() {
        binding.detailToolbar.setNavigationOnClickListener(v -> navigateBack());
    }

    private void setupGotItButton() {
        binding.detailGotItBtn.setOnClickListener(v -> {
            repo.deleteById(messageId);
            navigateBack();
        });
    }

    private void observeMessage() {
        repo.getMessageById(messageId).observe(getViewLifecycleOwner(), message -> {
            if (message == null) {
                // Message was deleted or expired
                return;
            }
            bindMessage(message);
            // Mark as read on first view
            if (!message.isRead()) {
                repo.markAsRead(messageId);
            }
        });
    }

    private void bindMessage(@NonNull MessageEntity message) {
        // Message text
        binding.detailMessageText.setText(message.getText());

        // Scope badge
        bindScopeBadge(message);

        // Priority badge
        bindPriorityBadge(message);

        // Visible to label
        binding.detailVisibleTo.setText(getVisibleToText(message));

        // TTL progress
        updateTtlDisplay(message);

        // Created at
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, h:mm a", Locale.US);
        binding.detailCreatedAt.setText(getString(R.string.detail_created_at,
                sdf.format(new Date(message.getCreatedAt()))));

        // Forwarded count (Iteration 7)
        int hopCount = message.getHopCount();
        if (hopCount > 0) {
            binding.detailForwardedCount.setText(
                    getString(R.string.detail_forwarded_count, hopCount));
        } else {
            binding.detailForwardedCount.setText(R.string.detail_forwarded_none);
        }

        // Schedule periodic TTL updates
        startTtlUpdates(message);
    }

    private void bindScopeBadge(@NonNull MessageEntity message) {
        String scopeLabel = ScopeLabelCodec.toDisplayTag(message);
        if (message.getScopeEnum() == MessageEntity.Scope.LOCAL) {
            binding.detailScopeBadge.setBackgroundResource(R.drawable.bg_badge_positive);
            binding.detailScopeBadge.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.echo_positive_accent));
        } else {
            binding.detailScopeBadge.setBackgroundResource(R.drawable.bg_badge_primary);
            binding.detailScopeBadge.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.echo_primary_accent));
        }
        binding.detailScopeBadge.setText(scopeLabel);
    }

    private void bindPriorityBadge(@NonNull MessageEntity message) {
        if (message.getPriorityEnum() == MessageEntity.Priority.ALERT) {
            binding.detailPriorityBadge.setVisibility(View.VISIBLE);
            binding.detailPriorityBadge.setText(R.string.priority_badge_urgent);
            // Show urgent banner with animation
            showUrgentBanner();
        } else {
            binding.detailPriorityBadge.setVisibility(View.GONE);
            binding.detailUrgentBanner.setVisibility(View.GONE);
        }
    }

    /**
     * Animate the urgent banner: fade in + slide down from 4dp over 180ms.
     */
    private void showUrgentBanner() {
        binding.detailUrgentBanner.setAlpha(0f);
        binding.detailUrgentBanner.setTranslationY(-4f * getResources().getDisplayMetrics().density);
        binding.detailUrgentBanner.setVisibility(View.VISIBLE);
        binding.detailUrgentBanner.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(new FastOutSlowInInterpolator())
                .start();
    }

    private String getVisibleToText(@NonNull MessageEntity message) {
        return getString(R.string.detail_visible_scope_id, ScopeLabelCodec.toDisplayTag(message));
    }

    private void updateTtlDisplay(@NonNull MessageEntity message) {
        float progress = message.getTtlProgress();
        String remaining = message.formatTtlRemaining();

        // Update progress bar (max = 1000 for precision)
        binding.detailTtlProgress.setProgress((int) (progress * 1000));

        // Color based on remaining percentage
        int progressColor;
        if (progress > 0.5f) {
            progressColor = ContextCompat.getColor(requireContext(), R.color.echo_positive_accent);
        } else if (progress > 0.2f) {
            progressColor = ContextCompat.getColor(requireContext(), R.color.echo_amber_accent);
        } else {
            progressColor = ContextCompat.getColor(requireContext(), R.color.echo_alert_accent);
        }
        binding.detailTtlProgress.setProgressTintList(ColorStateList.valueOf(progressColor));

        // TTL label
        binding.detailTtlLabel.setText(getString(R.string.detail_ttl_expires_in, remaining));

        // Time range (created → expires)
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.US);
        String created = timeFmt.format(new Date(message.getCreatedAt()));
        String expires = timeFmt.format(new Date(message.getExpiresAt()));
        binding.detailTtlTimes.setText(created + "  →  " + expires);
    }

    private void startTtlUpdates(@NonNull MessageEntity message) {
        stopTtlUpdates();
        ttlUpdateRunnable = () -> {
            if (binding != null) {
                updateTtlDisplay(message);
                ttlHandler.postDelayed(ttlUpdateRunnable, TTL_UPDATE_INTERVAL_MS);
            }
        };
        ttlHandler.postDelayed(ttlUpdateRunnable, TTL_UPDATE_INTERVAL_MS);
    }

    private void stopTtlUpdates() {
        if (ttlUpdateRunnable != null) {
            ttlHandler.removeCallbacks(ttlUpdateRunnable);
            ttlUpdateRunnable = null;
        }
    }

    private void navigateBack() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTtlUpdates();
        binding = null;
    }
}
