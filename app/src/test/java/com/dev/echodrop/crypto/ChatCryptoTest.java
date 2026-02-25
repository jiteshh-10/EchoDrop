package com.dev.echodrop.crypto;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import javax.crypto.SecretKey;

/**
 * Tests for AES-256-GCM encryption via ChatCrypto.
 *
 * <p>Uses Robolectric because ChatCrypto depends on android.util.Base64.</p>
 *
 * <p>Verifies:
 * <ul>
 *   <li>Round-trip encrypt→decrypt preserves plaintext</li>
 *   <li>Key derivation is deterministic for the same code</li>
 *   <li>Different codes produce different keys</li>
 *   <li>Ciphertext differs each call (random IV)</li>
 *   <li>Wrong key fails decryption</li>
 *   <li>Empty string round-trip works</li>
 *   <li>Unicode round-trip works</li>
 *   <li>Long message round-trip works</li>
 *   <li>Ciphertext is Base64 encoded</li>
 *   <li>PBKDF2 salt is correct constant</li>
 * </ul>
 * </p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ChatCryptoTest {

    private static final String CODE_A = "ABCD2345";
    private static final String CODE_B = "WXYZ6789";

    // ──────────────────── Key derivation ────────────────────

    @Test
    public void deriveKey_sameCode_returnsSameKey() {
        final SecretKey k1 = ChatCrypto.deriveKey(CODE_A);
        final SecretKey k2 = ChatCrypto.deriveKey(CODE_A);
        assertArrayEquals("Same code should derive same key bytes",
                k1.getEncoded(), k2.getEncoded());
    }

    @Test
    public void deriveKey_differentCodes_returnDifferentKeys() {
        final SecretKey k1 = ChatCrypto.deriveKey(CODE_A);
        final SecretKey k2 = ChatCrypto.deriveKey(CODE_B);
        assertFalse("Different codes should derive different keys",
                java.util.Arrays.equals(k1.getEncoded(), k2.getEncoded()));
    }

    @Test
    public void deriveKey_keyIs256Bits() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        assertEquals("Key should be 256 bits (32 bytes)", 32, key.getEncoded().length);
    }

    @Test
    public void deriveKey_algorithmIsAES() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        assertEquals("AES", key.getAlgorithm());
    }

    // ──────────────────── Encrypt / Decrypt round-trip ────────────────────

    @Test
    public void encryptDecrypt_roundTrip_preservesPlaintext() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        final String plaintext = "Hello, EchoDrop!";
        final String cipherText = ChatCrypto.encrypt(plaintext, key);
        final String decrypted = ChatCrypto.decrypt(cipherText, key);
        assertEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecrypt_emptyString_roundTrip() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        final String cipherText = ChatCrypto.encrypt("", key);
        final String decrypted = ChatCrypto.decrypt(cipherText, key);
        assertEquals("", decrypted);
    }

    @Test
    public void encryptDecrypt_unicodeText_roundTrip() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        final String text = "こんにちは 🎉 Ñoño";
        final String cipherText = ChatCrypto.encrypt(text, key);
        assertEquals(text, ChatCrypto.decrypt(cipherText, key));
    }

    @Test
    public void encryptDecrypt_longMessage_roundTrip() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("This is a long message segment. ");
        final String text = sb.toString();
        final String cipherText = ChatCrypto.encrypt(text, key);
        assertEquals(text, ChatCrypto.decrypt(cipherText, key));
    }

    // ──────────────────── IV randomness ────────────────────

    @Test
    public void encrypt_samePlaintext_differentCiphertext() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        final String text = "Same text encrypted twice";
        final String c1 = ChatCrypto.encrypt(text, key);
        final String c2 = ChatCrypto.encrypt(text, key);
        assertNotEquals("Random IV should produce different ciphertext", c1, c2);
    }

    // ──────────────────── Wrong key ────────────────────

    @Test(expected = RuntimeException.class)
    public void decrypt_wrongKey_throwsException() {
        final SecretKey rightKey = ChatCrypto.deriveKey(CODE_A);
        final SecretKey wrongKey = ChatCrypto.deriveKey(CODE_B);
        final String cipherText = ChatCrypto.encrypt("secret message", rightKey);
        ChatCrypto.decrypt(cipherText, wrongKey);
    }

    // ──────────────────── Base64 format ────────────────────

    @Test
    public void encrypt_outputIsBase64() {
        final SecretKey key = ChatCrypto.deriveKey(CODE_A);
        final String cipherText = ChatCrypto.encrypt("test", key);
        // Base64 NO_WRAP should not contain newlines
        assertFalse("Base64 output should not contain newlines",
                cipherText.contains("\n"));
        // Should be decodable
        final byte[] decoded = android.util.Base64.decode(cipherText, android.util.Base64.NO_WRAP);
        assertTrue("Decoded bytes should include IV (12 bytes) + ciphertext",
                decoded.length > 12);
    }

    // ──────────────────── PBKDF2 salt constant ────────────────────

    @Test
    public void salt_isExpectedValue() {
        final String expected = "EchoDrop-ChatKey-v1";
        assertEquals(expected, new String(ChatCrypto.SALT, java.nio.charset.StandardCharsets.UTF_8));
    }
}
