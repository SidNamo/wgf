package dev.wgforwarder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import engine.Engine;

/**
 * Keeps the WireGuard profiles the user connected running, several at once.
 *
 * Each profile has its own single-thread "lane", so start/stop of one profile is
 * ordered while different profiles never wait for each other (a slow DNS lookup of
 * one server does not delay the others). Temporary failures (DNS not ready, e.g.
 * right after a network change or boot) are retried with backoff instead of
 * disconnecting the profile. A network change does not restart tunnels: the engine
 * re-resolves the server and reopens its UDP socket while forwarded TCP connections
 * stay open.
 */
public final class ForwardService extends Service {
    /** Connect the profile in {@link #EXTRA_PROFILE}. */
    static final String START = "dev.wgforwarder.START";
    /** Disconnect the profile in {@link #EXTRA_PROFILE}. */
    static final String STOP = "dev.wgforwarder.STOP";
    static final String STOP_ALL = "dev.wgforwarder.STOP_ALL";
    /** Bring back every profile the user left connected (boot, app start). */
    static final String ENSURE = "dev.wgforwarder.ENSURE";
    static final String EXTRA_PROFILE = "profileId";
    private static final String CHANNEL = "wireguard_forwarder";
    private static final int NOTIFICATION = 42;
    private static final long FIRST_RETRY_MS = 5_000;
    private static final long MAX_RETRY_MS = 5 * 60_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    // Lanes outlive a service instance, so a stop queued by a service that is being
    // destroyed still runs before a start requested from its successor.
    private static final Map<String, ExecutorService> lanes = new HashMap<>();
    /** The live service instance; only touched on the main thread. */
    private static ForwardService current;
    // The fields below are only used on the main thread.
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Runnable> retries = new HashMap<>();
    private ProfileStore store;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean observedFirstNetwork;
    private boolean pendingFirstNetwork;
    private boolean destroyed;
    private boolean foregroundFailed;
    private int lastStartId;
    private final Runnable networkChanged = this::onNetworkChanged;

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        store = new ProfileStore(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "WireGuard Forwarder",
                NotificationManager.IMPORTANCE_LOW));
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent != null ? intent.getAction() : null;
        String id = intent != null ? intent.getStringExtra(EXTRA_PROFILE) : null;
        // A service started with startForegroundService() must always call startForeground().
        promote("연결 준비 중");
        if (START.equals(action) && store.hasProfile(id)) {
            store.setActive(id, true);
            store.setProfileError(id, null);
            resetRetry(id);
            LogStore.add(this, "연결 요청: " + store.profileName(id));
            lane(id).execute(() -> start(id));
        } else if (STOP.equals(action) && id != null) {
            stopProfile(id);
            LogStore.add(this, "연결 종료: " + store.profileName(id));
        } else if (STOP_ALL.equals(action)) {
            Set<String> ids = new LinkedHashSet<>(store.activeProfiles());
            ids.addAll(runningIds());
            for (String each : ids) stopProfile(each);
            LogStore.add(this, "모든 연결 종료");
        } else {
            // ENSURE, or a sticky restart after the system killed the service.
            for (String each : store.activeProfiles()) lane(each).execute(() -> ensure(each));
        }
        refresh();
        return START_STICKY;
    }

    private void stopProfile(String id) {
        store.setActive(id, false);
        resetRetry(id);
        lane(id).execute(() -> {
            Engine.stopTunnel(id);
            onMain(ForwardService::refresh);
        });
    }

    /** One ordered worker per profile; its thread exits while idle. */
    private static ExecutorService lane(String id) {
        synchronized (lanes) {
            ExecutorService lane = lanes.get(id);
            if (lane == null) {
                ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>());
                pool.allowCoreThreadTimeOut(true);
                lane = pool;
                lanes.put(id, lane);
            }
            return lane;
        }
    }

    private interface ServiceAction { void run(ForwardService service); }

    /** Runs on the main thread against the live service, if there still is one. */
    private static void onMain(ServiceAction action) {
        main.post(() -> {
            ForwardService service = current;
            if (service != null && !service.destroyed) action.run(service);
        });
    }

    // ----- work on a profile's lane -----

    private void ensure(String id) {
        if (!store.isActive(id)) return;
        if (Engine.isRunning(id)) { onMain(ForwardService::refresh); return; }
        start(id);
    }

    private void start(String id) {
        if (!store.isActive(id)) return; // disconnected while this was queued
        String name = store.profileName(id);
        try {
            if (!store.hasProfile(id)) throw new IllegalStateException("삭제된 프로필입니다.");
            Engine.startTunnel(id, name, store.configText(id), store.rulesFor(id).toString());
            store.setProfileError(id, null);
            LogStore.add(this, "연결 시작: " + name + ", 로컬 포트 " + store.enabledRuleCount(id) + "개");
            onMain(service -> { service.attempts.remove(id); service.refresh(); });
        } catch (Exception e) {
            String message = message(e);
            if (Engine.retryable(message)) {
                onMain(service -> service.scheduleRetry(id, message));
            } else {
                store.setActive(id, false);
                store.setProfileError(id, message);
                LogStore.add(this, "연결 실패: " + name + " — " + message);
                onMain(ForwardService::refresh);
            }
        }
    }

    private void reconnect(String id) {
        if (!store.isActive(id)) return;
        if (!Engine.isRunning(id)) { start(id); return; } // was waiting for a retry: try now
        String name = store.profileName(id);
        try {
            Engine.reconnect(id);
            store.setProfileError(id, null);
            LogStore.add(this, "네트워크 변경: " + name + " 재연결 (기존 연결 유지)");
        } catch (Exception e) {
            String message = message(e);
            if (Engine.retryable(message)) {
                // The tunnel keeps the previous server address; the engine looks it up
                // again by itself if handshakes stop working.
                LogStore.add(this, "네트워크 변경: " + name + " 서버 DNS 조회 실패, 이전 주소 유지 — " + message);
            } else {
                LogStore.add(this, "네트워크 변경: " + name + " 재설정 실패, 터널 재시작 — " + message);
                start(id);
            }
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ----- retries (main thread) -----

    private void scheduleRetry(String id, String message) {
        if (destroyed || !store.isActive(id)) return;
        Integer previous = attempts.get(id);
        int attempt = previous == null ? 1 : previous + 1;
        attempts.put(id, attempt);
        long delay = Math.min(MAX_RETRY_MS, FIRST_RETRY_MS << Math.min(attempt - 1, 6));
        String note = "서버 DNS 조회 실패 · " + (delay / 1000) + "초 후 다시 시도 (" + attempt + "회째): " + message;
        store.setProfileError(id, note);
        LogStore.add(this, store.profileName(id) + ": " + note);
        cancelRetry(id);
        Runnable retry = () -> {
            retries.remove(id);
            if (!destroyed) lane(id).execute(() -> ensure(id));
        };
        retries.put(id, retry);
        main.postDelayed(retry, delay);
        refresh();
    }

    private void cancelRetry(String id) {
        Runnable pending = retries.remove(id);
        if (pending != null) main.removeCallbacks(pending);
    }

    private void resetRetry(String id) {
        cancelRetry(id);
        attempts.remove(id);
    }

    // ----- network changes -----

    private void onNetworkChanged() {
        if (destroyed) return;
        boolean first = pendingFirstNetwork;
        pendingFirstNetwork = false;
        boolean reconnectRunning = !first && store.prefs().getBoolean("autoReconnect", true);
        for (String id : store.activeProfiles()) {
            boolean waiting = retries.containsKey(id);
            if (!waiting && !reconnectRunning) continue;
            resetRetry(id); // a new network is the best moment to retry right away
            lane(id).execute(() -> reconnect(id));
        }
    }

    private void registerNetworkMonitor() {
        if (networkCallback != null) return;
        observedFirstNetwork = false;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                main.post(() -> {
                    // The first callback only reports the current network: running tunnels
                    // need nothing, but profiles waiting for DNS are retried.
                    if (!observedFirstNetwork) { observedFirstNetwork = true; pendingFirstNetwork = true; }
                    main.removeCallbacks(networkChanged);
                    main.postDelayed(networkChanged, 2000);
                });
            }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); }
        catch (Exception e) {
            networkCallback = null;
            LogStore.add(this, "네트워크 감시 실패: " + e.getClass().getSimpleName());
        }
    }

    private void unregisterNetworkMonitor() {
        main.removeCallbacks(networkChanged);
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) { }
            networkCallback = null;
        }
    }

    // ----- foreground notification -----

    /** Updates the notification, or stops the service when no profile should run. */
    private void refresh() {
        if (destroyed) return;
        Set<String> active = store.activeProfiles();
        if (active.isEmpty()) {
            unregisterNetworkMonitor();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(lastStartId); // ignored if a newer start command arrived
            return;
        }
        List<String> up = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (String id : active) (Engine.isRunning(id) ? up : waiting).add(store.profileName(id));
        StringBuilder text = new StringBuilder();
        if (!up.isEmpty()) text.append("연결됨 ").append(up.size()).append("개: ").append(String.join(", ", up));
        if (!waiting.isEmpty()) {
            if (text.length() > 0) text.append(" · ");
            text.append("연결 시도 중: ").append(String.join(", ", waiting));
        }
        promote(text.toString());
        registerNetworkMonitor();
    }

    private void promote(String state) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("WireGuard Forwarder")
                .setContentText(state)
                .setStyle(new Notification.BigTextStyle().bigText(state))
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent stop = new Intent(this, ForwardService.class).setAction(STOP_ALL);
        builder.addAction(new Notification.Action.Builder(null, "모두 연결 종료",
                PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION, notification);
            }
        } catch (RuntimeException e) {
            // Android 12+ can refuse a foreground start from the background (e.g. a sticky restart).
            if (!foregroundFailed) LogStore.add(this, "알림 서비스 시작이 OS에 의해 제한됨: " + e.getClass().getSimpleName());
            foregroundFailed = true;
        }
    }

    private static List<String> runningIds() {
        List<String> ids = new ArrayList<>();
        try {
            Iterator<String> keys = new JSONObject(Engine.stats()).keys();
            while (keys.hasNext()) ids.add(keys.next());
        } catch (Exception ignored) { }
        return ids;
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (current == this) current = null;
        unregisterNetworkMonitor();
        for (Runnable retry : retries.values()) main.removeCallbacks(retry);
        retries.clear();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
