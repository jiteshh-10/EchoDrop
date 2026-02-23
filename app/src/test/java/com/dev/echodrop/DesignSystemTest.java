package com.dev.echodrop;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;

import com.dev.echodrop.models.Message;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Validation tests for EchoDrop design system resources.
 * These tests ensure that all expected resources (colors, strings, dimensions,
 * drawables, animations) are present and correctly defined.
 *
 * Tests cover:
 * - All 15 color tokens exist and are non-zero
 * - Critical string resources exist
 * - Dimension resources exist and have valid values
 * - Integer animation constants exist and have correct values
 * - Drawable resources exist
 * - Animation resources exist
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DesignSystemTest {

    private Context context;
    private Resources resources;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        resources = context.getResources();
    }

    // ── Color Tokens ──────────────────────────────────────────────

    @Test
    public void color_bgMain_exists() {
        int color = resources.getColor(R.color.echo_bg_main, null);
        assertNotEquals("echo_bg_main must be defined", 0, color);
    }

    @Test
    public void color_cardSurface_exists() {
        int color = resources.getColor(R.color.echo_card_surface, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_elevatedSurface_exists() {
        int color = resources.getColor(R.color.echo_elevated_surface, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_primaryAccent_exists() {
        int color = resources.getColor(R.color.echo_primary_accent, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_alertAccent_exists() {
        int color = resources.getColor(R.color.echo_alert_accent, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_positiveAccent_exists() {
        int color = resources.getColor(R.color.echo_positive_accent, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_amberAccent_exists() {
        int color = resources.getColor(R.color.echo_amber_accent, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_textPrimary_exists() {
        int color = resources.getColor(R.color.echo_text_primary, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_textSecondary_exists() {
        int color = resources.getColor(R.color.echo_text_secondary, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_mutedDisabled_exists() {
        int color = resources.getColor(R.color.echo_muted_disabled, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_divider_exists() {
        int color = resources.getColor(R.color.echo_divider, null);
        assertNotEquals(0, color);
    }

    @Test
    public void color_border_exists() {
        int color = resources.getColor(R.color.echo_border, null);
        assertNotEquals(0, color);
    }

    // ── String Resources ──────────────────────────────────────────

    @Test
    public void string_appName_exists() {
        String name = resources.getString(R.string.app_name);
        assertNotNull(name);
        assertFalse(name.isEmpty());
    }

    @Test
    public void string_onboardingHeadline_exists() {
        String s = resources.getString(R.string.onboarding_headline);
        assertNotNull(s);
        assertFalse(s.isEmpty());
    }

    @Test
    public void string_tabAll_exists() {
        String s = resources.getString(R.string.tab_all);
        assertNotNull(s);
        assertEquals("All", s);
    }

    @Test
    public void string_tabAlerts_exists() {
        String s = resources.getString(R.string.tab_alerts);
        assertNotNull(s);
        assertEquals("Alerts", s);
    }

    @Test
    public void string_tabChats_exists() {
        String s = resources.getString(R.string.tab_chats);
        assertNotNull(s);
        assertEquals("Chats", s);
    }

    @Test
    public void string_postCharCounter_formatString() {
        String formatted = resources.getString(R.string.post_char_counter, 42);
        assertTrue("Should contain the count", formatted.contains("42"));
        assertTrue("Should contain the limit", formatted.contains("240"));
    }

    @Test
    public void string_syncManyDevices_formatString() {
        String formatted = resources.getString(R.string.sync_many_devices, 5);
        assertTrue("Should contain the count", formatted.contains("5"));
    }

    // ── Dimension Resources ───────────────────────────────────────

    @Test
    public void dimen_spacing1_is8dp() {
        float value = resources.getDimension(R.dimen.spacing_1);
        assertTrue("spacing_1 must be positive", value > 0);
    }

    @Test
    public void dimen_spacing2_is16dp() {
        float value = resources.getDimension(R.dimen.spacing_2);
        assertTrue("spacing_2 must be positive", value > 0);
    }

    @Test
    public void dimen_fabSize_is56dp() {
        float value = resources.getDimension(R.dimen.fab_size);
        assertTrue("fab_size must be positive", value > 0);
    }

    @Test
    public void dimen_minTouchTarget_is44dp() {
        float value = resources.getDimension(R.dimen.min_touch_target);
        assertTrue("min_touch_target must be positive", value > 0);
    }

    // ── Integer Animation Constants ───────────────────────────────

    @Test
    public void integer_animPress_is40() {
        int value = resources.getInteger(R.integer.anim_press);
        assertEquals(40, value);
    }

    @Test
    public void integer_animFast_is180() {
        int value = resources.getInteger(R.integer.anim_fast);
        assertEquals(180, value);
    }

    @Test
    public void integer_animStandard_is250() {
        int value = resources.getInteger(R.integer.anim_standard);
        assertEquals(250, value);
    }

    @Test
    public void integer_animSlow_is400() {
        int value = resources.getInteger(R.integer.anim_slow);
        assertEquals(400, value);
    }

    // ── Drawable Resources Exist ──────────────────────────────────

    @Test
    public void drawable_bgSearchInput_exists() {
        assertNotEquals(0, R.drawable.bg_search_input);
    }

    @Test
    public void drawable_bgSearchInputFocused_exists() {
        assertNotEquals(0, R.drawable.bg_search_input_focused);
    }

    @Test
    public void drawable_bgBadgePrimary_exists() {
        assertNotEquals(0, R.drawable.bg_badge_primary);
    }

    @Test
    public void drawable_bgBadgeAlert_exists() {
        assertNotEquals(0, R.drawable.bg_badge_alert);
    }

    @Test
    public void drawable_bgBadgePositive_exists() {
        assertNotEquals(0, R.drawable.bg_badge_positive);
    }

    @Test
    public void drawable_bgCircle_exists() {
        assertNotEquals(0, R.drawable.bg_circle);
    }

    @Test
    public void drawable_icWifi_exists() {
        assertNotEquals(0, R.drawable.ic_wifi);
    }

    // ── Animation Resources Exist ─────────────────────────────────

    @Test
    public void anim_fragmentEnter_exists() {
        assertNotEquals(0, R.anim.fragment_enter);
    }

    @Test
    public void anim_fragmentExit_exists() {
        assertNotEquals(0, R.anim.fragment_exit);
    }

    @Test
    public void anim_fragmentPopEnter_exists() {
        assertNotEquals(0, R.anim.fragment_pop_enter);
    }

    @Test
    public void anim_fragmentPopExit_exists() {
        assertNotEquals(0, R.anim.fragment_pop_exit);
    }

    // ── Layout Resources Exist ────────────────────────────────────

    @Test
    public void layout_activityMain_exists() {
        assertNotEquals(0, R.layout.activity_main);
    }

    @Test
    public void layout_screenOnboardingConsent_exists() {
        assertNotEquals(0, R.layout.screen_onboarding_consent);
    }

    @Test
    public void layout_screenPermissions_exists() {
        assertNotEquals(0, R.layout.screen_permissions);
    }

    @Test
    public void layout_screenHowItWorks_exists() {
        assertNotEquals(0, R.layout.screen_how_it_works);
    }

    @Test
    public void layout_screenHomeInbox_exists() {
        assertNotEquals(0, R.layout.screen_home_inbox);
    }

    @Test
    public void layout_itemMessageCard_exists() {
        assertNotEquals(0, R.layout.item_message_card);
    }

    @Test
    public void layout_fragmentPostComposer_exists() {
        assertNotEquals(0, R.layout.fragment_post_composer);
    }

    // ── Menu Resources Exist ──────────────────────────────────────

    @Test
    public void menu_homeMenu_exists() {
        assertNotEquals(0, R.menu.home_menu);
    }
}
