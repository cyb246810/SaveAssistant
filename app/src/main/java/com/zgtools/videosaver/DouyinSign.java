package com.zgtools.videosaver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates the a_bogus value used by Douyin's public web detail request.
 *
 * This is a Java port of the MIT-licensed signing implementation in
 * Chenwenwen1007/WeChat-ShuiYin. See THIRD_PARTY_NOTICES.md in the source package.
 */
final class DouyinSign {
    private static final String ALPHABET_S3 =
            "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe";
    private static final String ALPHABET_S4 =
            "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe";
    private static final String WINDOW_ENV =
            "1536|747|1536|834|0|30|0|0|1536|834|1536|864|1525|747|24|24|Win32";

    private DouyinSign() {}

    static String generate(String query, String userAgent) {
        int[] randomPrefix = new int[12];
        int offset = 0;
        offset = appendRandom(randomPrefix, offset, 3, 45);
        offset = appendRandom(randomPrefix, offset, 1, 0);
        appendRandom(randomPrefix, offset, 1, 5);

        int[] payload = generatePayload(query, userAgent);
        int[] all = new int[randomPrefix.length + payload.length];
        System.arraycopy(randomPrefix, 0, all, 0, randomPrefix.length);
        System.arraycopy(payload, 0, all, randomPrefix.length, payload.length);
        return resultEncrypt(all, ALPHABET_S4) + "=";
    }

    private static int appendRandom(int[] output, int offset, int option0, int option1) {
        int r = ((int) (ThreadLocalRandom.current().nextDouble() * 10000.0)) & 0xffff;
        output[offset] = ((r & 0xff & 0xaa) | (option0 & 0x55)) & 0xff;
        output[offset + 1] = ((r & 0xff & 0x55) | (option0 & 0xaa)) & 0xff;
        output[offset + 2] = ((((r >> 8) & 0xff & 0xaa) | (option1 & 0x55))) & 0xff;
        output[offset + 3] = ((((r >> 8) & 0xff & 0x55) | (option1 & 0xaa))) & 0xff;
        return offset + 4;
    }

    private static int[] generatePayload(String query, String userAgent) {
        long startTime = System.currentTimeMillis();
        byte[] queryHash = sm3(sm3((query + "cus").getBytes(StandardCharsets.UTF_8)));
        byte[] cusHash = sm3(sm3("cus".getBytes(StandardCharsets.UTF_8)));

        int[] uaInput = toUnsigned(userAgent.getBytes(StandardCharsets.US_ASCII));
        int[] uaRc4 = rc4(uaInput, new int[]{0, 1, 14});
        String uaEncrypted = resultEncrypt(uaRc4, ALPHABET_S3);
        byte[] uaHash = sm3(uaEncrypted.getBytes(StandardCharsets.UTF_8));
        long endTime = System.currentTimeMillis();

        long[] b = new long[73];
        b[8] = 3;
        b[10] = endTime;
        b[16] = startTime;
        b[18] = 44;

        putLongBytes(b, 20, b[16]);
        b[24] = (b[16] >>> 32) & 0xff;
        b[25] = (b[16] >>> 40) & 0xff;
        putIntBytes(b, 26, 0);
        b[30] = 0;
        b[31] = 1;
        b[32] = 0;
        b[33] = 0;
        putIntBytes(b, 34, 14);
        b[38] = queryHash[21] & 0xff;
        b[39] = queryHash[22] & 0xff;
        b[40] = cusHash[21] & 0xff;
        b[41] = cusHash[22] & 0xff;
        b[42] = uaHash[23] & 0xff;
        b[43] = uaHash[24] & 0xff;
        putLongBytes(b, 44, b[10]);
        b[48] = b[8];
        b[49] = (b[10] >>> 32) & 0xff;
        b[50] = (b[10] >>> 40) & 0xff;

        long pageId = 6241;
        b[51] = pageId;
        putIntBytes(b, 52, pageId);
        long aid = 6383;
        b[56] = aid;
        b[57] = aid & 0xff;
        b[58] = (aid >>> 8) & 0xff;
        b[59] = (aid >>> 16) & 0xff;
        b[60] = (aid >>> 24) & 0xff;

        int[] environment = toUnsigned(WINDOW_ENV.getBytes(StandardCharsets.US_ASCII));
        b[64] = environment.length;
        b[65] = b[64] & 0xff;
        b[66] = (b[64] >>> 8) & 0xff;
        b[69] = b[70] = b[71] = 0;

        int[] keys = {
                18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21,
                27, 54, 55, 31, 35, 57, 39, 41, 43, 22, 28, 32,
                60, 36, 23, 29, 33, 37, 44, 45, 59, 46, 47, 48,
                49, 50, 24, 25, 65, 66, 70, 71
        };
        int checksum = 0;
        for (int key : keys) checksum ^= (int) b[key];

        int[] plain = new int[keys.length + environment.length + 1];
        int p = 0;
        for (int key : keys) plain[p++] = ((int) b[key]) & 0xffff;
        for (int value : environment) plain[p++] = value;
        plain[p] = checksum & 0xffff;
        return rc4(plain, new int[]{121});
    }

