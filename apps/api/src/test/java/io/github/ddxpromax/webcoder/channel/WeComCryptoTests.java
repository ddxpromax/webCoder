package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.ddxpromax.webcoder.channel.impl.WeComCrypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WeComCryptoTests {
    @Test void verifiesTheDocumentedSortedSha1Signature() throws Exception {
        String[] values = { "token", "1700000000", "nonce", "ciphertext" }; Arrays.sort(values);
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
        StringBuilder signature = new StringBuilder(); for (byte value : hash) signature.append(String.format("%02x", value));
        assertThat(WeComCrypto.verifySignature("token", "1700000000", "nonce", "ciphertext", signature.toString())).isTrue();
        assertThat(WeComCrypto.verifySignature("token", "1700000000", "nonce", "ciphertext", "incorrect")).isFalse();
    }
}
