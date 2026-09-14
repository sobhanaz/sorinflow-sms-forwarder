package tech.bogomolov.incomingsmsgateway;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;

/**
 * Renders the app's screens in known states and saves PNG screenshots to
 * {@code /sdcard/Download/sorinflow-screenshots} via the shell's {@code screencap}
 * (the app's own directories are wiped when Gradle uninstalls it after the run),
 * which CI pulls into an artifact for the README. It doubles as a smoke test of
 * {@link StatusCard#bind()} with seeded data: the configured account must show on
 * the card. Nothing here starts the foreground service.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenshotsTest {

    private static final String SERVER = "https://sorinflow.example";
    private static final String ACCOUNT = "09123456789";
    private static final String SCREENSHOT_DIR = "/sdcard/Download/sorinflow-screenshots";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule
    public GrantPermissionRule permissionRule = GrantPermissionRule.grant(permissionsToGrant());

    private static String[] permissionsToGrant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{Manifest.permission.RECEIVE_SMS};
    }

    @Before
    public void reset() {
        clearState();
    }

    @After
    public void tearDown() {
        clearState();
        setLocale(LocaleListCompat.getEmptyLocaleList());
    }

    @Test
    public void mainScreenBeforeSetup() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.status_account)).check(matches(withText(R.string.status_not_configured)));
            capture("01-main-before-setup");
        }
    }

    @Test
    public void setupScreen() {
        try (ActivityScenario<SetupActivity> ignored = ActivityScenario.launch(SetupActivity.class)) {
            onView(withId(R.id.input_server_url)).perform(replaceText(SERVER));
            onView(withId(R.id.input_account_phone)).perform(replaceText(ACCOUNT));
            onView(withId(R.id.input_shared_secret))
                    .perform(replaceText("shared-secret"), closeSoftKeyboard());
            capture("02-setup");
        }
    }

    @Test
    public void mainScreenConfigured() {
        seedConfiguredState();
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.status_account))
                    .check(matches(withText(context.getString(R.string.status_account, ACCOUNT))));
            capture("03-main-configured");
        }
    }

    @Test
    public void mainScreenConfiguredPersian() {
        seedConfiguredState();
        setLocale(LocaleListCompat.forLanguageTags("fa"));
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            capture("04-main-configured-fa");
        }
    }

    // Settings, both rules, a successful heartbeat and one delivered contact code
    // (the shape DeliveryStatus.recordMessage writes), without any network call.
    private void seedConfiguredState() {
        SorinFlowSettings settings = new SorinFlowSettings(SERVER, ACCOUNT, "shared-secret");
        settings.save(context);
        SorinFlowRules.apply(context, settings);

        long now = System.currentTimeMillis();
        DeliveryStatus.prefs(context).edit()
                .putLong(DeliveryStatus.KEY_HB_TIME, now - 90_000L)
                .putInt(DeliveryStatus.KEY_HB_HTTP, 200)
                .putLong(DeliveryStatus.KEY_HB_RTT, 143L)
                .putBoolean(DeliveryStatus.KEY_HB_OK, true)
                .putString(DeliveryStatus.KEY_HB_REASON, "")
                .putLong(DeliveryStatus.KEY_MSG_TIME, now - 35_000L)
                .putString(DeliveryStatus.KEY_MSG_KIND, SorinFlowRules.KIND_CONTACT)
                .putString(DeliveryStatus.KEY_MSG_CODE, OtpCodes.mask("523969"))
                .putInt(DeliveryStatus.KEY_MSG_HTTP, 200)
                .putLong(DeliveryStatus.KEY_MSG_RTT, 412L)
                .putLong(DeliveryStatus.KEY_MSG_SINCE_RECEIVED, 1_280L)
                .putString(DeliveryStatus.KEY_MSG_RESULT, DeliveryStatus.RESULT_OK)
                .putString(DeliveryStatus.KEY_MSG_REASON, "")
                .commit();
    }

    private void clearState() {
        context.getSharedPreferences(context.getString(R.string.key_phones_preference),
                Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("heartbeat", Context.MODE_PRIVATE).edit().clear().commit();
        DeliveryStatus.prefs(context).edit().clear().commit();
        SorinFlowSettings.clear(context);
        FailedMessage.clear(context);
    }

    // Per-app locale switch; must run on the main thread. Applied to activities
    // launched afterwards, reset in tearDown so other test classes stay in English.
    private void setLocale(LocaleListCompat locales) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setApplicationLocales(locales));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    // The shell user can run screencap and write to Download; the test app cannot
    // write there itself under scoped storage.
    private void capture(String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        shell("mkdir -p " + SCREENSHOT_DIR);
        shell("screencap -p " + SCREENSHOT_DIR + "/" + name + ".png");
    }

    private static void shell(String command) {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ParcelFileDescriptor fd = automation.executeShellCommand(command);
        // Drain the output so the command has finished before we move on.
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[4096];
            while (in.read(buffer) != -1) {
                // discard
            }
        } catch (IOException e) {
            throw new IllegalStateException("shell command failed: " + command, e);
        }
    }
}