    private static void putLongBytes(long[] output, int offset, long value) {
        output[offset] = (value >>> 24) & 0xff;
        output[offset + 1] = (value >>> 16) & 0xff;
        output[offset + 2] = (value >>> 8) & 0xff;
        output[offset + 3] = value & 0xff;
    }

    private static void putIntBytes(long[] output, int offset, long value) {
        output[offset] = (value >>> 24) & 0xff;
        output[offset + 1] = (value >>> 16) & 0xff;
        output[offset + 2] = (value >>> 8) & 0xff;
        output[offset + 3] = value & 0xff;
    }

    private static int[] rc4(int[] input, int[] key) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key[i % key.length]) & 0xff;
            int temp = s[i]; s[i] = s[j]; s[j] = temp;
        }
        int i = 0;
        j = 0;
        int[] output = new int[input.length];
        for (int k = 0; k < input.length; k++) {
            i = (i + 1) & 0xff;
            j = (j + s[i]) & 0xff;
            int temp = s[i]; s[i] = s[j]; s[j] = temp;
            output[k] = s[(s[i] + s[j]) & 0xff] ^ input[k];
        }
        return output;
    }

    private static String resultEncrypt(int[] input, String alphabet) {
        StringBuilder result = new StringBuilder((input.length / 3) * 4);
        for (int offset = 0; offset + 2 < input.length; offset += 3) {
            int value = ((input[offset] & 0xff) << 16) |
                    ((input[offset + 1] & 0xff) << 8) | (input[offset + 2] & 0xff);
            result.append(alphabet.charAt((value & 0xfc0000) >>> 18));
            result.append(alphabet.charAt((value & 0x03f000) >>> 12));
            result.append(alphabet.charAt((value & 0x000fc0) >>> 6));
            result.append(alphabet.charAt(value & 0x3f));
        }
        return result.toString();
    }

    private static int[] toUnsigned(byte[] bytes) {
        int[] output = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) output[i] = bytes[i] & 0xff;
        return output;
    }

    private static byte[] sm3(byte[] input) {
        int[] iv = {
                0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
        };
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        padded.write(input, 0, input.length);
        padded.write(0x80);
        while ((padded.size() % 64) != 56) padded.write(0);
        long bitLength = ((long) input.length) * 8L;
        for (int shift = 56; shift >= 0; shift -= 8) padded.write((int) (bitLength >>> shift) & 0xff);
        byte[] data = padded.toByteArray();

        int[] w = new int[68];
        int[] w1 = new int[64];
        for (int block = 0; block < data.length; block += 64) {
            for (int j = 0; j < 16; j++) {
                int o = block + j * 4;
                w[j] = ((data[o] & 0xff) << 24) | ((data[o + 1] & 0xff) << 16) |
                        ((data[o + 2] & 0xff) << 8) | (data[o + 3] & 0xff);
            }
            for (int j = 16; j < 68; j++) {
                int x = w[j - 16] ^ w[j - 9] ^ Integer.rotateLeft(w[j - 3], 15);
                w[j] = x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23) ^
                        Integer.rotateLeft(w[j - 13], 7) ^ w[j - 6];
            }
            for (int j = 0; j < 64; j++) w1[j] = w[j] ^ w[j + 4];

            int a = iv[0], b = iv[1], c = iv[2], d = iv[3];
            int e = iv[4], f = iv[5], g = iv[6], h = iv[7];
            for (int j = 0; j < 64; j++) {
                int tj = j < 16 ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(tj, j), 7);
                int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
                int ff = j < 16 ? a ^ b ^ c : (a & b) | (a & c) | (b & c);
                int gg = j < 16 ? e ^ f ^ g : (e & f) | (~e & g);
                int tt1 = ff + d + ss2 + w1[j];
                int tt2 = gg + h + ss1 + w[j];
                d = c;
                c = Integer.rotateLeft(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = Integer.rotateLeft(f, 19);
                f = e;
                e = tt2 ^ Integer.rotateLeft(tt2, 9) ^ Integer.rotateLeft(tt2, 17);
            }
            iv[0] ^= a; iv[1] ^= b; iv[2] ^= c; iv[3] ^= d;
            iv[4] ^= e; iv[5] ^= f; iv[6] ^= g; iv[7] ^= h;
        }

        byte[] output = new byte[32];
        for (int i = 0; i < iv.length; i++) {
            output[i * 4] = (byte) (iv[i] >>> 24);
            output[i * 4 + 1] = (byte) (iv[i] >>> 16);
            output[i * 4 + 2] = (byte) (iv[i] >>> 8);
            output[i * 4 + 3] = (byte) iv[i];
        }
        return output;
    }
}
