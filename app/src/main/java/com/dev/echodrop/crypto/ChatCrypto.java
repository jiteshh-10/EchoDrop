package com.dev.echodrop.crypto;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for private chat messages.
 *
 * <ul>
 *   <li><b>Key derivation:</b> PBKDF2WithHmacSHA256, 10 000 iterations, 256-bit key.</li>
 *   <li><b>Encryption:</b> AES/GCM/NoPadding, 96-bit IV prepended to ciphertext.</li>
 *   <li><b>Storage format:</b> Base64( IV‖ciphertext‖GCM-tag ).</li>
 * </ul>
 *
 * <p>The key is never stored — it is derived from the chat code each session.</p>
 */
public final class ChatCrypto {

    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** Fixed application-level salt for PBKDF2. */
    @VisibleForTesting
    static final byte[] SALT = "EchoDrop-ChatKey-v1".getBytes(StandardCharsets.UTF_8);

    private static final int ITERATION_COUNT = 10_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int IV_LENGTH_BYTES = 12; // 96 bits
    private static final int GCM_TAG_BITS = 128;

    private ChatCrypto() { /* utility class */ }

    // ──────────────────── Key derivation ────────────────────

    /**
     * Derives a 256-bit AES key from the chat code using PBKDF2.
     *
     * @param chatCode the raw 8-character chat code (no dash).
     * @return AES {@link SecretKey}.
     */
    @NonNull
    public static SecretKey deriveKey(@NonNull String chatCode) {
        try {
            final PBEKeySpec spec = new PBEKeySpec(
                    chatCode.toCharArray(), SALT, ITERATION_COUNT, KEY_LENGTH_BITS);
            final SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            final byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    // ──────────────────── Encrypt ────────────────────

    /**
     * Encrypts plaintext to a Base64 token: {@code Base64( IV ‖ ciphertext ‖ tag )}.
     *
     * @param plaintext the message text.
     * @param key       AES-256 key derived from chat code.
     * @return Base64-encoded ciphertext string.
     */
    @NonNull
    public static String encrypt(@NonNull String plaintext, @NonNull SecretKey key) {
        try {
            final byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

            final byte[] encrypted = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext+tag
            final byte[] combined = new byte[IV_LENGTH_BYTES + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH_BYTES, encrypted.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    // ──────────────────── Decrypt ────────────────────

    /**
     * Decrypts a Base64 token back to plaintext.
     *
     * @param cipherToken Base64-encoded string from {@link #encrypt}.
     * @param key         AES-256 key derived from chat code.
     * @return original plaintext.
     */
    @NonNull
    public static String decrypt(@NonNull String cipherToken, @NonNull SecretKey key) {
        try {
            final byte[] combined = Base64.decode(cipherToken, Base64.NO_WRAP);

            final byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

            final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

            final byte[] decrypted = cipher.doFinal(
                    combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
