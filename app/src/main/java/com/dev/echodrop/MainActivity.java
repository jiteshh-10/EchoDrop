package com.dev.echodrop;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.StrictMode;

import androidx.appcompat.app.AppCompatActivity;

import com.dev.echodrop.screens.BatteryGuideFragment;
import com.dev.echodrop.screens.ChatConversationFragment;
import com.dev.echodrop.screens.CreateChatFragment;
import com.dev.echodrop.screens.DiagnosticsFragment;
import com.dev.echodrop.screens.DiscoveryStatusFragment;
import com.dev.echodrop.screens.HomeInboxFragment;
import com.dev.echodrop.screens.HowItWorksFragment;
import com.dev.echodrop.screens.MessageDetailFragment;
import com.dev.echodrop.screens.OnboardingConsentFragment;
import com.dev.echodrop.screens.PermissionsFragment;
import com.dev.echodrop.screens.PrivateChatListFragment;
import com.dev.echodrop.screens.SettingsFragment;
import com.dev.echodrop.service.EchoService;
import com.dev.echodrop.util.DiagnosticsLog;
import com.dev.echodrop.workers.TtlCleanupWorker;

import timber.log.Timber;

/**
 * Single-activity host for all fragments.
 *
 * <p>Updated in Iteration 2:
 * <ul>
 *   <li>Schedules TtlCleanupWorker on first launch</li>
 *   <li>Triggers one-time TTL cleanup on every onResume</li>
 *   <li>Adds navigation to MessageDetailFragment</li>
 * </ul>
 * </p>
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "echodrop_prefs";
    private static final String PREF_ONBOARDING_COMPLETE = "onboarding_complete";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
        // Always plant diagnostics tree for in-app log viewing
        Timber.plant(new DiagnosticsLog.DiagTree());

        // StrictMode for debug builds — catch disk/network on main thread
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build());
        }

        // Schedule periodic TTL cleanup (every 15 minutes)
        TtlCleanupWorker.schedule(this);

        // Auto-start the mesh service if permissions were previously granted
        if (EchoService.hasBlePermissions(this) && EchoService.isBackgroundEnabled(this)) {
            EchoService.startService(this);
        }

        if (savedInstanceState == null) {
            if (isOnboardingComplete()) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, new HomeInboxFragment())
                        .commit();
                Timber.i("ED:NAV onboarding_complete=true → HomeInbox");
            } else {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, new OnboardingConsentFragment())
                        .commit();
                Timber.i("ED:NAV onboarding_complete=false → Onboarding");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Run immediate TTL cleanup on each foreground resume
        TtlCleanupWorker.runOnce(this);
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
        // Mark onboarding as complete when reaching home for the first time
        markOnboardingComplete();
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new HomeInboxFragment())
                .commit();
    }

    /**
     * Navigate to the message detail screen.
     */
    public void showMessageDetail(String messageId) {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, MessageDetailFragment.newInstance(messageId))
                .addToBackStack("detail")
                .commit();
    }

    /**
     * Navigate to the private chat list screen.
     */
    public void showChatList() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new PrivateChatListFragment())
                .addToBackStack("chatList")
                .commit();
    }

    /**
     * Navigate to the create chat screen.
     */
    public void showCreateChat() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new CreateChatFragment())
                .addToBackStack("createChat")
                .commit();
    }

    /**
     * Navigate to a chat conversation screen.
     */
    public void showChatConversation(String chatId, String chatCode, String chatName) {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container,
                        ChatConversationFragment.newInstance(chatId, chatCode, chatName))
                .addToBackStack("conversation")
                .commit();
    }

    public void showSettings() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new SettingsFragment())
                .addToBackStack("settings")
                .commit();
    }

    public void showBatteryGuide() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new BatteryGuideFragment())
                .addToBackStack("batteryGuide")
                .commit();
    }

    public void showDiscoveryStatus() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new DiscoveryStatusFragment())
                .addToBackStack("discoveryStatus")
                .commit();
    }

    public void showDiagnostics() {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, new DiagnosticsFragment())
                .addToBackStack("diagnostics")
                .commit();
    }

    // ──────────────────── Onboarding Persistence ────────────────────

    /** Returns true if onboarding has been completed (user reached HomeInbox). */
    private boolean isOnboardingComplete() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_ONBOARDING_COMPLETE, false);
    }

    /** Marks onboarding as complete. Called when user first reaches HomeInbox. */
    public void markOnboardingComplete() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ONBOARDING_COMPLETE, true)
                .apply();
    }
}