package com.nobrain.linux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (ProotManager.isRootfsExtracted(context)) {
                context.startForegroundService(new Intent(context, PocketService.class));
            }
        }
    }
}
