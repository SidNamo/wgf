package dev.wgforwarder.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BackupCodecTest {
    public static void main(String[] args) throws Exception {
        byte[] input = "WireGuard secrets and forwarding rules".getBytes(StandardCharsets.UTF_8);
        char[] pass = "strong-test-password".toCharArray();
        byte[] backup = BackupCodec.encrypt(input, pass);
        if (!Arrays.equals(input, BackupCodec.decrypt(backup, pass))) {
            throw new AssertionError("roundtrip failed");
        }
        if (Arrays.equals(backup, BackupCodec.encrypt(input, pass))) {
            throw new AssertionError("encryption reused IV/salt");
        }
        assertReject(backup, "wrong-password".toCharArray());
        backup[backup.length-1] ^= 1;
        assertReject(backup, pass);
        System.out.println("BackupCodec tests passed: roundtrip, randomized encryption, wrong password, corruption");
    }
    private static void assertReject(byte[] data, char[] pass) throws Exception {
        try { BackupCodec.decrypt(data, pass); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid backup was accepted");
    }
}
