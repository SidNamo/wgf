package dev.wgforwarder.app;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable passphrase-encrypted backup; no Android Keystore dependency. */
final class BackupCodec {
    private static final byte[] MAGIC = "WGF1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 250_000;
    private static final int SALT_SIZE = 16;
    private static final int IV_SIZE = 12;
    private static final int TAG_BITS = 128;
    static final int MAX_BACKUP_BYTES = 2 * 1024 * 1024;

    private BackupCodec() { }

    private static SecretKeySpec key(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            try { return new SecretKeySpec(bytes, "AES"); }
            finally { Arrays.fill(bytes, (byte) 0); }
        } finally {
            spec.clearPassword();
        }
    }

    static byte[] encrypt(byte[] plaintext, char[] password) throws Exception {
        if (password.length < 8) throw new IllegalArgumentException("백업 암호는 8자 이상이어야 합니다.");
        if (plaintext.length > MAX_BACKUP_BYTES - 64) throw new IllegalArgumentException("백업 내용이 너무 큽니다.");
        byte[] salt = new byte[SALT_SIZE];
        byte[] iv = new byte[IV_SIZE];
        SecureRandom rng = new SecureRandom();
        rng.nextBytes(salt);
        rng.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(MAGIC);
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] output = new byte[MAGIC.length + SALT_SIZE + IV_SIZE + encrypted.length];
        int offset = 0;
        System.arraycopy(MAGIC, 0, output, offset, MAGIC.length); offset += MAGIC.length;
        System.arraycopy(salt, 0, output, offset, SALT_SIZE); offset += SALT_SIZE;
        System.arraycopy(iv, 0, output, offset, IV_SIZE); offset += IV_SIZE;
        System.arraycopy(encrypted, 0, output, offset, encrypted.length);
        return output;
    }

    static byte[] decrypt(byte[] input, char[] password) throws Exception {
        if (input.length < MAGIC.length + SALT_SIZE + IV_SIZE + 16 || input.length > MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("잘못된 백업 파일 크기입니다.");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (input[i] != MAGIC[i]) throw new IllegalArgumentException("WGF 백업 파일이 아닙니다.");
        }
        int offset = MAGIC.length;
        byte[] salt = Arrays.copyOfRange(input, offset, offset + SALT_SIZE); offset += SALT_SIZE;
        byte[] iv = Arrays.copyOfRange(input, offset, offset + IV_SIZE); offset += IV_SIZE;
        byte[] encrypted = Arrays.copyOfRange(input, offset, input.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(MAGIC);
        try {
            return cipher.doFinal(encrypted);
        } catch (AEADBadTagException badPasswordOrCorruption) {
            throw new IllegalArgumentException("백업 암호가 틀렸거나 파일이 손상되었습니다.");
        }
    }
}
