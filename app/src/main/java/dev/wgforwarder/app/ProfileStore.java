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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.nio.file.Files;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Saved WireGuard profiles (configs encrypted with the Android Keystore), their port
 * forwarding rules and which profiles should be running.
 *
 * Every rule belongs to one profile through its "profileId". Several profiles can be
 * connected at once; the ids the user wants connected are kept in "activeProfiles" so
 * the service can bring them back after a restart, a reboot or a network change.
 */
final class ProfileStore {
    static final String PREFS = "settings";
    private static final String KEY_ALIAS = "wireguard_config_key_v1";
    private static final int SCHEMA = 2;
    private static final int BACKUP_VERSION = 2;
    /** Guards read-modify-write of preferences shared by the UI and service threads. */
    private static final Object LOCK = new Object();
    private final Context context;
    private final SharedPreferences prefs;

    ProfileStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrate();
    }

    SharedPreferences prefs() { return prefs; }

    /**
     * Schema 1 had one selected profile, a single global rule list and a
     * "desiredRunning" flag. Its rules now belong to the profile that was selected
     * (or the first one), and a running profile stays running.
     */
    private void migrate() {
        if (prefs.getInt("schema", 1) >= SCHEMA) return;
        synchronized (LOCK) {
            if (prefs.getInt("schema", 1) >= SCHEMA) return;
            JSONArray list = profiles();
            String selected = prefs.getString("selected", "");
            if (!hasProfile(selected)) {
                JSONObject first = list.optJSONObject(0);
                selected = first == null ? "" : first.optString("id");
            }
            JSONArray rules = rules();
            try {
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject r = rules.optJSONObject(i);
                    if (r != null && r.optString("profileId").isEmpty()) r.put("profileId", selected);
                }
            } catch (Exception ignored) { }
            Set<String> active = new LinkedHashSet<>();
            if (prefs.getBoolean("desiredRunning", false) && !selected.isEmpty()) active.add(selected);
            prefs.edit().putString("rules", rules.toString())
                    .putString("activeProfiles", new JSONArray(active).toString())
                    .remove("desiredRunning").remove("lastError").remove("selected")
                    .putInt("schema", SCHEMA).commit();
        }
    }

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

    // ----- profiles -----

    JSONArray profiles() {
        try { return new JSONArray(prefs.getString("profiles", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    List<String> profileIds() {
        List<String> ids = new ArrayList<>();
        JSONArray arr = profiles();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry != null && !entry.optString("id").isEmpty()) ids.add(entry.optString("id"));
        }
        return ids;
    }

    boolean hasProfile(String id) { return id != null && !id.isEmpty() && profileIds().contains(id); }

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

    /** Saves a new profile and returns its id. Rules without a valid owner join it. */
    String addProfile(String name, String text) throws Exception {
        validateConfig(text);
        String id = UUID.randomUUID().toString();
        encryptedWrite(id, text);
        synchronized (LOCK) {
            JSONArray arr = profiles();
            Set<String> known = new HashSet<>(profileIds());
            JSONObject entry = new JSONObject();
            entry.put("id", id);
            entry.put("name", name.trim().isEmpty() ? "Home" : name.trim());
            arr.put(entry);
            JSONArray rules = rules();
            for (int i = 0; i < rules.length(); i++) {
                JSONObject r = rules.optJSONObject(i);
                if (r != null && !known.contains(r.optString("profileId"))) r.put("profileId", id);
            }
            prefs.edit().putString("profiles", arr.toString()).putString("rules", rules.toString()).apply();
        }
        return id;
    }

    void updateProfile(String id, String name, String text) throws Exception {
        if (!hasProfile(id)) throw new IllegalStateException("프로필 목록에서 선택한 항목을 찾지 못했습니다.");
        validateConfig(text);
        encryptedWrite(id, text);
        synchronized (LOCK) {
            JSONArray arr = profiles();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.optJSONObject(i);
                if (entry != null && id.equals(entry.optString("id"))) {
                    entry.put("name", name.trim().isEmpty() ? "Home" : name.trim());
                    prefs.edit().putString("profiles", arr.toString()).apply();
                    return;
                }
            }
        }
        throw new IllegalStateException("프로필 목록에서 선택한 항목을 찾지 못했습니다.");
    }

    String configText(String id) throws Exception {
        if (!hasProfile(id)) throw new IllegalStateException("WireGuard 프로필을 찾지 못했습니다.");
        return decryptConfig(id);
    }

    /** Deletes the profile, its encrypted config and its forwarding rules. */
    void deleteProfile(String id) {
        if (!hasProfile(id)) return;
        if (secretFile(id).exists() && !secretFile(id).delete()) {
            // A failed deletion must not silently discard the profile entry.
            return;
        }
        synchronized (LOCK) {
            JSONArray old = profiles();
            JSONArray updated = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject p = old.optJSONObject(i);
                if (p != null && !id.equals(p.optString("id"))) updated.put(p);
            }
            JSONArray oldRules = rules();
            JSONArray keptRules = new JSONArray();
            for (int i = 0; i < oldRules.length(); i++) {
                JSONObject r = oldRules.optJSONObject(i);
                if (r != null && !id.equals(r.optString("profileId"))) keptRules.put(r);
            }
            Set<String> active = activeProfiles();
            active.remove(id);
            JSONObject errors = errors();
            errors.remove(id);
            prefs.edit().putString("profiles", updated.toString())
                    .putString("rules", keptRules.toString())
                    .putString("activeProfiles", new JSONArray(active).toString())
                    .putString("profileErrors", errors.toString())
                    .apply();
        }
    }

    // ----- which profiles should run, and their last error -----

    Set<String> activeProfiles() {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("activeProfiles", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) { }
        return out;
    }

    boolean isActive(String id) { return activeProfiles().contains(id); }

    void setActive(String id, boolean active) {
        synchronized (LOCK) {
            Set<String> set = activeProfiles();
            boolean changed = active ? set.add(id) : set.remove(id);
            if (changed) prefs.edit().putString("activeProfiles", new JSONArray(set).toString()).commit();
        }
    }

    private JSONObject errors() {
        try { return new JSONObject(prefs.getString("profileErrors", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    String profileError(String id) { return errors().optString(id, ""); }

    /** Stores the last connection problem of a profile; null or empty clears it. */
    void setProfileError(String id, String message) {
        synchronized (LOCK) {
            JSONObject errors = errors();
            try {
                if (message == null || message.isEmpty()) {
                    if (!errors.has(id)) return;
                    errors.remove(id);
                } else {
                    if (message.equals(errors.optString(id))) return;
                    errors.put(id, message);
                }
            } catch (Exception ignored) { return; }
            prefs.edit().putString("profileErrors", errors.toString()).apply();
        }
    }

    // ----- forwarding rules -----

    JSONArray rules() {
        try { return new JSONArray(prefs.getString("rules", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    /** Rules of one profile, in the format the engine expects. */
    JSONArray rulesFor(String profileId) {
        JSONArray all = rules();
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && profileId.equals(r.optString("profileId"))) out.put(r);
        }
        return out;
    }

    int enabledRuleCount(String profileId) {
        JSONArray own = rulesFor(profileId);
        int count = 0;
        for (int i = 0; i < own.length(); i++) {
            JSONObject r = own.optJSONObject(i);
            if (r != null && r.optBoolean("enabled", true)) count++;
        }
        return count;
    }

    void saveRules(JSONArray rules) {
        synchronized (LOCK) { prefs.edit().putString("rules", rules.toString()).apply(); }
    }

    static JSONObject rule(String profileId, String name, String localAddress, int local, String targetHost,
                           int targetPort, boolean enabled, String mode, String tlsHost, List<String> allowedDomains) {
        JSONObject o = new JSONObject();
        try {
            o.put("profileId", profileId);
            o.put("name", name);
            o.put("localAddress", localAddress);
            o.put("localPort", local);
            o.put("targetHost", targetHost);
            o.put("targetPort", targetPort);
            o.put("enabled", enabled);
            o.put("mode", mode);
            o.put("tlsHost", tlsHost);
            o.put("allowedDomains", new JSONArray(allowedDomains));
        } catch (Exception ignored) { }
        return o;
    }

    static String localAddress(JSONObject rule) {
        String address = rule.optString("localAddress", "127.0.0.1").trim();
        return address.isEmpty() ? "127.0.0.1" : address;
    }

    // ----- backup -----

    // Explicit backup is portable across devices: it is encrypted with a user
    // passphrase rather than the non-exportable Android Keystore key.
    byte[] backup(char[] password) throws Exception {
        JSONObject document = new JSONObject();
        document.put("version", BACKUP_VERSION);
        JSONArray saved = profiles();
        JSONArray exported = new JSONArray();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.getJSONObject(i);
            String id = item.getString("id");
            order.add(id);
            JSONObject entry = new JSONObject();
            entry.put("name", item.optString("name"));
            entry.put("config", decryptConfig(id));
            exported.put(entry);
        }
        // Rules refer to their profile by position, since ids are device-local.
        JSONArray rules = rules();
        JSONArray exportedRules = new JSONArray();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = new JSONObject(rules.getJSONObject(i).toString());
            r.put("profile", order.indexOf(r.optString("profileId")));
            r.remove("profileId");
            exportedRules.put(r);
        }
        document.put("profiles", exported);
        document.put("rules", exportedRules);
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

    /** Replaces all profiles and rules. Accepts version 1 (single rule list) and 2 backups. */
    void restore(byte[] backup, char[] password) throws Exception {
        byte[] plaintext = BackupCodec.decrypt(backup, password);
        JSONObject document;
        try { document = new JSONObject(new String(plaintext, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        int version = document.getInt("version");
        if (version != 1 && version != BACKUP_VERSION) throw new IllegalArgumentException("지원하지 않는 백업 버전입니다.");
        JSONArray incoming = document.getJSONArray("profiles");
        JSONArray forwarding = document.getJSONArray("rules");
        if (incoming.length() > 50 || forwarding.length() > 100) {
            throw new IllegalArgumentException("백업 항목 수가 너무 많습니다.");
        }
        // Version 1 rules all belonged to the selected profile.
        int legacyOwner = -1;
        if (version == 1) {
            int selectedIndex = document.getInt("selectedIndex");
            if (selectedIndex < -1 || selectedIndex >= incoming.length()) {
                throw new IllegalArgumentException("잘못된 선택 프로필입니다.");
            }
            legacyOwner = selectedIndex >= 0 ? selectedIndex : (incoming.length() > 0 ? 0 : -1);
        }
        // Validate the entire backup before replacing any saved settings.
        Set<String> listeners = new HashSet<>();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject item = incoming.getJSONObject(i);
            validateConfig(item.getString("config"));
            if (item.getString("name").length() > 200) throw new IllegalArgumentException("프로필 이름이 너무 깁니다.");
        }
        int[] owners = new int[forwarding.length()];
        for (int i = 0; i < forwarding.length(); i++) {
            JSONObject r = forwarding.getJSONObject(i);
            int owner = version == 1 ? legacyOwner : r.optInt("profile", -1);
            if (owner < -1 || owner >= incoming.length()) {
                throw new IllegalArgumentException("백업 규칙의 프로필 정보가 잘못되었습니다.");
            }
            owners[i] = owner;
            int local = r.getInt("localPort");
            int target = r.getInt("targetPort");
            String host = r.getString("targetHost");
            String localAddress = r.optString("localAddress", "127.0.0.1").trim();
            if (localAddress.isEmpty()) localAddress = "127.0.0.1";
            String mode = r.optString("mode", "tcp");
            if ((local != 443 && (local < 1024 || local > 65535)) || target < 1 || target > 65535 || !validIPv4(host) ||
                    !validLoopbackIPv4(localAddress) ||
                    (!"tcp".equals(mode) && !"http-https".equals(mode))) {
                throw new IllegalArgumentException("백업에 잘못된 포워딩 규칙이 있습니다.");
            }
            r.put("localAddress", localAddress);
            if ("http-https".equals(mode) && !validTLSHost(r.optString("tlsHost"))) {
                throw new IllegalArgumentException("백업에 잘못된 HTTPS 도메인이 있습니다.");
            }
            if (r.has("allowedDomains")) {
                JSONArray domains = r.optJSONArray("allowedDomains");
                if (domains == null || domains.length() > DomainList.MAX
                        || ("http-https".equals(mode) && domains.length() > 0)) {
                    throw new IllegalArgumentException("백업에 잘못된 허용 도메인 목록이 있습니다.");
                }
                for (int d = 0; d < domains.length(); d++) {
                    Object item = domains.get(d);
                    if (!(item instanceof String) || !DomainList.valid(DomainList.normalize((String) item))) {
                        throw new IllegalArgumentException("백업에 잘못된 허용 도메인이 있습니다.");
                    }
                }
            }
            // Listeners only clash within one profile; different profiles may reuse them.
            if (r.getBoolean("enabled") && !listeners.add(owner + "|" + localAddress + ":" + local)) {
                throw new IllegalArgumentException("백업의 한 프로필에 중복 로컬 IP/포트가 있습니다.");
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
            for (int i = 0; i < forwarding.length(); i++) {
                JSONObject r = forwarding.getJSONObject(i);
                r.remove("profile");
                r.put("profileId", owners[i] < 0 ? "" : newIds.get(owners[i]));
            }
            JSONArray previous = profiles();
            boolean success;
            synchronized (LOCK) {
                success = prefs.edit()
                        .putString("profiles", newProfiles.toString())
                        .putString("rules", forwarding.toString())
                        .putBoolean("autoStart", autoStart)
                        .putBoolean("autoReconnect", autoReconnect)
                        .putString("activeProfiles", "[]")
                        .remove("profileErrors")
                        .putInt("schema", SCHEMA)
                        .commit();
            }
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

    static boolean validLoopbackIPv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4 || !"127".equals(parts[0])) return false;
        try {
            for (String part : parts) {
                if (!part.matches("[0-9]{1,3}")) return false;
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255 || !part.equals(Integer.toString(value))) return false;
            }
            return true;
        } catch (NumberFormatException bad) { return false; }
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
