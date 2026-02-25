package com.dev.echodrop.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the {@link EchoService} after the device reboots,
 * provided the user has not disabled background sharing.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (EchoService.isBackgroundEnabled(context)) {
                Log.i(TAG, "Boot completed — starting EchoService");
                EchoService.startService(context);
            } else {
                Log.i(TAG, "Boot completed — background sharing disabled, skipping");
            }
        }
    }
}
