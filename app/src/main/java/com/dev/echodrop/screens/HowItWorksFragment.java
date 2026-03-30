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

    private static final String ARG_FROM_SETTINGS = "from_settings";

    private ScreenHowItWorksBinding binding;
    private boolean fromSettings;

    /** Creates an instance for display from Settings (back-stack navigation). */
    @NonNull
    public static HowItWorksFragment newInstance(boolean fromSettings) {
        final HowItWorksFragment fragment = new HowItWorksFragment();
        final Bundle args = new Bundle();
        args.putBoolean(ARG_FROM_SETTINGS, fromSettings);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ScreenHowItWorksBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        fromSettings = getArguments() != null
                && getArguments().getBoolean(ARG_FROM_SETTINGS, false);

        binding.howToolbar.setNavigationIcon(R.drawable.ic_back);
        binding.howToolbar.setNavigationContentDescription(R.string.content_back);
        binding.howToolbar.setNavigationOnClickListener(v -> navigateBack());

        if (fromSettings) {
            binding.howGetStarted.setText(R.string.how_it_works_got_it);
            binding.howGetStarted.setOnClickListener(v -> navigateBack());
        } else {
            binding.howGetStarted.setOnClickListener(v -> navigateToPermissions());
        }
    }

    private void navigateBack() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    private void navigateToPermissions() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showPermissions();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
