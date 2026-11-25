package dev.wgforwarder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        ProfileStore store = new ProfileStore(context);
        if (!store.prefs().getBoolean("autoStart", false) || store.selectedId().isEmpty()) return;
        Intent start = new Intent(context, ForwardService.class).setAction(ForwardService.START);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start);
            else context.startService(start);
            LogStore.add(context, "부팅 후 자동 연결 요청");
        } catch (RuntimeException e) {
            LogStore.add(context, "부팅 자동 시작이 OS에 의해 제한됨: " + e.getClass().getSimpleName());
        }
    }
}
