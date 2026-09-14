package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link OtpCodes}: Persian/Arabic digit normalisation, code
 * extraction from both Divar SMS shapes, masking, and phone normalisation.
 */
public class OtpCodesTest {

    private static final String CONTACT_SMS = "کد امنیتی دریافت اطلاعات تماس دیوار:\nCode: 523969";
    private static final String LOGIN_SMS = "کد تایید دیوار:\nCode: 593154\nبرای دیگران نفرستید.";

    @Test
    public void mapsPersianDigitsToAscii() {
        assertEquals("523969", OtpCodes.normalizeDigits("۵۲۳۹۶۹"));
    }

    @Test
    public void mapsArabicIndicDigitsToAscii() {
        assertEquals("523969", OtpCodes.normalizeDigits("٥٢٣٩٦٩"));
    }

    @Test
    public void leavesOtherTextUntouched() {
        assertEquals("Code: 12 ab", OtpCodes.normalizeDigits("Code: ۱۲ ab"));
        assertEquals("", OtpCodes.normalizeDigits(null));
    }

    @Test
    public void extractsContactCode() {
        assertEquals("523969", OtpCodes.extract(CONTACT_SMS));
    }

    @Test
    public void extractsLoginCode() {
        assertEquals("593154", OtpCodes.extract(LOGIN_SMS));
    }

    @Test
    public void extractsPersianDigitsAfterLabel() {
        assertEquals("593154", OtpCodes.extract("کد تایید دیوار:\nCode: ۵۹۳۱۵۴"));
    }

    @Test
    public void labelIsCaseInsensitive() {
        assertEquals("123456", OtpCodes.extract("code:  123456"));
    }

    @Test
    public void fallsBackToStandaloneSixDigitRun() {
        assertEquals("482913", OtpCodes.extract("کد شما 482913 است"));
        assertEquals("482913", OtpCodes.extract("کد شما ۴۸۲۹۱۳ است"));
    }

    @Test
    public void ignoresLongerDigitRuns() {
        assertEquals("", OtpCodes.extract("call 09123456789 now"));
    }

    @Test
    public void emptyWhenThereIsNoCode() {
        assertEquals("", OtpCodes.extract("hello"));
        assertEquals("", OtpCodes.extract(null));
    }

    @Test
    public void maskKeepsOnlyTheLastTwoDigits() {
        assertEquals("••••69", OtpCodes.mask("523969"));
        assertEquals("••", OtpCodes.mask("12"));
        assertEquals("", OtpCodes.mask(""));
        assertEquals("", OtpCodes.mask(null));
    }

    @Test
    public void normalizesPhoneNumbers() {
        assertEquals("09123456789", OtpCodes.normalizePhone("۰۹۱۲ ۳۴۵-۶۷۸۹"));
        assertEquals("+989123456789", OtpCodes.normalizePhone("+98 912 345 6789"));
        assertEquals("09123456789", OtpCodes.normalizePhone(" 0912-345-6789 "));
    }
}
