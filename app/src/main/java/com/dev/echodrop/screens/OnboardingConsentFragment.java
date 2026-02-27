package com.dev.echodrop.screens;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AlphaAnimation;
import android.view.animation.ScaleAnimation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenOnboardingConsentBinding;

public class OnboardingConsentFragment extends Fragment {

    private ScreenOnboardingConsentBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ScreenOnboardingConsentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // All paths go straight to HomeInbox — permissions are requested inline
        binding.onboardingPrimaryButton.setOnClickListener(v -> navigateToHome());
        // Hide the "How It Works" link and "Skip" — single CTA only
        binding.onboardingSecondaryLink.setVisibility(View.GONE);
        binding.onboardingSkip.setVisibility(View.GONE);

        // Button press scale animation (scale 0.97 on press, 40ms)
        binding.onboardingPrimaryButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(40).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    break;
            }
            return false;
        });

        startPulseAnimation();
    }

    private void startPulseAnimation() {
        AnimationSet set = new AnimationSet(true);
        ScaleAnimation scale = new ScaleAnimation(
                1f,
                1.4f,
                1f,
                1.4f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f
        );
        scale.setDuration(2000);
        scale.setRepeatCount(Animation.INFINITE);
        scale.setRepeatMode(Animation.RESTART);
        set.addAnimation(scale);
        AlphaAnimation alpha = new AlphaAnimation(0.2f, 0f);
        alpha.setDuration(2000);
        alpha.setRepeatCount(Animation.INFINITE);
        alpha.setRepeatMode(Animation.RESTART);
        set.addAnimation(alpha);
        binding.onboardingPulseRing.startAnimation(set);
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
