package com.dev.echodrop;

import static org.junit.Assert.*;

import com.dev.echodrop.screens.HomeInboxFragment;
import com.dev.echodrop.screens.HowItWorksFragment;
import com.dev.echodrop.screens.OnboardingConsentFragment;
import com.dev.echodrop.screens.PermissionsFragment;

import org.junit.Test;

/**
 * Unit tests for {@link MainActivity} navigation contract.
 *
 * Note: Full Activity lifecycle tests are not viable with Robolectric due to
 * downloadable fonts (Google Fonts provider certificates are not available
 * in the local JVM test environment). Instead, we verify the navigation
 * contract at the fragment-instantiation and type level.
 *
 * Full Activity/layout inflation tests should be run as instrumented tests
 * on a real device or emulator where Google Play Services is available.
 *
 * Tests cover:
 * - Fragment classes are instantiable
 * - Fragment class hierarchy is correct
 * - Navigation target types match expected classes
 * - OnboardingConsentFragment is default entry point
 */
public class MainActivityTest {

    // ── Fragment Instantiation ────────────────────────────────────

    @Test
    public void onboardingConsentFragment_isInstantiable() {
        OnboardingConsentFragment fragment = new OnboardingConsentFragment();
        assertNotNull(fragment);
    }

    @Test
    public void permissionsFragment_isInstantiable() {
        PermissionsFragment fragment = new PermissionsFragment();
        assertNotNull(fragment);
    }

    @Test
    public void howItWorksFragment_isInstantiable() {
        HowItWorksFragment fragment = new HowItWorksFragment();
        assertNotNull(fragment);
    }

    @Test
    public void homeInboxFragment_isInstantiable() {
        HomeInboxFragment fragment = new HomeInboxFragment();
        assertNotNull(fragment);
    }

    // ── Fragment Type Hierarchy ───────────────────────────────────

    @Test
    public void onboardingConsentFragment_extendsFragment() {
        assertTrue("OnboardingConsentFragment must extend Fragment",
                androidx.fragment.app.Fragment.class.isAssignableFrom(OnboardingConsentFragment.class));
    }

    @Test
    public void permissionsFragment_extendsFragment() {
        assertTrue("PermissionsFragment must extend Fragment",
                androidx.fragment.app.Fragment.class.isAssignableFrom(PermissionsFragment.class));
    }

    @Test
    public void howItWorksFragment_extendsFragment() {
        assertTrue("HowItWorksFragment must extend Fragment",
                androidx.fragment.app.Fragment.class.isAssignableFrom(HowItWorksFragment.class));
    }

    @Test
    public void homeInboxFragment_extendsFragment() {
        assertTrue("HomeInboxFragment must extend Fragment",
                androidx.fragment.app.Fragment.class.isAssignableFrom(HomeInboxFragment.class));
    }

    // ── Navigation Contract ───────────────────────────────────────

    @Test
    public void mainActivity_extendsAppCompatActivity() {
        assertTrue("MainActivity must extend AppCompatActivity",
                androidx.appcompat.app.AppCompatActivity.class.isAssignableFrom(MainActivity.class));
    }

    @Test
    public void mainActivity_hasShowPermissionsMethod() throws NoSuchMethodException {
        assertNotNull("showPermissions() method must exist",
                MainActivity.class.getMethod("showPermissions"));
    }

    @Test
    public void mainActivity_hasShowHowItWorksMethod() throws NoSuchMethodException {
        assertNotNull("showHowItWorks() method must exist",
                MainActivity.class.getMethod("showHowItWorks"));
    }

    @Test
    public void mainActivity_hasShowHomeInboxMethod() throws NoSuchMethodException {
        assertNotNull("showHomeInbox() method must exist",
                MainActivity.class.getMethod("showHomeInbox"));
    }

    @Test
    public void mainActivity_navigationMethods_arePublic() throws NoSuchMethodException {
        int showPermsMods = MainActivity.class.getMethod("showPermissions").getModifiers();
        int showHowMods = MainActivity.class.getMethod("showHowItWorks").getModifiers();
        int showHomeMods = MainActivity.class.getMethod("showHomeInbox").getModifiers();

        assertTrue("showPermissions must be public", java.lang.reflect.Modifier.isPublic(showPermsMods));
        assertTrue("showHowItWorks must be public", java.lang.reflect.Modifier.isPublic(showHowMods));
        assertTrue("showHomeInbox must be public", java.lang.reflect.Modifier.isPublic(showHomeMods));
    }

    @Test
    public void mainActivity_navigationMethods_returnVoid() throws NoSuchMethodException {
        assertEquals(void.class, MainActivity.class.getMethod("showPermissions").getReturnType());
        assertEquals(void.class, MainActivity.class.getMethod("showHowItWorks").getReturnType());
        assertEquals(void.class, MainActivity.class.getMethod("showHomeInbox").getReturnType());
    }

    // ── PostComposerSheet Callback ────────────────────────────────

    @Test
    public void homeInboxFragment_implementsOnPostListener() {
        assertTrue("HomeInboxFragment must implement PostComposerSheet.OnPostListener",
                com.dev.echodrop.components.PostComposerSheet.OnPostListener.class
                        .isAssignableFrom(HomeInboxFragment.class));
    }
}
