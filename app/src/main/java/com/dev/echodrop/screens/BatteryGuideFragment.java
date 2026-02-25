package com.dev.echodrop.screens;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.dev.echodrop.databinding.ScreenBatteryGuideBinding;

/**
 * Battery optimisation guide with collapsible OEM-specific sections.
 *
 * <p>Each OEM section (Samsung, Xiaomi, OnePlus, Stock Android) has
 * a header row with an expand/collapse arrow and a hidden body that
 * slides open when tapped. The arrow rotates 180° on toggle.</p>
 */
public class BatteryGuideFragment extends Fragment {

    private ScreenBatteryGuideBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ScreenBatteryGuideBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupToolbar();
        setupCollapsibleSections();
        setupDoneButton();
    }

    private void setupToolbar() {
        binding.batteryToolbar.setNavigationOnClickListener(v -> navigateBack());
    }

    private void setupCollapsibleSections() {
        setupSection(binding.headerSamsung, binding.bodySamsung, binding.arrowSamsung);
        setupSection(binding.headerXiaomi, binding.bodyXiaomi, binding.arrowXiaomi);
        setupSection(binding.headerOneplus, binding.bodyOneplus, binding.arrowOneplus);
        setupSection(binding.headerStock, binding.bodyStock, binding.arrowStock);
    }

    /**
     * Wires a single collapsible section: clicking the header toggles
     * the body visibility and rotates the arrow by 180°.
     */
    private void setupSection(View header, LinearLayout body, ImageView arrow) {
        header.setOnClickListener(v -> {
            final boolean expanding = body.getVisibility() == View.GONE;
            body.setVisibility(expanding ? View.VISIBLE : View.GONE);
            animateArrow(arrow, expanding);
        });
    }

    /**
     * Rotates the expand arrow between 0° and 180° over 200 ms.
     */
    private void animateArrow(ImageView arrow, boolean expanding) {
        final float fromDegrees = expanding ? 0f : 180f;
        final float toDegrees = expanding ? 180f : 0f;

        final RotateAnimation rotation = new RotateAnimation(
                fromDegrees, toDegrees,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        rotation.setDuration(200);
        rotation.setFillAfter(true);
        arrow.startAnimation(rotation);
    }

    private void setupDoneButton() {
        binding.btnDone.setOnClickListener(v -> navigateBack());
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
