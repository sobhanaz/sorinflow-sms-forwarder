package tech.bogomolov.incomingsmsgateway;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;
import java.io.InputStream;

/**
 * Runs a command as the shell user through the instrumentation. Used to drop
 * artifacts (screenshots, measurements) into /sdcard/Download, which survives
 * Gradle uninstalling the app after the run and is what CI pulls.
 */
final class TestShell {

    static final String ARTIFACT_DIR = "/sdcard/Download/sorinflow-screenshots";

    private TestShell() {
    }

    static void run(String command) {
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
