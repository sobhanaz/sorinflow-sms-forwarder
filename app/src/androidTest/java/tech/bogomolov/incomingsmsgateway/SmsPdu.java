package tech.bogomolov.incomingsmsgateway;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds a GSM SMS-DELIVER PDU with an alphanumeric sender and a UCS-2 body, the
 * shape Android hands to SMS_RECEIVED receivers, so tests can inject a realistic
 * Divar message (Persian text plus a Latin code) without a radio.
 */
final class SmsPdu {

    private SmsPdu() {
    }

    static byte[] deliver(String sender, String body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00);                                   // no SMSC in the PDU
        out.write(0x04);                                   // SMS-DELIVER, no more messages
        byte[] packedSender = pack7Bit(sender);
        out.write((int) Math.ceil(sender.length() * 7 / 4.0)); // address length in semi-octets
        out.write(0xD0);                                   // type of address: alphanumeric
        out.write(packedSender, 0, packedSender.length);
        out.write(0x00);                                   // protocol identifier
        out.write(0x08);                                   // data coding scheme: UCS-2
        byte[] timestamp = {0x62, (byte) 0x90, 0x41, (byte) 0x90, 0x21, 0x00, 0x08}; // 26-09-14 09:12:00 +02:00
        out.write(timestamp, 0, timestamp.length);
        byte[] userData = body.getBytes(StandardCharsets.UTF_16BE);
        if (userData.length > 140) {
            throw new IllegalArgumentException("body longer than one PDU");
        }
        out.write(userData.length);
        out.write(userData, 0, userData.length);
        return out.toByteArray();
    }

    // GSM 7-bit default alphabet packing (ASCII letters and digits only).
    private static byte[] pack7Bit(String text) {
        byte[] out = new byte[(text.length() * 7 + 7) / 8];
        int bitPos = 0;
        for (char c : text.toCharArray()) {
            int value = c & 0x7F;
            for (int i = 0; i < 7; i++) {
                if (((value >> i) & 1) != 0) {
                    out[bitPos / 8] |= (byte) (1 << (bitPos % 8));
                }
                bitPos++;
            }
        }
        return out;
    }
}
