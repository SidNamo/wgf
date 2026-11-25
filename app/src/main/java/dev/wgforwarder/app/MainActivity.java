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
import java.util.List;

import engine.Engine;

public final class MainActivity extends Activity {
    private static final int PICK_CONFIG = 110;
    private static final int OPEN_BACKUP = 111;
    private static final int SAVE_BACKUP = 112;
    private char[] pendingBackupPassword;
    private ProfileStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Spinner profiles;
    private TextView status;
    private TextView counters;
    private TextView logs;
    private LinearLayout routesView;
    private Button connectButton;
    private boolean renderingProfiles;
    private long lastSampleAt;
    private long lastTx;
    private long lastRx;

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
        renderRoutes();
    }

    @Override protected void onResume() {
        super.onResume();
        // Do not average traffic over the time the screen was closed.
        lastSampleAt = 0;
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

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = text("WireGuard Forwarder", 23);
        heading.setTypeface(null, 1);
        root.addView(heading);
        root.addView(text("다른 VPN과 병행 가능 · VpnService 미사용 · TCP / HTTP→HTTPS 중계", 12));

        root.addView(text("WireGuard 프로필", 18));
        profiles = new Spinner(this);
        root.addView(profiles);
        profiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (renderingProfiles || running()) return;
                Object tag = profiles.getTag();
                if (!(tag instanceof List)) return;
                @SuppressWarnings("unchecked") List<String> ids = (List<String>) tag;
                if (position >= 0 && position < ids.size()) store.select(ids.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        LinearLayout profileActions = row();
        profileActions.addView(button("QR 스캔", v -> scanConfig()),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        profileActions.addView(button(".conf 파일", v -> pickConfig()),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(profileActions);
        LinearLayout editorActions = row();
        editorActions.addView(button("직접 입력", v -> showConfigEditor(false, "")),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        editorActions.addView(button("프로필 수정", v -> editProfile()),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        editorActions.addView(button("삭제", v -> deleteProfile()),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(editorActions);

        root.addView(text("연결 상태", 18));
        status = text("연결 안 됨", 15);
        root.addView(status);
        counters = text("", 13);
        root.addView(counters);
        connectButton = button("연결", v -> toggleConnection());
        root.addView(connectButton);
        root.addView(text("'연결'은 로컬 포트가 열린 상태입니다. 실제 WireGuard 경로는 아래 '연결 테스트'로 확인하세요.", 12));

        LinearLayout titleRow = row();
        titleRow.addView(text("포트 포워딩", 18), new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(button("+ 추가", v -> editRule(-1)));
        root.addView(titleRow);
        routesView = column();
        root.addView(routesView);
        root.addView(text("TCP: 데이터 변경 없이 중계 / HTTP→HTTPS: 로컬 HTTP를 원격 HTTPS로 변환", 12));

        root.addView(text("실행 설정", 18));
        CheckBox autoStart = new CheckBox(this);
        autoStart.setText("부팅 후 자동 연결 (제조사 정책에 따라 제한 가능)");
        autoStart.setChecked(store.prefs().getBoolean("autoStart", false));
        autoStart.setOnCheckedChangeListener((v, enabled) ->
                store.prefs().edit().putBoolean("autoStart", enabled).apply());
        root.addView(autoStart);
        CheckBox autoReconnect = new CheckBox(this);
        autoReconnect.setText("네트워크 변경 시 재연결");
        autoReconnect.setChecked(store.prefs().getBoolean("autoReconnect", true));
        autoReconnect.setOnCheckedChangeListener((v, enabled) ->
                store.prefs().edit().putBoolean("autoReconnect", enabled).apply());
        root.addView(autoReconnect);
        root.addView(text("연결 중에는 화면을 닫아도 알림과 함께 백그라운드에서 동작합니다. "
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
        root.addView(text("① WireGuard 프로필을 등록한 후 연결합니다. "
                + "이 앱은 Android의 VPN 권한을 사용하지 않고 독립적인 터널을 생성합니다.", 12));
        root.addView(text("② 포워딩 규칙에서 로컬 IP·포트와 원격 서버의 IP·포트를 지정합니다. "
                + "다른 앱은 규칙에 설정한 로컬 IP:포트로 접속하면 됩니다. "
                + "설정한 포트의 TCP 연결만 WireGuard로 전달하며 기기 전체 트래픽은 변경하지 않습니다.", 12));
        root.addView(text("③ TCP 모드는 데이터를 변경하지 않고 전달합니다. "
                + "HTTP→HTTPS 모드는 로컬 HTTP 요청을 원격 HTTPS 서버로 중계하고, "
                + "설정한 도메인으로 TLS 인증서를 검증합니다. "
                + "로컬 앱과 이 앱 사이의 HTTP 통신은 암호화되지 않습니다.", 12));
        root.addView(text("TCP 모드에서는 여러 HTTPS 도메인이 같은 서버 IP·포트를 사용해도 "
                + "요청의 TLS SNI를 변경하지 않고 전달합니다. 도메인을 각 규칙의 로컬 IP로 해석하는 DNS 설정은 "
                + "이 앱 외부에서 구성해야 하며, 로컬 443 포트 사용 가능 여부는 기기마다 다릅니다.", 12));
        root.addView(text("④ '연결'은 로컬 포워딩이 시작되었다는 뜻입니다. "
                + "원격 서버에 실제로 접속 가능한지는 각 규칙의 '연결 테스트'로 확인하세요. "
                + "백그라운드에서 계속 사용하려면 연결을 유지하고, 중지하려면 '연결 종료'를 누르세요.", 12));
        showLogs();
    }

    private void renderProfiles() {
        renderingProfiles = true;
        JSONArray list = store.profiles();
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) continue;
            String id = p.optString("id");
            if (id.equals(store.selectedId())) selected = i;
            ids.add(id);
            names.add(p.optString("name"));
        }
        if (names.isEmpty()) names.add("QR / .conf / 직접 입력으로 등록하세요");
        profiles.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        profiles.setTag(ids);
        if (!ids.isEmpty()) profiles.setSelection(Math.min(selected, ids.size() - 1));
        renderingProfiles = false;
        updateStats();
    }

    private void pickConfig() {
        if (running()) { toast("연결을 종료한 후 프로필을 가져오세요."); return; }
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
            showConfigEditor(false, content);
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

    private void pickBackup() {
        if (running()) { toast("설정을 복원하려면 연결을 먼저 종료하세요."); return; }
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
        if (running()) { toast("설정을 복원하려면 연결을 먼저 종료하세요."); return; }
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
                renderRoutes();
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
        if (running()) { toast("연결을 종료한 후 프로필을 등록하세요."); return; }
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
                    showConfigEditor(false, content);
                })
                .addOnFailureListener(e -> error(
                        "QR 스캔을 시작할 수 없습니다. Google Play 서비스와 네트워크 연결을 확인하세요."));
    }

    private void editProfile() {
        if (running()) { toast("연결을 종료한 후 프로필을 수정하세요."); return; }
        if (store.selectedId().isEmpty()) { toast("수정할 프로필을 선택하세요."); return; }
        try {
            showConfigEditor(true, store.configText());
        } catch (Exception e) { error("프로필을 읽지 못했습니다: " + e.getClass().getSimpleName()); }
    }

    // Shared editor for QR imports, .conf files, manual creation, and editing.
    private void showConfigEditor(boolean editing, String config) {
        if (running()) { toast("연결을 종료한 후 프로필을 변경하세요."); return; }
        LinearLayout form = column();
        form.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText name = field(form, "프로필 이름", editing
                ? store.profileName(store.selectedId()) : "Home", false);
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
                    if (editing) store.updateSelected(label, raw);
                    else store.addProfile(label, raw);
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

    private void deleteProfile() {
        if (running()) { toast("연결을 종료한 후 프로필을 삭제하세요."); return; }
        if (store.selectedId().isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("프로필 삭제")
                .setMessage("저장된 WireGuard 설정을 삭제합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    store.deleteSelected();
                    renderProfiles();
                }).show();
    }

    private void renderRoutes() {
        routesView.removeAllViews();
        JSONArray rules = store.rules();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            final int index = i;
            LinearLayout panel = column();
            panel.setPadding(dp(12), dp(8), dp(12), dp(8));
            panel.setBackgroundColor(0xFFF2F4F7);
            LinearLayout top = row();
            top.addView(text(rule.optString("name") + (rule.optBoolean("enabled", true) ? "" : " (꺼짐)"), 15),
                    new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(button("수정", v -> editRule(index)));
            top.addView(button("삭제", v -> deleteRule(index)));
            panel.addView(top);
            String rawLocalAddress = rule.optString("localAddress", "127.0.0.1").trim();
            final String localAddress = rawLocalAddress.isEmpty() ? "127.0.0.1" : rawLocalAddress;
            panel.addView(text(localAddress + ":" + rule.optInt("localPort") + " → "
                    + rule.optString("targetHost") + ":" + rule.optInt("targetPort"), 13));
            boolean bridge = "http-https".equals(rule.optString("mode", "tcp"));
            panel.addView(text(bridge ? "HTTP → HTTPS · 인증서 도메인: " + rule.optString("tlsHost")
                    : "TCP 그대로 전달", 12));
            panel.addView(button("연결 테스트", v -> testRoute(localAddress, rule.optInt("localPort"))));
            routesView.addView(panel);
            TextView space = text("", 4);
            routesView.addView(space);
        }
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean number) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(input);
        return input;
    }

    private void editRule(int index) {
        if (running()) { toast("규칙을 변경하려면 연결을 종료하세요."); return; }
        JSONObject old = index >= 0 ? store.rules().optJSONObject(index) : null;
        LinearLayout form = column();
        form.setPadding(dp(18), 0, dp(18), 0);
        EditText name = field(form, "규칙 이름", old == null ? "" : old.optString("name"), false);
        EditText localAddress = field(form, "로컬 IP (예: 127.0.0.1 또는 127.0.0.2)",
                old == null ? "127.0.0.1" : old.optString("localAddress", "127.0.0.1"), false);
        form.addView(text("프로필마다 다른 127.x.x.x 주소를 사용하면 동일한 포트를 별도로 열 수 있습니다. 외부 IP는 사용할 수 없습니다.", 12));
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
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                tlsHost.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        mode.setSelection(old != null && "http-https".equals(old.optString("mode")) ? 1 : 0);
        tlsHost.setVisibility(mode.getSelectedItemPosition() == 1 ? View.VISIBLE : View.GONE);
        CheckBox enabled = new CheckBox(this);
        enabled.setText("사용");
        enabled.setChecked(old == null || old.optBoolean("enabled", true));
        form.addView(enabled);
        new AlertDialog.Builder(this).setTitle(index < 0 ? "포트 추가" : "포트 수정")
                .setView(form)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (dialog, which) -> {
                    try {
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
                        for (int i = 0; i < rules.length(); i++) {
                            JSONObject existing = rules.optJSONObject(i);
                            if (i != index && existing != null && enabled.isChecked()
                                    && existing.optBoolean("enabled", true)
                                    && existing.optInt("localPort") == lp
                                    && defaultLocalAddress(existing).equals(bindAddress)) {
                                throw new IllegalArgumentException("동일한 로컬 IP와 포트가 이미 등록되어 있습니다.");
                            }
                        }
                        String selectedMode = mode.getSelectedItemPosition() == 1 ? "http-https" : "tcp";
                        String domain = tlsHost.getText().toString().trim();
                        if ("http-https".equals(selectedMode) && !validTlsHost(domain)) {
                            throw new IllegalArgumentException("HTTPS 인증서 도메인을 확인하세요 (https:// 또는 포트 입력 금지).");
                        }
                        JSONObject updated = ProfileStore.rule(name.getText().toString().trim(),
                                bindAddress, lp, ip, rp, enabled.isChecked(), selectedMode, domain);
                        if (index < 0) rules.put(updated);
                        else rules.put(index, updated);
                        store.saveRules(rules);
                        renderRoutes();
                        LogStore.add(this, "포트 규칙 저장: " + bindAddress + ":" + lp + " -> " + ip + ":" + rp);
                    } catch (Exception e) { error(e.getMessage()); }
                }).show();
    }

    private static String defaultLocalAddress(JSONObject rule) {
        String address = rule.optString("localAddress", "127.0.0.1").trim();
        return address.isEmpty() ? "127.0.0.1" : address;
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

    private void deleteRule(int index) {
        if (running()) { toast("연결을 종료한 후 규칙을 삭제하세요."); return; }
        new AlertDialog.Builder(this).setTitle("포트 규칙 삭제")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    JSONArray old = store.rules();
                    JSONArray updated = new JSONArray();
                    for (int i = 0; i < old.length(); i++) if (i != index) updated.put(old.opt(i));
                    store.saveRules(updated);
                    renderRoutes();
                }).show();
    }

    private void toggleConnection() {
        if (!running() && store.selectedId().isEmpty()) { toast("WireGuard .conf부터 가져오세요."); return; }
        String action = running() || store.prefs().getBoolean("desiredRunning", false)
                ? ForwardService.STOP : ForwardService.START;
        Intent intent = new Intent(this, ForwardService.class).setAction(action);
        try {
            if (ForwardService.START.equals(action)) startForegroundService(intent);
            else startService(intent);
            status.setText(ForwardService.START.equals(action) ? "연결 시작 중..." : "연결 종료 중...");
        } catch (Exception e) { error("서비스 실행 실패: " + e.getClass().getSimpleName()); }
    }

    private void testRoute(String localAddress, int port) {
        if (!running()) { toast("먼저 연결을 시작하세요."); return; }
        status.setText("TCP 연결 테스트 진행 중...");
        new Thread(() -> {
            String result;
            try {
                Engine.probeAt(localAddress, port);
                boolean https = false;
                JSONArray rules = store.rules();
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject rule = rules.optJSONObject(i);
                    if (rule != null && rule.optInt("localPort") == port
                            && defaultLocalAddress(rule).equals(localAddress)) {
                        https = "http-https".equals(rule.optString("mode", "tcp"));
                        break;
                    }
                }
                result = (https ? "HTTPS TLS 인증서 검증 성공" : "TCP 연결 성공")
                        + " (WireGuard 경유, " + localAddress + ":" + port + ")";
            } catch (Exception e) {
                result = "TCP " + localAddress + ":" + port + " 연결 실패: " + e.getMessage();
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

    private boolean running() {
        try { return new JSONObject(Engine.stats()).optBoolean("running"); }
        catch (Exception | LinkageError e) { return false; }
    }

    private void updateStats() {
        try {
            JSONObject data = new JSONObject(Engine.stats());
            boolean active = data.optBoolean("running");
            String failure = store.prefs().getString("lastError", "");
            status.setText(active ? "포워딩 활성화 (테스트로 서버 연결 확인)"
                    : (failure.isEmpty() ? "연결 안 됨" : "오류: " + failure));
            if (active) {
                long now = SystemClock.elapsedRealtime();
                long tx = data.optLong("tx");
                long rx = data.optLong("rx");
                double upload = 0;
                double download = 0;
                if (lastSampleAt > 0 && now > lastSampleAt && tx >= lastTx && rx >= lastRx) {
                    double intervalSeconds = (now - lastSampleAt) / 1000.0;
                    upload = (tx - lastTx) / intervalSeconds;
                    download = (rx - lastRx) / intervalSeconds;
                }
                lastTx = tx;
                lastRx = rx;
                lastSampleAt = now;
                counters.setText("업로드 " + humanRate(upload)
                        + " · 다운로드 " + humanRate(download)
                        + " · 활성 연결 " + data.optInt("active")
                        + (data.optString("error").isEmpty() ? "" : "\n최근 연결 오류: " + data.optString("error")));
            } else {
                lastSampleAt = 0;
                lastTx = 0;
                lastRx = 0;
                counters.setText("");
            }
            connectButton.setText(active || store.prefs().getBoolean("desiredRunning", false) ? "연결 종료" : "연결");
            profiles.setEnabled(!active);
        } catch (Exception | LinkageError e) {
            status.setText("네이티브 엔진을 로드할 수 없습니다.");
            lastSampleAt = 0;
        }
    }

    private static String humanRate(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB/s", bytesPerSecond / 1048576.0);
    }

    private void showLogs() { logs.setText(LogStore.read(this)); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void error(String message) { new AlertDialog.Builder(this).setTitle("오류").setMessage(message).setPositiveButton("확인", null).show(); }
}
