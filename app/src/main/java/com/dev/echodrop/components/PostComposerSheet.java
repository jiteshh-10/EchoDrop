package com.dev.echodrop.components;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.dev.echodrop.R;
import com.dev.echodrop.databinding.FragmentPostComposerBinding;
import com.dev.echodrop.db.MessageEntity;
import com.dev.echodrop.util.ScopeLabelCodec;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;

/**
 * Bottom sheet dialog for composing and posting new messages.
 *
 * <p>Updated in Iteration 2:
 * <ul>
 *   <li>Creates MessageEntity instead of Message POJO</li>
 *   <li>Dedup checking is handled by the listener (HomeInboxFragment)</li>
 * </ul>
 * </p>
 */
public class PostComposerSheet extends BottomSheetDialogFragment {

    private static final String SCOPE_PREFS = "scope_label_prefs";
    private static final String KEY_ZONE_LABEL = "zone_label";
    private static final String KEY_EVENT_LABEL = "event_label";

    public interface OnPostListener {
        void onPost(MessageEntity entity);
    }

    private FragmentPostComposerBinding binding;
    private OnPostListener listener;

    public void setOnPostListener(OnPostListener listener) {
        this.listener = listener;
    }

    @Override
    public int getTheme() {
        return R.style.Theme_EchoDrop_BottomSheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPostComposerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupChips();
        setupUrgentToggle();
        setupCharacterCounter();
        setupButtons();
        updatePostEnabled();
    }

