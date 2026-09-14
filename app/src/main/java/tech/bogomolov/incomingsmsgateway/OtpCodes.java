package tech.bogomolov.incomingsmsgateway;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Digit helpers for Divar one-time codes and phone numbers. Persian (U+06F0..U+06F9)
 * and Arabic-Indic (U+0660..U+0669) digits are mapped to ASCII first, so patterns
 * written for Latin digits work whichever keyboard or SMS encoding produced the text.
 */
public final class OtpCodes {

    /** Digits after "Code:", the form Divar uses in both of its SMS types. */
    private static final Pattern AFTER_CODE_LABEL = Pattern.compile("(?i)Code:\\s*(\\d{4,8})");

    /** Fallback: any standalone run of exactly six digits. */
    private static final Pattern STANDALONE_SIX = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    private OtpCodes() {
    }

    public static String normalizeDigits(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '۰' && c <= '۹') {
                c = (char) ('0' + (c - '۰'));
            } else if (c >= '٠' && c <= '٩') {
                c = (char) ('0' + (c - '٠'));
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The code in an SMS body: digits after "Code:", else the first standalone 6-digit run, else "". */
    public static String extract(String smsText) {
        String text = normalizeDigits(smsText);
        Matcher labelled = AFTER_CODE_LABEL.matcher(text);
        if (labelled.find()) {
            return labelled.group(1);
        }
        Matcher standalone = STANDALONE_SIX.matcher(text);
        return standalone.find() ? standalone.group(1) : "";
    }

    /** "523969" -> "••••69": enough to tell two codes apart, not enough to reuse one. */
    public static String mask(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder();
        int visible = code.length() > 2 ? 2 : 0;
        for (int i = 0; i < code.length() - visible; i++) {
            masked.append('•');
        }
        return masked.append(code, code.length() - visible, code.length()).toString();
    }

    /** Keeps a leading '+' and the digits only, mapping Persian/Arabic digits to ASCII. */
    public static String normalizePhone(String raw) {
        String text = normalizeDigits(raw).trim();
        boolean international = text.startsWith("+");
        String digits = text.replaceAll("[^0-9]", "");
        return (international ? "+" : "") + digits;
    }
}
