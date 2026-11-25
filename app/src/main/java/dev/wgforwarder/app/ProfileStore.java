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
        if (!prefs.contains("rules")) {
            JSONArray initial = new JSONArray();
            initial.put(rule("Cloudreve", 5212, "192.168.0.230", 5212, true));
            initial.put(rule("Immich", 2283, "192.168.0.230", 2283, true));
            prefs.edit().putString("rules", initial.toString()).apply();
        }
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
        File file = secretFile(id);
        if (!file.isFile()) throw new IllegalStateException("프로필 암호화 파일이 없습니다.");
        byte[] all = Files.readAllBytes(file.toPath());
        if (all.length < 29) throw new IllegalStateException("프로필 암호화 파일이 손상되었습니다.");
        byte[] iv = new byte[12];
        System.arraycopy(all, 0, iv, 0, iv.length);
        byte[] data = new byte[all.length - iv.length];
        System.arraycopy(all, iv.length, data, 0, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
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
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("localPort", local);
            o.put("targetHost", targetHost);
            o.put("targetPort", targetPort);
            o.put("enabled", enabled);
        } catch (Exception ignored) { }
        return o;
    }
}