    private void setupChips() {
        styleChip(binding.chipScopeNearby);
        styleChip(binding.chipScopeArea);
        styleChip(binding.chipScopeEvent);
        styleChip(binding.chipTtl1h);
        styleChip(binding.chipTtl4h);
        styleChip(binding.chipTtl12h);
        styleChip(binding.chipTtl24h);

        binding.scopeGroup.check(binding.chipScopeNearby.getId());
        binding.ttlGroup.check(binding.chipTtl4h.getId());

        binding.scopeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            updateScopeLabelUi();
            updatePostEnabled();
        });

        updateScopeLabelUi();
    }

    private void styleChip(Chip chip) {
        Context context = chip.getContext();
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] bgColors = new int[]{
                ContextCompat.getColor(context, R.color.echo_primary_tint),
                ContextCompat.getColor(context, android.R.color.transparent)
        };
        int[] strokeColors = new int[]{
                ContextCompat.getColor(context, R.color.echo_primary_accent),
                ContextCompat.getColor(context, R.color.echo_border)
        };
        int[] textColors = new int[]{
                ContextCompat.getColor(context, R.color.echo_primary_accent),
                ContextCompat.getColor(context, R.color.echo_text_secondary)
        };
        chip.setChipBackgroundColor(new ColorStateList(states, bgColors));
        chip.setChipStrokeColor(new ColorStateList(states, strokeColors));
        chip.setChipStrokeWidth(1f);
        chip.setTextColor(new ColorStateList(states, textColors));
        chip.setCheckedIconVisible(false);
    }

    private void setupUrgentToggle() {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] trackColors = new int[]{
                ContextCompat.getColor(requireContext(), R.color.echo_alert_accent),
                ContextCompat.getColor(requireContext(), R.color.echo_muted_disabled)
        };
        binding.urgentSwitch.setTrackTintList(new ColorStateList(states, trackColors));
        binding.urgentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            binding.urgentLabel.setText(isChecked ? R.string.post_marked_urgent : R.string.post_mark_urgent);
            binding.urgentHint.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            animatePostButtonColor(isChecked);
        });
    }

    /**
     * Transitions the Post button color between primary and alert accent over 180ms.
     */
    private void animatePostButtonColor(boolean urgent) {
        final int fromColor = ContextCompat.getColor(requireContext(),
                urgent ? R.color.echo_primary_accent : R.color.echo_alert_accent);
        final int toColor = ContextCompat.getColor(requireContext(),
                urgent ? R.color.echo_alert_accent : R.color.echo_primary_accent);
        final ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, toColor);
        animator.setDuration(180);
        animator.addUpdateListener(animation -> {
            if (binding != null) {
                int color = (int) animation.getAnimatedValue();
                binding.postSubmit.setBackgroundTintList(ColorStateList.valueOf(color));
            }
        });
        animator.start();
    }

    private void setupCharacterCounter() {
        binding.postInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCharCounter(s.length());
                updatePostEnabled();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        updateCharCounter(0);
        binding.postScopeCustomInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePostEnabled();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void updateCharCounter(int length) {
        binding.postCharCounter.setText(getString(R.string.post_char_counter, length));
        if (length >= 240) {
            binding.postCharCounter.setTextColor(ContextCompat.getColor(requireContext(), R.color.echo_alert_accent));
        } else if (length >= 200) {
            binding.postCharCounter.setTextColor(ContextCompat.getColor(requireContext(), R.color.echo_amber_accent));
        } else {
            binding.postCharCounter.setTextColor(ContextCompat.getColor(requireContext(), R.color.echo_text_secondary));
        }
    }

    private void setupButtons() {
        binding.postClose.setOnClickListener(v -> dismiss());
        binding.postCancel.setOnClickListener(v -> dismiss());
        binding.postSubmit.setOnClickListener(v -> submit());
    }

    private void updatePostEnabled() {
        boolean hasText = binding.postInput.getText() != null && binding.postInput.getText().toString().trim().length() > 0;
        boolean scopeSelected = binding.scopeGroup.getCheckedChipId() != View.NO_ID;
        MessageEntity.Scope scope = getSelectedScope();
        boolean hasRequiredScopeLabel = !ScopeLabelCodec.requiresCustomLabel(scope)
                || getRawScopeLabel().length() > 0;
        binding.postSubmit.setEnabled(hasText && scopeSelected && hasRequiredScopeLabel);
        binding.postSubmit.setAlpha(binding.postSubmit.isEnabled() ? 1f : 0.5f);
    }

    private void submit() {
        if (!binding.postSubmit.isEnabled()) {
            return;
        }
        String text = binding.postInput.getText() == null ? "" : binding.postInput.getText().toString().trim();
        MessageEntity.Scope scope = getSelectedScope();
        MessageEntity.Priority priority = binding.urgentSwitch.isChecked()
                ? MessageEntity.Priority.ALERT : MessageEntity.Priority.NORMAL;
        long ttlMillis = getSelectedTtlMillis();
        long created = System.currentTimeMillis();
        String rawScopeLabel = getRawScopeLabel();

        if (ScopeLabelCodec.requiresCustomLabel(scope) && rawScopeLabel.isEmpty()) {
            binding.postScopeCustomInput.requestFocus();
            binding.postScopeCustomInput.setError(getString(R.string.error_scope_label_required));
            return;
        }

        MessageEntity entity = MessageEntity.create(text, scope, priority, created, created + ttlMillis);
        entity.setScopeId(ScopeLabelCodec.buildCanonicalScopeId(scope, rawScopeLabel));
        persistScopeLabel(scope, rawScopeLabel);

        if (listener != null) {
            listener.onPost(entity);
        }
        dismiss();
        // Show Snackbar on the activity's root view so it's visible after sheet dismissal
        View activityView = getActivity() != null ? getActivity().findViewById(android.R.id.content) : null;
        if (activityView != null) {
            Snackbar snackbar = Snackbar.make(activityView, R.string.post_snackbar, Snackbar.LENGTH_LONG);
            snackbar.getView().setBackgroundColor(ContextCompat.getColor(activityView.getContext(), R.color.echo_card_surface));
            snackbar.setTextColor(ContextCompat.getColor(activityView.getContext(), R.color.echo_text_primary));
            snackbar.setDuration(3000);
            snackbar.show();
        }
    }

    private MessageEntity.Scope getSelectedScope() {
        int id = binding.scopeGroup.getCheckedChipId();
        if (id == binding.chipScopeArea.getId()) {
            return MessageEntity.Scope.ZONE;
        }
        if (id == binding.chipScopeEvent.getId()) {
            return MessageEntity.Scope.EVENT;
        }
        return MessageEntity.Scope.LOCAL;
    }

    private long getSelectedTtlMillis() {
        int id = binding.ttlGroup.getCheckedChipId();
        if (id == binding.chipTtl1h.getId()) {
            return 60 * 60 * 1000L;
        }
        if (id == binding.chipTtl12h.getId()) {
            return 12 * 60 * 60 * 1000L;
        }
        if (id == binding.chipTtl24h.getId()) {
            return 24 * 60 * 60 * 1000L;
        }
        return 4 * 60 * 60 * 1000L;
    }

    private void updateScopeLabelUi() {
        MessageEntity.Scope scope = getSelectedScope();
        if (ScopeLabelCodec.requiresCustomLabel(scope)) {
            binding.postScopeCustomLabel.setVisibility(View.VISIBLE);
            binding.postScopeCustomInput.setVisibility(View.VISIBLE);
            binding.postScopeCustomInput.setError(null);
            binding.postScopeCustomInput.setHint(scope == MessageEntity.Scope.ZONE
                    ? R.string.post_scope_custom_hint_zone
                    : R.string.post_scope_custom_hint_event);
            binding.postScopeCustomInput.setText(loadScopeLabel(scope));
        } else {
            binding.postScopeCustomLabel.setVisibility(View.GONE);
            binding.postScopeCustomInput.setVisibility(View.GONE);
            binding.postScopeCustomInput.setError(null);
        }
    }

    @NonNull
    private String getRawScopeLabel() {
        return binding.postScopeCustomInput.getText() == null
                ? ""
                : binding.postScopeCustomInput.getText().toString().trim();
    }

    private void persistScopeLabel(@NonNull MessageEntity.Scope scope, @NonNull String rawLabel) {
        if (!ScopeLabelCodec.requiresCustomLabel(scope) || rawLabel.isEmpty()) {
            return;
        }
        final String key = scope == MessageEntity.Scope.ZONE ? KEY_ZONE_LABEL : KEY_EVENT_LABEL;
        requireContext()
                .getSharedPreferences(SCOPE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, rawLabel)
                .apply();
    }

    @NonNull
    private String loadScopeLabel(@NonNull MessageEntity.Scope scope) {
        if (!ScopeLabelCodec.requiresCustomLabel(scope)) {
            return "";
        }
        final String key = scope == MessageEntity.Scope.ZONE ? KEY_ZONE_LABEL : KEY_EVENT_LABEL;
        return requireContext()
                .getSharedPreferences(SCOPE_PREFS, Context.MODE_PRIVATE)
                .getString(key, "");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
