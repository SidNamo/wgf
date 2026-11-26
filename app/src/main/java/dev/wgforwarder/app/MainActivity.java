package dev.wgforwarder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.WindowManager;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import engine.Engine;

public final class MainActivity extends Activity {
    private static final int PICK_CONFIG = 110;
    private static final int OPEN_BACKUP = 111;
    private static final int SAVE_BACKUP = 112;
    private char[] pendingBackupPassword;
    private ProfileStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout profilesView;
    private TextView summary;
    private TextView logs;
    // Per-profile views refreshed by the stats timer, and the last traffic sample
    // of each running profile: {elapsedRealtime, tx, rx}.
    private final Map<String, TextView> statusViews = new HashMap<>();
    private final Map<String, Button> toggleButtons = new HashMap<>();
    private final Map<String, long[]> samples = new HashMap<>();

    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            updateStats();
            handler.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new ProfileStore(this);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        renderProfiles();
        // Profiles left connected but not running (e.g. after an app update) come back.
        for (String id : store.activeProfiles()) {
            if (!running(id)) { sendToService(ForwardService.ENSURE, null); break; }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // Do not average traffic over the time the screen was closed.
        samples.clear();
        handler.removeCallbacks(statsTick);
        handler.post(statsTick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(statsTick);
        super.onPause();
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }

    private Button button(String name, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(name);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = text("WireGuard Forwarder", 23);
        heading.setTypeface(null, 1);
        root.addView(heading);
        root.addView(text("다른 VPN과 병행 가능 · VpnService 미사용 · 여러 프로필 동시 연결 · TCP / HTTP→HTTPS 중계", 12));

        root.addView(text("WireGuard 프로필", 18));
        summary = text("", 13);
        root.addView(summary);
        LinearLayout addActions = row();
        addActions.addView(button("QR 스캔", v -> scanConfig()), new LinearLayout.LayoutParams(0, dp(52), 1));
        addActions.addView(button(".conf 파일", v -> pickConfig()), new LinearLayout.LayoutParams(0, dp(52), 1));
        addActions.addView(button("직접 입력", v -> showConfigEditor(null, "")), new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(addActions);

        profilesView = column();
        root.addView(profilesView);

        LinearLayout allActions = row();
        allActions.addView(button("모두 연결", v -> connectAll()), new LinearLayout.LayoutParams(0, dp(52), 1));
        allActions.addView(button("모두 연결 종료", v -> sendToService(ForwardService.STOP_ALL, null)),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(allActions);
        root.addView(text("프로필마다 독립된 WireGuard 터널이 동시에 실행되며, 포트 포워딩 규칙도 프로필별로 등록합니다. "
                + "동시에 연결할 프로필끼리는 같은 로컬 IP:포트를 쓸 수 없으니 127.0.0.2처럼 다른 루프백 주소나 포트를 사용하세요. "
                + "'연결'은 로컬 포트가 열린 상태이며, 서버 연결 여부는 핸드셰이크 시각과 '연결 테스트'로 확인하세요.", 12));

        root.addView(text("실행 설정", 18));
        CheckBox autoStart = new CheckBox(this);
        autoStart.setText("부팅 후 자동 연결 (연결해 둔 프로필, 제조사 정책에 따라 제한 가능)");
        autoStart.setChecked(store.prefs().getBoolean("autoStart", false));
        autoStart.setOnCheckedChangeListener((v, enabled) ->
                store.prefs().edit().putBoolean("autoStart", enabled).apply());
        root.addView(autoStart);
        CheckBox autoReconnect = new CheckBox(this);
        autoReconnect.setText("네트워크 변경 시 재연결 (진행 중인 연결 유지)");
        autoReconnect.setChecked(store.prefs().getBoolean("autoReconnect", true));
        autoReconnect.setOnCheckedChangeListener((v, enabled) ->
                store.prefs().edit().putBoolean("autoReconnect", enabled).apply());
        root.addView(autoReconnect);
        root.addView(text("연결 중에는 화면을 닫아도 알림과 함께 백그라운드에서 동작합니다. "
                + "서버 주소 DNS 조회가 일시적으로 실패하면 연결을 끊지 않고 자동으로 다시 시도합니다. "
                + "장시간 사용하려면 Android 앱 정보에서 배터리 사용을 '제한 없음'으로 설정하세요. "
                + "기기 절전 정책에 따라 중단될 수 있습니다.", 12));
        root.addView(button("앱 정보 열기 (배터리 설정)", v -> {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
        }));

        root.addView(text("설정 백업 및 복원", 18));
        root.addView(text("WireGuard 개인키가 포함되므로 백업 파일을 암호화합니다. 암호를 잃어버리면 복원할 수 없습니다.", 12));
        LinearLayout backupActions = row();
        backupActions.addView(button("백업 내보내기", v -> startBackup()),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        backupActions.addView(button("백업 복원", v -> pickBackup()),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(backupActions);

        root.addView(text("실행 로그 (최대 64KB · 비밀키 기록 안 함)", 18));
        LinearLayout logActions = row();
        logActions.addView(button("새로고침", v -> showLogs()), new LinearLayout.LayoutParams(0, dp(50), 1));
        logActions.addView(button("복사", v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("WireGuard Forwarder logs", LogStore.read(this)));
            toast("로그가 복사되었습니다.");
        }), new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(logActions);
        logs = text("", 12);
        logs.setTextIsSelectable(true);
        root.addView(logs);
        root.addView(text("작동 방식", 18));
        root.addView(text("① WireGuard 프로필을 여러 개 등록하고 각각 연결합니다. "
                + "이 앱은 Android의 VPN 권한을 사용하지 않고 프로필마다 독립적인 터널을 생성하므로, "
                + "프로필끼리 터널 IP나 AllowedIPs가 겹쳐도 서로 영향을 주지 않습니다.", 12));
        root.addView(text("② 각 프로필의 포워딩 규칙에서 로컬 IP·포트와 원격 서버의 IP·포트를 지정합니다. "
                + "다른 앱은 규칙에 설정한 로컬 IP:포트로 접속하면 해당 프로필의 WireGuard로 전달됩니다. "
                + "설정한 포트의 TCP 연결만 전달하며 기기 전체 트래픽은 변경하지 않습니다.", 12));
        root.addView(text("③ TCP 모드는 데이터를 변경하지 않고 전달합니다. "
                + "HTTP→HTTPS 모드는 로컬 HTTP 요청을 원격 HTTPS 서버로 중계하고, "
                + "설정한 도메인으로 TLS 인증서를 검증합니다. "
                + "로컬 앱과 이 앱 사이의 HTTP 통신은 암호화되지 않습니다.", 12));
        root.addView(text("TCP 모드에서는 여러 HTTPS 도메인이 같은 서버 IP·포트를 사용해도 "
                + "요청의 TLS SNI를 변경하지 않고 전달합니다. 도메인을 각 규칙의 로컬 IP로 해석하는 DNS 설정은 "
                + "이 앱 외부에서 구성해야 하며, 로컬 443 포트 사용 가능 여부는 기기마다 다릅니다.", 12));
        root.addView(text("④ '연결'은 로컬 포워딩이 시작되었다는 뜻입니다. "
                + "'마지막 핸드셰이크'가 표시되면 WireGuard 서버와 실제로 통신한 것이며, "
                + "목적지 서비스 접속 가능 여부는 각 규칙의 '연결 테스트'로 확인하세요. "
                + "네트워크가 바뀌면 터널을 끊지 않고 서버 주소를 다시 조회해 이어서 사용합니다.", 12));
        showLogs();
    }

    // ----- profiles -----

    private void renderProfiles() {
        profilesView.removeAllViews();
        statusViews.clear();
        toggleButtons.clear();
        JSONArray list = store.profiles();
        JSONArray rules = store.rules();
        for (int p = 0; p < list.length(); p++) {
            JSONObject profile = list.optJSONObject(p);
            if (profile == null) continue;
            final String id = profile.optString("id");
            LinearLayout card = column();
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackgroundColor(0xFFE6ECF2);

            LinearLayout top = row();
            TextView name = text(profile.optString("name"), 17);
            name.setTypeface(null, 1);
            top.addView(name, weight());
            Button toggle = button("연결", v -> toggleProfile(id));
            top.addView(toggle);
            card.addView(top);
            TextView status = text("", 13);
            card.addView(status);
            statusViews.put(id, status);
            toggleButtons.put(id, toggle);

            LinearLayout actions = row();
            actions.addView(button("프로필 수정", v -> editProfile(id)), weight());
            actions.addView(button("삭제", v -> deleteProfile(id)), weight());
            actions.addView(button("+ 포트 추가", v -> editRule(-1, id)), weight());
            card.addView(actions);

            TextView rulesTitle = text("포트 포워딩", 14);
            rulesTitle.setTypeface(null, 1);
            card.addView(rulesTitle);
            int shown = 0;
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule == null || !id.equals(rule.optString("profileId"))) continue;
                card.addView(rulePanel(id, i, rule));
                card.addView(text("", 2));
                shown++;
            }
            if (shown == 0) card.addView(text("등록된 규칙이 없습니다. '+ 포트 추가'로 등록하세요.", 12));
            profilesView.addView(card);
            profilesView.addView(text("", 4));
        }
        if (list.length() == 0) {
            profilesView.addView(text("QR / .conf / 직접 입력으로 WireGuard 프로필을 등록하세요.", 13));
        }
        updateStats();
    }

    private View rulePanel(String profileId, int index, JSONObject rule) {
        LinearLayout panel = column();
        panel.setPadding(dp(10), dp(6), dp(10), dp(6));
        panel.setBackgroundColor(0xFFF7F9FB);
        LinearLayout top = row();
        top.addView(text(rule.optString("name") + (rule.optBoolean("enabled", true) ? "" : " (꺼짐)"), 15), weight());
        top.addView(button("수정", v -> editRule(index, profileId)));
        top.addView(button("삭제", v -> deleteRule(index, profileId)));
        panel.addView(top);
        final String localAddress = ProfileStore.localAddress(rule);
        final int localPort = rule.optInt("localPort");
        panel.addView(text(localAddress + ":" + localPort + " → "
                + rule.optString("targetHost") + ":" + rule.optInt("targetPort"), 13));
        boolean bridge = "http-https".equals(rule.optString("mode", "tcp"));
        List<String> domains = allowedDomains(rule);
        panel.addView(text(bridge ? "HTTP → HTTPS · 인증서 도메인: " + rule.optString("tlsHost")
                : "TCP 그대로 전달 · " + (domains.isEmpty() ? "모든 연결 전달"
                : "허용 도메인: " + String.join(", ", domains)), 12));
        panel.addView(button("연결 테스트", v -> testRoute(profileId, localAddress, localPort, bridge)));
        return panel;
    }

    /** Running, or connected by the user and waiting for a retry. */
    private boolean connected(String id) { return running(id) || store.isActive(id); }

    private void toggleProfile(String id) {
        if (connected(id)) {
            sendToService(ForwardService.STOP, id);
            return;
        }
        if (store.enabledRuleCount(id) == 0) {
            error("'" + store.profileName(id) + "'에 사용 중인 포트 포워딩 규칙이 없습니다. '+ 포트 추가'로 먼저 등록하세요.");
            return;
        }
        String conflict = listenerConflict(id);
        if (conflict != null) { error(conflict); return; }
        sendToService(ForwardService.START, id);
    }

    private void connectAll() {
        List<String> skipped = new ArrayList<>();
        for (String id : store.profileIds()) {
            if (connected(id)) continue;
            if (store.enabledRuleCount(id) == 0 || listenerConflict(id) != null) {
                skipped.add(store.profileName(id));
                continue;
            }
            sendToService(ForwardService.START, id);
        }
        if (!skipped.isEmpty()) {
            notice("다음 프로필은 연결하지 않았습니다 (규칙 없음 또는 다른 프로필과 같은 로컬 IP:포트 사용): "
                    + String.join(", ", skipped));
        }
    }

    /** Explains why profile id cannot run next to the connected profiles, or null. */
    private String listenerConflict(String id) {
        Set<String> active = store.activeProfiles();
        JSONArray rules = store.rules();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject mine = rules.optJSONObject(i);
            if (mine == null || !id.equals(mine.optString("profileId")) || !mine.optBoolean("enabled", true)) continue;
            for (int j = 0; j < rules.length(); j++) {
                JSONObject other = rules.optJSONObject(j);
                if (other == null || !other.optBoolean("enabled", true)) continue;
                String owner = other.optString("profileId");
                if (owner.equals(id) || !(active.contains(owner) || running(owner))) continue;
                if (other.optInt("localPort") == mine.optInt("localPort")
                        && ProfileStore.localAddress(other).equals(ProfileStore.localAddress(mine))) {
                    return "로컬 " + ProfileStore.localAddress(mine) + ":" + mine.optInt("localPort")
                            + "을(를) 연결 중인 프로필 '" + store.profileName(owner) + "'에서 사용 중입니다. "
                            + "한 프로필의 규칙에서 로컬 IP(예: 127.0.0.2)나 포트를 바꾸면 동시에 연결할 수 있습니다.";
                }
            }
        }
        return null;
    }

    private void sendToService(String action, String id) {
        Intent intent = new Intent(this, ForwardService.class).setAction(action);
        if (id != null) intent.putExtra(ForwardService.EXTRA_PROFILE, id);
        try {
            if (ForwardService.START.equals(action) || ForwardService.ENSURE.equals(action)) startForegroundService(intent);
            else startService(intent);
            if (id != null && statusViews.containsKey(id)) {
                statusViews.get(id).setText(ForwardService.START.equals(action) ? "연결 시작 중..." : "연결 종료 중...");
            }
        } catch (Exception e) { error("서비스 실행 실패: " + e.getClass().getSimpleName()); }
    }

    private void pickConfig() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, PICK_CONFIG);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_BACKUP) {
            char[] password = pendingBackupPassword;
            pendingBackupPassword = null;
            if (password == null) return;
            try {
                if (result != RESULT_OK || data == null || data.getData() == null) return;
                byte[] bytes = store.backup(password);
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (out == null) throw new IllegalStateException("백업 파일을 만들 수 없습니다.");
                    out.write(bytes);
                    out.flush();
                } finally { Arrays.fill(bytes, (byte) 0); }
                LogStore.add(this, "암호화 설정 백업 완료");
                toast("암호화 백업 파일을 저장했습니다.");
            } catch (Exception error) {
                error("백업 실패: " + error.getClass().getSimpleName());
            } finally { Arrays.fill(password, '\0'); }
            return;
        }
        if (request == OPEN_BACKUP) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new IllegalStateException("백업 파일을 열 수 없습니다.");
                byte[] bytes = readBackup(in);
                showRestoreDialog(bytes);
            } catch (Exception error) { error("백업 파일 읽기 실패: " + error.getClass().getSimpleName()); }
            return;
        }
        if (request != PICK_CONFIG || result != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalArgumentException("설정 파일을 열 수 없습니다.");
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > 65536) throw new IllegalArgumentException("설정 파일 크기는 64KB 이하만 지원합니다.");
                out.write(buf, 0, n);
            }
            String content = out.toString(StandardCharsets.UTF_8.name());
            showConfigEditor(null, content);
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void startBackup() {
        if (pendingBackupPassword != null) Arrays.fill(pendingBackupPassword, '\0');
        pendingBackupPassword = null;
        LinearLayout form = column();
        form.setPadding(dp(18), 0, dp(18), 0);
        EditText password = field(form, "백업 암호 (8자 이상)", "", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm = field(form, "백업 암호 확인", "", false);
        confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("암호화 설정 백업")
                .setView(form)
                .setNegativeButton("취소", null)
                .setPositiveButton("파일 선택", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
            char[] one = password.getText().toString().toCharArray();
            char[] two = confirm.getText().toString().toCharArray();
            try {
                if (one.length < 8 || !Arrays.equals(one, two)) {
                    error("암호는 8자 이상이며 확인 입력과 일치해야 합니다.");
                    return;
                }
                pendingBackupPassword = one.clone();
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                save.addCategory(Intent.CATEGORY_OPENABLE);
                save.setType("application/octet-stream");
                save.putExtra(Intent.EXTRA_TITLE, "WireGuardForwarder-backup.wgfb");
                startActivityForResult(save, SAVE_BACKUP);
                dialog.dismiss();
            } catch (Exception error) { error("파일 선택 실패: " + error.getClass().getSimpleName()); }
            finally { Arrays.fill(one, '\0'); Arrays.fill(two, '\0'); }
        }));
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private boolean anyConnected() {
        for (String id : store.profileIds()) if (connected(id)) return true;
        return runningStats().length() > 0;
    }

    private void pickBackup() {
        if (anyConnected()) { toast("설정을 복원하려면 모든 연결을 먼저 종료하세요."); return; }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, OPEN_BACKUP);
    }

    private static byte[] readBackup(InputStream in) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) {
                if (out.size() + length > BackupCodec.MAX_BACKUP_BYTES) {
                    throw new IllegalArgumentException("백업 파일이 2MB를 초과했습니다.");
                }
                out.write(buffer, 0, length);
            }
            return out.toByteArray();
        }
    }

    private void showRestoreDialog(byte[] backupData) {
        if (anyConnected()) { toast("설정을 복원하려면 모든 연결을 먼저 종료하세요."); return; }
        LinearLayout form = column();
        form.setPadding(dp(18), 0, dp(18), 0);
        form.addView(text("현재 프로필과 포워딩 규칙을 백업 내용으로 교체합니다. 먼저 현재 설정을 백업하세요.", 13));
        EditText password = field(form, "백업 암호", "", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("암호화 백업 복원")
                .setView(form)
                .setNegativeButton("취소", null)
                .setPositiveButton("교체 복원", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
            char[] pass = password.getText().toString().toCharArray();
            try {
                store.restore(backupData, pass);
                LogStore.add(this, "설정 백업 복원 완료");
                dialog.dismiss();
                buildUi();
                renderProfiles();
                toast("설정을 복원했습니다. 연결은 수동으로 시작하세요.");
            } catch (Exception error) {
                error("복원 실패: " + error.getMessage());
            } finally { Arrays.fill(pass, '\0'); }
        }));
        dialog.setOnDismissListener(v -> Arrays.fill(backupData, (byte) 0));
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void scanConfig() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(barcode -> {
                    String content = barcode.getRawValue();
                    if (content == null || content.trim().isEmpty()) {
                        error("QR 코드에 WireGuard 설정이 없습니다.");
                        return;
                    }
                    // Only plaintext WireGuard .conf QR payloads are supported.
                    showConfigEditor(null, content);
                })
                .addOnFailureListener(e -> error(
                        "QR 스캔을 시작할 수 없습니다. Google Play 서비스와 네트워크 연결을 확인하세요."));
    }

    private void editProfile(String id) {
        if (connected(id)) { toast("이 프로필의 연결을 종료한 후 수정하세요."); return; }
        try {
            showConfigEditor(id, store.configText(id));
        } catch (Exception e) { error("프로필을 읽지 못했습니다: " + e.getClass().getSimpleName()); }
    }

    // Shared editor for QR imports, .conf files, manual creation (editingId == null), and editing.
    private void showConfigEditor(String editingId, String config) {
        boolean editing = editingId != null;
        if (editing && connected(editingId)) { toast("이 프로필의 연결을 종료한 후 변경하세요."); return; }
        LinearLayout form = column();
        form.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText name = field(form, "프로필 이름", editing
                ? store.profileName(editingId) : "Home", false);
        EditText content = new EditText(this);
        content.setHint("[Interface]\nPrivateKey = ...\nAddress = ...\n\n[Peer]\nPublicKey = ...\nEndpoint = ...\nAllowedIPs = ...");
        content.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        content.setSingleLine(false);
        content.setMinLines(10);
        content.setMaxLines(18);
        content.setText(config);
        content.setSelection(content.length());
        form.addView(content);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "WireGuard 프로필 수정" : "WireGuard 프로필 등록")
                .setView(scroll)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        // The visible text contains a VPN private key; protect it before the dialog is shown.
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String label = name.getText().toString();
                    String raw = content.getText().toString();
                    if (editing) {
                        if (connected(editingId)) throw new IllegalStateException("이 프로필의 연결을 먼저 종료하세요.");
                        store.updateProfile(editingId, label, raw);
                    } else {
                        store.addProfile(label, raw);
                    }
                    LogStore.add(this, editing ? "WireGuard 프로필 수정 완료" : "WireGuard 프로필 등록 완료");
                    dialog.dismiss();
                    renderProfiles();
                    toast("프로필이 암호화되어 저장되었습니다.");
                } catch (Exception e) {
                    // Never print invalid configuration text, keys, or QR payloads.
                    error("설정을 저장할 수 없습니다: " + e.getMessage());
                }
            });
        });
        dialog.show();
    }

    private void deleteProfile(String id) {
        if (connected(id)) { toast("이 프로필의 연결을 종료한 후 삭제하세요."); return; }
        int ruleCount = store.rulesFor(id).length();
        new AlertDialog.Builder(this).setTitle("프로필 삭제")
                .setMessage("'" + store.profileName(id) + "'의 WireGuard 설정"
                        + (ruleCount > 0 ? "과 포트 포워딩 규칙 " + ruleCount + "개" : "") + "를 삭제합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    if (connected(id)) { toast("이 프로필의 연결을 종료한 후 삭제하세요."); return; }
                    store.deleteProfile(id);
                    renderProfiles();
                }).show();
    }

    // ----- forwarding rules -----

    private EditText field(LinearLayout parent, String hint, String value, boolean number) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(input);
        return input;
    }

    private void editRule(int index, String profileId) {
        if (connected(profileId)) { toast("규칙을 변경하려면 이 프로필의 연결을 종료하세요."); return; }
        JSONObject old = index >= 0 ? store.rules().optJSONObject(index) : null;
        LinearLayout form = column();
        form.setPadding(dp(18), 0, dp(18), 0);
        form.addView(text("프로필: " + store.profileName(profileId), 13));
        EditText name = field(form, "규칙 이름", old == null ? "" : old.optString("name"), false);
        EditText localAddress = field(form, "로컬 IP (예: 127.0.0.1 또는 127.0.0.2)",
                old == null ? "127.0.0.1" : old.optString("localAddress", "127.0.0.1"), false);
        form.addView(text("동시에 연결할 다른 프로필과 같은 포트를 쓰려면 프로필마다 다른 127.x.x.x 주소를 사용하세요. 외부 IP는 사용할 수 없습니다.", 12));
        EditText local = field(form, "로컬 포트", old == null ? "" : "" + old.optInt("localPort"), true);
        form.addView(text("443 포트는 기기 보안 정책에 따라 수신이 거부될 수 있습니다. "
                + "수신 실패 시 8443 같은 포트를 사용하세요.", 12));
        EditText host = field(form, "서버 IPv4", old == null ? "" : old.optString("targetHost"), false);
        EditText remote = field(form, "서버 포트", old == null ? "" : "" + old.optInt("targetPort"), true);
        Spinner mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"TCP 그대로 전달", "HTTP → HTTPS"}));
        form.addView(mode);
        EditText tlsHost = field(form, "HTTPS 인증서 도메인 (예: service.example.com)",
                old == null ? "" : old.optString("tlsHost"), false);
        TextView domainsHelp = text("허용 도메인 (선택): 한 줄에 하나씩 입력합니다. 이 도메인으로 접속한 연결만 "
                + "WireGuard로 전달하고 나머지는 끊습니다. 비워 두면 모든 연결을 전달합니다. "
                + "*.example.com은 하위 도메인만 포함하므로 example.com도 함께 적으세요.", 12);
        form.addView(domainsHelp);
        EditText domains = new EditText(this);
        domains.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        domains.setMinLines(2);
        domains.setMaxLines(8);
        domains.setHint("example.com\n*.example.com");
        domains.setText(old == null ? "" : String.join("\n", allowedDomains(old)));
        form.addView(domains);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                tlsHost.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                domainsHelp.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                domains.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        mode.setSelection(old != null && "http-https".equals(old.optString("mode")) ? 1 : 0);
        boolean bridgeMode = mode.getSelectedItemPosition() == 1;
        tlsHost.setVisibility(bridgeMode ? View.VISIBLE : View.GONE);
        domainsHelp.setVisibility(bridgeMode ? View.GONE : View.VISIBLE);
        domains.setVisibility(bridgeMode ? View.GONE : View.VISIBLE);
        CheckBox enabled = new CheckBox(this);
        enabled.setText("사용");
        enabled.setChecked(old == null || old.optBoolean("enabled", true));
        form.addView(enabled);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this).setTitle(index < 0 ? "포트 추가" : "포트 수정")
                .setView(scroll)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (dialog, which) -> {
                    try {
                        if (connected(profileId)) throw new IllegalStateException("이 프로필의 연결을 먼저 종료하세요.");
                        int lp = Integer.parseInt(local.getText().toString());
                        int rp = Integer.parseInt(remote.getText().toString());
                        String bindAddress = localAddress.getText().toString().trim();
                        if (bindAddress.isEmpty()) bindAddress = "127.0.0.1";
                        String ip = host.getText().toString().trim();
                        if (!ProfileStore.validLoopbackIPv4(bindAddress)) {
                            throw new IllegalArgumentException("로컬 IP는 127.0.0.1~127.255.255.255 범위의 IPv4 주소여야 합니다.");
                        }
                        if ((lp != 443 && (lp < 1024 || lp > 65535)) || rp < 1 || rp > 65535 || !validIpv4(ip)) {
                            throw new IllegalArgumentException("포트 또는 대상 IPv4 주소를 확인하세요.");
                        }
                        JSONArray rules = store.rules();
                        String sharedWith = null;
                        for (int i = 0; i < rules.length(); i++) {
                            JSONObject existing = rules.optJSONObject(i);
                            if (i == index || existing == null || !enabled.isChecked()
                                    || !existing.optBoolean("enabled", true)
                                    || existing.optInt("localPort") != lp
                                    || !ProfileStore.localAddress(existing).equals(bindAddress)) continue;
                            String owner = existing.optString("profileId");
                            if (owner.equals(profileId)) {
                                throw new IllegalArgumentException("이 프로필에 동일한 로컬 IP와 포트가 이미 등록되어 있습니다.");
                            }
                            sharedWith = store.profileName(owner);
                        }
                        String selectedMode = mode.getSelectedItemPosition() == 1 ? "http-https" : "tcp";
                        String domain = tlsHost.getText().toString().trim();
                        if ("http-https".equals(selectedMode) && !validTlsHost(domain)) {
                            throw new IllegalArgumentException("HTTPS 인증서 도메인을 확인하세요 (https:// 또는 포트 입력 금지).");
                        }
                        List<String> allowed = "tcp".equals(selectedMode)
                                ? DomainList.parse(domains.getText().toString()) : new ArrayList<>();
                        JSONObject updated = ProfileStore.rule(profileId, name.getText().toString().trim(),
                                bindAddress, lp, ip, rp, enabled.isChecked(), selectedMode, domain, allowed);
                        if (index < 0) rules.put(updated);
                        else rules.put(index, updated);
                        store.saveRules(rules);
                        renderProfiles();
                        LogStore.add(this, "포트 규칙 저장 (" + store.profileName(profileId) + "): "
                                + bindAddress + ":" + lp + " -> " + ip + ":" + rp
                                + (allowed.isEmpty() ? "" : " (허용 도메인 " + allowed.size() + "개)"));
                        if (sharedWith != null) {
                            notice("저장했습니다. 다만 프로필 '" + sharedWith + "'도 " + bindAddress + ":" + lp
                                    + "을(를) 사용하므로 두 프로필은 동시에 연결할 수 없습니다. "
                                    + "동시에 쓰려면 로컬 IP를 127.0.0.2처럼 다르게 지정하세요.");
                        }
                    } catch (Exception e) { error(e.getMessage()); }
                }).show();
    }

    private static List<String> allowedDomains(JSONObject rule) {
        List<String> out = new ArrayList<>();
        JSONArray array = rule.optJSONArray("allowedDomains");
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            String domain = array.optString(i, "").trim();
            if (!domain.isEmpty()) out.add(domain);
        }
        return out;
    }

    private static boolean validIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) return false;
                for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i))) return false;
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) return false;
            }
            return !host.startsWith("127.") && !host.equals("0.0.0.0")
                    && !host.startsWith("224.") && !host.startsWith("239.");
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean validTlsHost(String host) {
        return host.length() <= 253 && !host.matches("[0-9.]+") && host.matches("(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
    }

    private void deleteRule(int index, String profileId) {
        if (connected(profileId)) { toast("이 프로필의 연결을 종료한 후 규칙을 삭제하세요."); return; }
        new AlertDialog.Builder(this).setTitle("포트 규칙 삭제")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    JSONArray old = store.rules();
                    JSONArray updated = new JSONArray();
                    for (int i = 0; i < old.length(); i++) if (i != index) updated.put(old.opt(i));
                    store.saveRules(updated);
                    renderProfiles();
                }).show();
    }

    private void testRoute(String profileId, String localAddress, int port, boolean https) {
        if (!running(profileId)) { toast("먼저 이 프로필을 연결하세요."); return; }
        toast("연결 테스트 진행 중...");
        new Thread(() -> {
            String result;
            String where = store.profileName(profileId) + ", " + localAddress + ":" + port;
            try {
                Engine.probeTunnel(profileId, localAddress, port);
                result = (https ? "HTTPS TLS 인증서 검증 성공" : "TCP 연결 성공") + " (WireGuard 경유, " + where + ")";
            } catch (Exception e) {
                result = "연결 실패 (" + where + "): " + e.getMessage();
            }
            final String message = result;
            LogStore.add(this, message);
            runOnUiThread(() -> {
                showLogs();
                new AlertDialog.Builder(this).setTitle("연결 테스트 결과")
                        .setMessage(message).setPositiveButton("확인", null).show();
            });
        }, "connection-probe").start();
    }

    // ----- status -----

    private static JSONObject runningStats() {
        try { return new JSONObject(Engine.stats()); }
        catch (Exception | LinkageError e) { return new JSONObject(); }
    }

    private static boolean running(String id) {
        try { return Engine.isRunning(id); }
        catch (Exception | LinkageError e) { return false; }
    }

    private void updateStats() {
        JSONObject all;
        try { all = new JSONObject(Engine.stats()); }
        catch (Exception | LinkageError e) {
            summary.setText("네이티브 엔진을 로드할 수 없습니다.");
            samples.clear();
            return;
        }
        Set<String> active = store.activeProfiles();
        long now = SystemClock.elapsedRealtime();
        long wallSeconds = System.currentTimeMillis() / 1000;
        int up = 0;
        int waiting = 0;
        for (Map.Entry<String, TextView> entry : statusViews.entrySet()) {
            String id = entry.getKey();
            JSONObject s = all.optJSONObject(id);
            String error = store.profileError(id);
            String line;
            if (s != null) {
                up++;
                long tx = s.optLong("tx");
                long rx = s.optLong("rx");
                double upload = 0;
                double download = 0;
                long[] last = samples.get(id);
                if (last != null && now > last[0] && tx >= last[1] && rx >= last[2]) {
                    double intervalSeconds = (now - last[0]) / 1000.0;
                    upload = (tx - last[1]) / intervalSeconds;
                    download = (rx - last[2]) / intervalSeconds;
                }
                samples.put(id, new long[]{now, tx, rx});
                long handshake = s.optLong("handshake");
                String shake = handshake <= 0 ? "서버 핸드셰이크 대기 중"
                        : "마지막 핸드셰이크 " + ago(Math.max(0, wallSeconds - handshake))
                        + (wallSeconds - handshake > 180 ? " (유휴 상태면 정상)" : "");
                line = "● 연결됨 · " + shake
                        + "\n업로드 " + humanRate(upload) + " · 다운로드 " + humanRate(download)
                        + " · 활성 연결 " + s.optInt("active")
                        + (s.optString("endpoint").isEmpty() ? "" : "\n서버 " + s.optString("endpoint"))
                        + (s.optString("error").isEmpty() ? "" : "\n최근 연결 오류: " + s.optString("error"));
            } else if (active.contains(id)) {
                waiting++;
                samples.remove(id);
                line = "◌ 연결 시도 중" + (error.isEmpty() ? "" : "\n" + error);
            } else {
                samples.remove(id);
                line = error.isEmpty() ? "연결 안 됨" : "오류: " + error;
            }
            entry.getValue().setText(line);
            Button toggle = toggleButtons.get(id);
            if (toggle != null) toggle.setText(s != null || active.contains(id) ? "연결 종료" : "연결");
        }
        int total = statusViews.size();
        summary.setText(total == 0 ? "" : "프로필 " + total + "개 · 연결됨 " + up + "개"
                + (waiting > 0 ? " · 연결 시도 중 " + waiting + "개" : ""));
    }

    private static String ago(long seconds) {
        if (seconds < 60) return seconds + "초 전";
        if (seconds < 3600) return (seconds / 60) + "분 전";
        return (seconds / 3600) + "시간 전";
    }

    private static String humanRate(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB/s", bytesPerSecond / 1048576.0);
    }

    private void showLogs() { logs.setText(LogStore.read(this)); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void error(String message) { new AlertDialog.Builder(this).setTitle("오류").setMessage(message).setPositiveButton("확인", null).show(); }
    private void notice(String message) { new AlertDialog.Builder(this).setTitle("알림").setMessage(message).setPositiveButton("확인", null).show(); }
}
