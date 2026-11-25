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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import engine.Engine;

public final class ForwardService extends Service {
    static final String START = "dev.wgforwarder.START";
    static final String STOP = "dev.wgforwarder.STOP";
    static final String RESTART = "dev.wgforwarder.RESTART";
    private static final String CHANNEL = "wireguard_forwarder";
    private static final int NOTIFICATION = 42;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean observedFirstNetwork = false;
    private boolean starting = false;
    private final Runnable networkReconnect = () -> {
        if (new ProfileStore(this).prefs().getBoolean("autoReconnect", true)
                && new ProfileStore(this).prefs().getBoolean("desiredRunning", false)) {
            LogStore.add(this, "네트워크 변경 감지: 터널 재연결");
            worker.execute(this::startEngine);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "WireGuard Forwarder",
                NotificationManager.IMPORTANCE_LOW));
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (STOP.equals(action)) {
            new ProfileStore(this).prefs().edit().putBoolean("desiredRunning", false).apply();
            worker.execute(() -> {
                Engine.stop();
                LogStore.add(this, "연결 종료");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        if (START.equals(action) || RESTART.equals(action)
                || (action == null && new ProfileStore(this).prefs().getBoolean("desiredRunning", false))) {
            new ProfileStore(this).prefs().edit().putBoolean("desiredRunning", true).apply();
            promote("연결 준비 중");
            worker.execute(this::startEngine);
            return START_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void promote(String state) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("WireGuard Forwarder")
                .setContentText(state)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent stop = new Intent(this, ForwardService.class).setAction(STOP);
        builder.addAction(new Notification.Action.Builder(null, "연결 종료",
                PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION, notification);
        }
    }

    private void startEngine() {
        if (starting) return;
        starting = true;
        try {
            Engine.stop();
            ProfileStore store = new ProfileStore(this);
            Engine.start(store.configText(), store.rules().toString());
            store.prefs().edit().remove("lastError").apply();
            LogStore.add(this, "연결 시작: " + store.profileName(store.selectedId())
                    + ", 로컬 포트 " + store.rules().length() + "개 등록");
            main.post(() -> {
                promote("로컬 TCP 포워딩 활성화 · 서버 연결은 테스트 필요");
                registerNetworkMonitor();
            });
        } catch (Exception e) {
            Engine.stop();
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            new ProfileStore(this).prefs().edit().putBoolean("desiredRunning", false)
                    .putString("lastError", message).apply();
            LogStore.add(this, "연결 실패: " + message);
            main.post(() -> {
                unregisterNetworkMonitor();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        } finally {
            starting = false;
        }
    }

    private void registerNetworkMonitor() {
        if (networkCallback != null || Build.VERSION.SDK_INT < 24) return;
        observedFirstNetwork = false;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (!observedFirstNetwork) { observedFirstNetwork = true; return; }
                main.removeCallbacks(networkReconnect);
                main.postDelayed(networkReconnect, 2000);
            }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); }
        catch (Exception e) { LogStore.add(this, "네트워크 감시 실패: " + e.getClass().getSimpleName()); }
    }

    private void unregisterNetworkMonitor() {
        main.removeCallbacks(networkReconnect);
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) { }
            networkCallback = null;
        }
    }

    @Override public void onDestroy() {
        unregisterNetworkMonitor();
        worker.execute(Engine::stop);
        worker.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
