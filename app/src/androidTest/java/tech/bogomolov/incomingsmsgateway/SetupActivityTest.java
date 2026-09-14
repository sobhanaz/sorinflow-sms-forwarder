package tech.bogomolov.incomingsmsgateway;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented (Espresso) tests for {@link SetupActivity}'s form validation. The
 * valid-save path is intentionally not exercised: it starts the foreground
 * service. Persistence is covered by {@link SorinFlowSettingsTest} and rule
 * installation by {@link SorinFlowRulesApplyTest}.
 */
@RunWith(AndroidJUnit4.class)
public class SetupActivityTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule
    public ActivityScenarioRule<SetupActivity> activityRule =
            new ActivityScenarioRule<>(SetupActivity.class);

    @Before
    public void clearSettings() {
        SorinFlowSettings.clear(context);
        activityRule.getScenario().recreate();
    }

    @After
    public void tearDown() {
        SorinFlowSettings.clear(context);
    }

    @Test
    public void testHttpUrlRejected() {
        fill("http://sorinflow.example", "09123456789", "s3cret");

        onView(withId(R.id.btn_setup_save)).perform(scrollTo(), click());

        onView(withId(R.id.input_server_url))
                .check(matches(hasErrorText(getResourceString(R.string.error_https_url))));
    }

    @Test
    public void testShortPhoneRejected() {
        fill("https://sorinflow.example", "0912", "s3cret");

        onView(withId(R.id.btn_setup_save)).perform(scrollTo(), click());

        onView(withId(R.id.input_account_phone))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_phone))));
    }

    @Test
    public void testEmptySecretRejected() {
        fill("https://sorinflow.example", "09123456789", "");

        onView(withId(R.id.btn_setup_save)).perform(scrollTo(), click());

        onView(withId(R.id.input_shared_secret))
                .check(matches(hasErrorText(getResourceString(R.string.error_empty_secret))));
    }

    private void fill(String url, String phone, String secret) {
        onView(withId(R.id.input_server_url)).perform(scrollTo(), replaceText(url));
        onView(withId(R.id.input_account_phone)).perform(scrollTo(), replaceText(phone));
        onView(withId(R.id.input_shared_secret))
                .perform(scrollTo(), replaceText(secret), closeSoftKeyboard());
    }

    private String getResourceString(int id) {
        Context targetContext = ApplicationProvider.getApplicationContext();
        return targetContext.getResources().getString(id);
    }
}
