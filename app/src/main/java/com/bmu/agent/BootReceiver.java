package com.bmu.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
            // Redémarrer l'agent au boot
            Intent serviceIntent = new Intent(context, AgentService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}
