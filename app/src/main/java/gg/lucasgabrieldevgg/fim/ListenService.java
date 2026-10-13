package gg.lucasgabrieldevgg.fim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Serviço em primeiro plano que mantém o WebSocket do ntfy.sh aberto para o
 * código do usuário. Mesmo modelo do ntfy (modo instant, sem Google services):
 * FGS specialUse + wake lock parcial + reconexão com backoff.
 */
public class ListenService extends Service {
    private static final String CH_AVISOS = "fim-avisos";
    private static final String CH_STATUS = "fim-status";
    private static final int NOTIF_ID_STATUS = 1;
    private static final String NTFY = "https://ntfy.sh";

    private final Handler h = new Handler(Looper.getMainLooper());
    private OkHttpClient http;
    private WebSocket ws;
    private PowerManager.WakeLock wake;
    private ConnectivityManager.NetworkCallback netCb;
    private String code;
    private long backoffMs = 1000;
    private boolean parado = false;
    private boolean vivo = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        criaCanais();
        http = new OkHttpClient.Builder()
                .pingInterval(45, TimeUnit.SECONDS) // servidor manda keepalive; ping de segurança
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        // Mantém a CPU acordada para o WebSocket atravessar o doze (mesmo tradeoff do ntfy).
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fim:escuta");
        wake.setReferenceCounted(false);
        wake.acquire();
        registraRede();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        code = new Store(this).getCode();
        if (code == null || code.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundStatus();
        parado = false;
        Bus.get().servicoAtivo = true;
        conectar();
        return START_STICKY;
    }

    private void criaCanais() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel avisos = new NotificationChannel(
                CH_AVISOS, "Avisos de tarefa concluída", NotificationManager.IMPORTANCE_HIGH);
        avisos.setDescription("Avisos enviados pelo seu agente de IA via Fim!");
        avisos.enableVibration(true);
        nm.createNotificationChannel(avisos);
        NotificationChannel status = new NotificationChannel(
                CH_STATUS, "Escuta em segundo plano", NotificationManager.IMPORTANCE_MIN);
        status.setDescription("Mostra que o Fim! está escutando pelo seu código");
        nm.createNotificationChannel(status);
    }

    private void startForegroundStatus() {
        Intent abrir = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, abrir,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_fim)
                .setContentTitle("Fim! está escutando")
                .setContentText("Código: " + code)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID_STATUS, n);
        }
    }

    private void registraRede() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                h.post(() -> {
                    if (parado || vivo) return;
                    backoffMs = 1000;
                    Bus.get().status(Bus.Status.CONECTANDO);
                    conectar();
                });
            }
        };
        cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                netCb);
    }

    private boolean temInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return true; // não sabe? tenta assim mesmo
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private synchronized void conectar() {
        if (parado || code == null) return;
        if (ws != null) {
            ws.cancel();
            ws = null;
        }
        if (!temInternet()) {
            vivo = false;
            Bus.get().status(Bus.Status.SEM_INTERNET);
            return; // o NetworkCallback chama de volta quando a internet voltar
        }
        Bus.get().status(Bus.Status.CONECTANDO);
        Request req = new Request.Builder().url(NTFY + "/" + code + "/ws").build();
        ws = http.newWebSocket(req, listener);
    }

    private final WebSocketListener listener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            vivo = true;
            backoffMs = 1000;
            Bus.get().status(Bus.Status.AO_VIVO);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject o = new JSONObject(text);
                if (!"message".equals(o.optString("event"))) return;
                long seg = o.optLong("time", System.currentTimeMillis() / 1000);
                FimItem item = new FimItem(
                        o.optString("id", ""),
                        o.optString("title", ""),
                        o.optString("message", ""),
                        seg * 1000L);
                if (item.title.isEmpty()) item = new FimItem(item.id, "🏁 Fim!", item.message, item.time);
                if (!new Store(ListenService.this).add(item)) return;
                Bus.get().item(item);
                if (!Bus.get().uiVisivel) notificar(item);
            } catch (Exception ignored) {
                // frame estranho do stream: ignora e segue escutando
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            vivo = false;
            Bus.get().status(temInternet() ? Bus.Status.DESCONECTADO : Bus.Status.SEM_INTERNET);
            agendarReconexao();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            vivo = false;
            agendarReconexao();
        }
    };

    private void agendarReconexao() {
        if (parado) return;
        ws = null;
        h.postDelayed(this::conectar, backoffMs);
        if (backoffMs < 60000) backoffMs = Math.min(backoffMs * 2, 60000);
    }

    private void notificar(FimItem item) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        if (!nm.areNotificationsEnabled()) return; // sem permissão: não crasha, só não notifica
        Intent abrir = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, (int) (item.time % 100000), abrir,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String titulo = item.title.isEmpty() ? "🏁 Fim!" : item.title;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_AVISOS)
                .setSmallIcon(R.drawable.ic_stat_fim)
                .setContentTitle(titulo)
                .setContentText(item.message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(item.message))
                .setWhen(item.time)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE);
        nm.notify((int) (item.time % 1000000000), b.build());
    }

    @Override
    public void onDestroy() {
        parado = true;
        h.removeCallbacksAndMessages(null);
        if (ws != null) ws.cancel();
        if (netCb != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCb);
        }
        if (wake != null) wake.release();
        Bus.get().servicoAtivo = false;
        Bus.get().status(Bus.Status.DESCONECTADO);
        super.onDestroy();
    }
}
