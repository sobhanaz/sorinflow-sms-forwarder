package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented tests for {@link SorinFlowSettings}: defaults, the encrypted
 * save/load round-trip, and that the secret is not readable from the backing
 * preference file. Needs a real Keystore, so it runs on a device/emulator.
 */
@RunWith(AndroidJUnit4.class)
public class SorinFlowSettingsTest {

    // Mirrors SorinFlowSettings' private file name.
    private static final String PREFERENCE = "sorinflow_secure";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() {
        SorinFlowSettings.clear(context);
    }

    @After
    public void tearDown() {
        SorinFlowSettings.clear(context);
    }

    @Test
    public void testDefaultsAreEmptyAndNotConfigured() {
        SorinFlowSettings settings = SorinFlowSettings.load(context);

        assertEquals("", settings.getBaseUrl());
        assertEquals("", settings.getAccount());
        assertEquals("", settings.getSecret());
        assertFalse(settings.isConfigured());
    }

    @Test
    public void testSaveAndLoadRoundTrip() {
        new SorinFlowSettings("https://sorinflow.example", "09123456789", "s3cret").save(context);

        SorinFlowSettings loaded = SorinFlowSettings.load(context);
        assertEquals("https://sorinflow.example", loaded.getBaseUrl());
        assertEquals("09123456789", loaded.getAccount());
        assertEquals("s3cret", loaded.getSecret());
        assertTrue(loaded.isConfigured());
    }

    @Test
    public void testPartialSettingsAreNotConfigured() {
        new SorinFlowSettings("https://sorinflow.example", "09123456789", "").save(context);

        assertFalse(SorinFlowSettings.load(context).isConfigured());
    }

    @Test
    public void testSecretIsNotStoredInPlainText() {
        new SorinFlowSettings("https://sorinflow.example", "09123456789", "s3cret").save(context);

        // The backing file holds encrypted keys and values only.
        for (Object value : context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE)
                .getAll().values()) {
            assertFalse(String.valueOf(value).contains("s3cret"));
        }
    }
}
