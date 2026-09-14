package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the QR / deep-link setup payload parser. */
public class SetupPayloadTest {

    @Test
    public void parsesTheLinkForm() {
        SorinFlowSettings s = SetupPayload.parse(
                "sorinflow://setup?server=https%3A%2F%2Fsorinflow.example%2F&account=0912%20345%206789&secret=s3cr%26t");
        assertNotNull(s);
        assertEquals("https://sorinflow.example", s.getBaseUrl());
        assertEquals("09123456789", s.getAccount());
        assertEquals("s3cr&t", s.getSecret());
        assertFalse(s.hasSecondAccount());
        assertTrue(s.isConfigured());
    }

    @Test
    public void parsesTheJsonFormWithSecondAccount() {
        SorinFlowSettings s = SetupPayload.parse(
                "{\"server\":\"https://sorinflow.example/api/scraper/otp-inbound\",\"account\":\"۰۹۱۲۳۴۵۶۷۸۹\","
                        + "\"account2\":\"09351234567\",\"secret\":\"abc\"}");
        assertNotNull(s);
        assertEquals("https://sorinflow.example", s.getBaseUrl());
        assertEquals("09123456789", s.getAccount());
        assertEquals("09351234567", s.getAccount2());
        assertTrue(s.hasSecondAccount());
    }

    @Test
    public void carriesThePanelDeviceId() {
        SorinFlowSettings s = SetupPayload.parse(
                "sorinflow://setup?server=https://sorinflow.example&account=09123456789&device=1a2b3c4d5e6f7a8b&secret=x");
        assertNotNull(s);
        assertEquals("1a2b3c4d5e6f7a8b", s.getDeviceId());
        assertEquals("", SetupPayload.parse("sorinflow://setup?server=https://sorinflow.example").getDeviceId());
    }

    @Test
    public void acceptsAnOptionalSlashBeforeTheQuery() {
        assertNotNull(SetupPayload.parse("sorinflow://setup/?server=https://sorinflow.example&account=1&secret=x"));
    }

    @Test
    public void rejectsOtherLinksAndNonHttpsServers() {
        assertNull(SetupPayload.parse("https://sorinflow.example/setup?server=x"));
        assertNull(SetupPayload.parse("sorinflow://other?server=https://sorinflow.example"));
        assertNull(SetupPayload.parse("sorinflow://setup?server=http://sorinflow.example&account=1&secret=x"));
        assertNull(SetupPayload.parse("{\"server\":\"ftp://x\"}"));
        assertNull(SetupPayload.parse("not a payload"));
        assertNull(SetupPayload.parse("{broken"));
        assertNull(SetupPayload.parse(null));
    }

    @Test
    public void missingFieldsLeaveTheSettingsUnconfigured() {
        SorinFlowSettings s = SetupPayload.parse("sorinflow://setup?server=https://sorinflow.example");
        assertNotNull(s);
        assertEquals("", s.getAccount());
        assertEquals("", s.getSecret());
        assertFalse(s.isConfigured());
    }
}
