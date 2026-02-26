package com.dev.echodrop.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import timber.log.Timber;

/**
 * Restarts the {@link EchoService} after the device reboots,
 * provided the user has not disabled background sharing.
 */
public class BootReceiver extends BroadcastReceiver {

        @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (EchoService.isBackgroundEnabled(context)) {
                Timber.i("Boot completed — starting EchoService");
                EchoService.startService(context);
            } else {
                Timber.i("Boot completed — background sharing disabled, skipping");
            }
        }
    }
}
