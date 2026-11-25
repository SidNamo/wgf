package dev.wgforwarder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.nio.file.Files;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ProfileStore {
    static final String PREFS = "settings";
    private static final String KEY_ALIAS = "wireguard_config_key_v1";
    private final Context context;
    private final SharedPreferences prefs;

    ProfileStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    SharedPreferences prefs() { return prefs; }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private File secretFile(String id) {
        return new File(context.getNoBackupFilesDir(), "profile_" + id + ".bin");
    }

    JSONArray profiles() {
        try { return new JSONArray(prefs.getString("profiles", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    String selectedId() { return prefs.getString("selected", ""); }
    void select(String id) { prefs.edit().putString("selected", id).apply(); }

    String profileName(String id) {
        JSONArray arr = profiles();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) return entry.optString("name");
        }
        return "";
    }

    private static void validateConfig(String text) {
        if (text == null || text.isEmpty() || text.length() > 65536) {
            throw new IllegalArgumentException("설정은 1~65536자여야 합니다.");
        }
        String section = "";
        boolean interfaceSection = false;
        boolean peerSection = false;
        boolean privateKey = false;
        boolean publicKey = false;
        for (String original : text.split("\\R")) {
            String line = original.trim();
            if (line.startsWith("#") || line.startsWith(";") || line.isEmpty()) continue;
            if (line.equalsIgnoreCase("[Interface]")) {
                section = "Interface";
                interfaceSection = true;
                continue;
            }
            if (line.equalsIgnoreCase("[Peer]")) {
                section = "Peer";
                peerSection = true;
                continue;
            }
            if (line.startsWith("[")) { section = ""; continue; }
            int equal = line.indexOf('=');
            if (equal < 0) continue;
            String keyName = line.substring(0, equal).trim();
            String value = line.substring(equal + 1).trim();
            if (section.equals("Interface") && keyName.equalsIgnoreCase("PrivateKey") && !value.isEmpty()) {
                privateKey = true;
            }
            if (section.equals("Peer") && keyName.equalsIgnoreCase("PublicKey") && !value.isEmpty()) {
                publicKey = true;
            }
        }
        if (!interfaceSection || !peerSection || !privateKey || !publicKey) {
            throw new IllegalArgumentException("[Interface]/PrivateKey와 [Peer]/PublicKey가 필요한 WireGuard 설정입니다.");
        }
    }

    private void encryptedWrite(String id, String text) throws Exception {
        byte[] plaintext = text.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext);
        File target = secretFile(id);
        File pending = new File(context.getNoBackupFilesDir(), "profile_" + id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(iv);
            out.write(ciphertext);
            out.getFD().sync();
        }
        try {
            Files.move(pending.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (pending.exists()) pending.delete();
        }
    }

    void addProfile(String name, String text) throws Exception {
        validateConfig(text);
        String id = UUID.randomUUID().toString();
        encryptedWrite(id, text);
        JSONArray arr = profiles();
        JSONObject entry = new JSONObject();
        entry.put("id", id);
        entry.put("name", name.trim().isEmpty() ? "Home" : name.trim());
        arr.put(entry);
        prefs.edit().putString("profiles", arr.toString()).putString("selected", id).apply();
    }

    void updateSelected(String name, String text) throws Exception {
        String id = selectedId();
        if (id.isEmpty()) throw new IllegalStateException("수정할 프로필을 먼저 선택하세요.");
        validateConfig(text);
        encryptedWrite(id, text);
        JSONArray arr = profiles();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) {
                entry.put("name", name.trim().isEmpty() ? "Home" : name.trim());
                prefs.edit().putString("profiles", arr.toString()).apply();
                return;
            }
        }
        throw new IllegalStateException("프로필 목록에서 선택한 항목을 찾지 못했습니다.");
    }

    String configText() throws Exception {
        String id = selectedId();
        if (id.isEmpty()) throw new IllegalStateException("WireGuard 프로필을 가져오세요.");
        return decryptConfig(id);
    }

    void deleteSelected() {
        String id = selectedId();
        if (id.isEmpty()) return;
        JSONArray old = profiles();
        JSONArray updated = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject p = old.optJSONObject(i);
            if (p != null && !id.equals(p.optString("id"))) updated.put(p);
        }
        if (secretFile(id).exists() && !secretFile(id).delete()) {
            // A failed deletion must not silently discard the profile entry.
            return;
        }
        String next = updated.length() > 0 ? updated.optJSONObject(0).optString("id") : "";
        prefs.edit().putString("profiles", updated.toString()).putString("selected", next).apply();
    }

    JSONArray rules() {
        try { return new JSONArray(prefs.getString("rules", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    void saveRules(JSONArray rules) { prefs.edit().putString("rules", rules.toString()).apply(); }
    static JSONObject rule(String name, int local, String targetHost, int targetPort, boolean enabled) {
        return rule(name, local, targetHost, targetPort, enabled, "tcp", "");
    }
    static JSONObject rule(String name, int local, String targetHost, int targetPort,
                           boolean enabled, String mode, String tlsHost) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("localPort", local);
            o.put("targetHost", targetHost);
            o.put("targetPort", targetPort);
            o.put("enabled", enabled);
            o.put("mode", mode);
            o.put("tlsHost", tlsHost);
        } catch (Exception ignored) { }
        return o;
    }

    // Explicit backup is portable across devices: it is encrypted with a user
    // passphrase rather than the non-exportable Android Keystore key.
    byte[] backup(char[] password) throws Exception {
        JSONObject document = new JSONObject();
        document.put("version", 1);
        JSONArray saved = profiles();
        JSONArray exported = new JSONArray();
        int selectedIndex = -1;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.getJSONObject(i);
            String id = item.getString("id");
            if (id.equals(selectedId())) selectedIndex = i;
            JSONObject entry = new JSONObject();
            entry.put("name", item.optString("name"));
            entry.put("config", decryptConfig(id));
            exported.put(entry);
        }
        document.put("profiles", exported);
        document.put("selectedIndex", selectedIndex);
        document.put("rules", rules());
        document.put("autoStart", prefs.getBoolean("autoStart", false));
        document.put("autoReconnect", prefs.getBoolean("autoReconnect", true));
        byte[] plaintext = document.toString().getBytes(StandardCharsets.UTF_8);
        try { return BackupCodec.encrypt(plaintext, password); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private String decryptConfig(String id) throws Exception {
        File file = secretFile(id);
        if (!file.isFile()) throw new IllegalStateException("프로필 암호화 파일이 없습니다.");
        byte[] all = Files.readAllBytes(file.toPath());
        if (all.length < 29) throw new IllegalStateException("프로필 암호화 파일이 손상되었습니다.");
        byte[] iv = Arrays.copyOfRange(all, 0, 12);
        byte[] data = Arrays.copyOfRange(all, 12, all.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    void restore(byte[] backup, char[] password) throws Exception {
        byte[] plaintext = BackupCodec.decrypt(backup, password);
        JSONObject document;
        try { document = new JSONObject(new String(plaintext, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        if (document.getInt("version") != 1) throw new IllegalArgumentException("지원하지 않는 백업 버전입니다.");
        JSONArray incoming = document.getJSONArray("profiles");
        JSONArray forwarding = document.getJSONArray("rules");
        if (incoming.length() > 50 || forwarding.length() > 100) {
            throw new IllegalArgumentException("백업 항목 수가 너무 많습니다.");
        }
        int selectedIndex = document.getInt("selectedIndex");
        if (selectedIndex < -1 || selectedIndex >= incoming.length()) {
            throw new IllegalArgumentException("잘못된 선택 프로필입니다.");
        }
        // Validate the entire backup before replacing any saved settings.
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject item = incoming.getJSONObject(i);
            validateConfig(item.getString("config"));
            if (item.getString("name").length() > 200) throw new IllegalArgumentException("프로필 이름이 너무 깁니다.");
        }
        for (int i = 0; i < forwarding.length(); i++) {
            JSONObject r = forwarding.getJSONObject(i);
            int local = r.getInt("localPort");
            int target = r.getInt("targetPort");
            String host = r.getString("targetHost");
            String mode = r.optString("mode", "tcp");
            if (local < 1024 || local > 65535 || target < 1 || target > 65535 || !validIPv4(host) ||
                    (!"tcp".equals(mode) && !"http-https".equals(mode))) {
                throw new IllegalArgumentException("백업에 잘못된 포워딩 규칙이 있습니다.");
            }
            if ("http-https".equals(mode) && !validTLSHost(r.optString("tlsHost"))) {
                throw new IllegalArgumentException("백업에 잘못된 HTTPS 도메인이 있습니다.");
            }
            if (r.getBoolean("enabled") && !ports.add(local)) {
                throw new IllegalArgumentException("백업에 중복 로컬 포트가 있습니다.");
            }
        }
        boolean autoStart = document.getBoolean("autoStart");
        boolean autoReconnect = document.getBoolean("autoReconnect");

        // Stage all encrypted profile files before changing SharedPreferences.
        List<String> newIds = new ArrayList<>();
        JSONArray newProfiles = new JSONArray();
        try {
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject item = incoming.getJSONObject(i);
                String id = UUID.randomUUID().toString();
                newIds.add(id);
                encryptedWrite(id, item.getString("config"));
                JSONObject entry = new JSONObject();
                entry.put("id", id);
                entry.put("name", item.getString("name"));
                newProfiles.put(entry);
            }
            JSONArray previous = profiles();
            String selected = selectedIndex < 0 ? "" : newIds.get(selectedIndex);
            boolean success = prefs.edit()
                    .putString("profiles", newProfiles.toString())
                    .putString("selected", selected)
                    .putString("rules", forwarding.toString())
                    .putBoolean("autoStart", autoStart)
                    .putBoolean("autoReconnect", autoReconnect)
                    .putBoolean("desiredRunning", false)
                    .remove("lastError")
                    .commit();
            if (!success) throw new IllegalStateException("설정 저장에 실패했습니다.");
            for (int i = 0; i < previous.length(); i++) {
                JSONObject old = previous.optJSONObject(i);
                if (old != null) secretFile(old.optString("id")).delete();
            }
        } catch (Exception err) {
            // Staged files can be discarded; the previous settings remain intact
            // until the preferences commit has succeeded.
            for (String id : newIds) secretFile(id).delete();
            throw err;
        }
    }

    private static boolean validIPv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            if (first == 0 || first == 127 || first >= 224) return false;
            for (String part : parts) {
                if (!part.matches("[0-9]{1,3}")) return false;
                int n = Integer.parseInt(part);
                if (n > 255) return false;
            }
            return true;
        } catch (NumberFormatException bad) { return false; }
    }

    private static boolean validTLSHost(String host) {
        return host.length() <= 253 && !host.matches("[0-9.]+") &&
                host.matches("(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
    }

}
