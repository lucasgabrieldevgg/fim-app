package gg.lucasgabrieldevgg.fim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Reinicia a escuta depois do boot, se o usuário já configurou um código. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (new Store(context).getCode() == null) return;
        Intent s = new Intent(context, ListenService.class);
        ContextCompat.startForegroundService(context, s);
    }
}
