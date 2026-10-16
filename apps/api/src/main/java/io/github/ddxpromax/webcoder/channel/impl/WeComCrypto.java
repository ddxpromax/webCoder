package io.github.ddxpromax.webcoder.channel.impl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** WeCom's documented callback signature verification and AES-CBC decryptor. */
public final class WeComCrypto {
    private WeComCrypto() { }
    public static boolean verifySignature(String token, String timestamp, String nonce, String encrypted, String signature) {
        try {
            String[] values = { token, timestamp, nonce, encrypted }; Arrays.sort(values);
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
            StringBuilder actual = new StringBuilder(); for (byte value : digest) actual.append(String.format("%02x", value));
            return actual.toString().equalsIgnoreCase(signature);
        } catch (Exception exception) { return false; }
    }
    public static String decrypt(String encodingAesKey, String encrypted) {
        try {
            byte[] key = java.util.Base64.getDecoder().decode(encodingAesKey + "=");
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
            ByteBuffer plain = ByteBuffer.wrap(cipher.doFinal(java.util.Base64.getDecoder().decode(encrypted)));
            plain.position(16); int length = plain.getInt(); byte[] message = new byte[length]; plain.get(message);
            return new String(message, StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("Unable to decrypt WeCom callback.", exception); }
    }
}
