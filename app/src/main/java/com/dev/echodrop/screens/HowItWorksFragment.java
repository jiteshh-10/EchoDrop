package com.dev.echodrop.screens;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;
import com.dev.echodrop.databinding.ScreenHowItWorksBinding;

public class HowItWorksFragment extends Fragment {

    private ScreenHowItWorksBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ScreenHowItWorksBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.howToolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        binding.howToolbar.setNavigationContentDescription(R.string.content_back);
        binding.howToolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
        binding.howGetStarted.setOnClickListener(v -> navigateToHome());
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
