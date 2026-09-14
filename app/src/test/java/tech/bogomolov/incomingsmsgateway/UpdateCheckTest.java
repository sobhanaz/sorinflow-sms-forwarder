package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the version comparison behind the update offer. */
public class UpdateCheckTest {

    @Test
    public void newerTagIsDetected() {
        assertTrue(UpdateCheck.isNewer("v3.1.0", "3.0.0"));
        assertTrue(UpdateCheck.isNewer("3.0.1", "3.0.0"));
        assertTrue(UpdateCheck.isNewer("v10.0.0", "9.9.9"));
        assertTrue(UpdateCheck.isNewer("v3.1", "3.0.7"));
    }

    @Test
    public void sameOrOlderTagIsNot() {
        assertFalse(UpdateCheck.isNewer("v3.0.0", "3.0.0"));
        assertFalse(UpdateCheck.isNewer("v2.9.9", "3.0.0"));
        assertFalse(UpdateCheck.isNewer("v3.0", "3.0.0"));
    }

    @Test
    public void preReleaseSuffixIsIgnoredAndGarbageIsNotNewer() {
        assertTrue(UpdateCheck.isNewer("v3.1.0-beta1", "3.0.0"));
        assertFalse(UpdateCheck.isNewer("latest", "3.0.0"));
        assertFalse(UpdateCheck.isNewer("", "3.0.0"));
        assertFalse(UpdateCheck.isNewer(null, "3.0.0"));
    }
}
