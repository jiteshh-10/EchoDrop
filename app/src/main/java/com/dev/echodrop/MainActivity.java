package com.dev.echodrop;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.dev.echodrop.screens.HomeInboxFragment;
import com.dev.echodrop.screens.HowItWorksFragment;
import com.dev.echodrop.screens.OnboardingConsentFragment;
import com.dev.echodrop.screens.PermissionsFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new OnboardingConsentFragment())
                    .commit();
        }
    }

    public void showPermissions() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new PermissionsFragment())
                .addToBackStack(null)
                .commit();
    }

    public void showHowItWorks() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new HowItWorksFragment())
                .addToBackStack(null)
                .commit();
    }

    public void showHomeInbox() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new HomeInboxFragment())
                .commit();
    }
}