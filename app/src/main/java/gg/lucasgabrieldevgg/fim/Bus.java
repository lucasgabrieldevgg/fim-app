package gg.lucasgabrieldevgg.fim;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Barramento em memória entre o serviço de escuta e a activity.
 * App de um processo só — um singleton com listeners resolve sem binder.
 */
public class Bus {
    public enum Status { CONECTANDO, AO_VIVO, SEM_INTERNET, DESCONECTADO }

    public interface Listener {
        void onItem(FimItem item);

        void onStatus(Status s);

        void onRefresh();
    }

    private static final Bus I = new Bus();

    public static Bus get() {
        return I;
    }

    private final CopyOnWriteArrayList<Listener> ls = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** A activity marca presença para o serviço NÃO duplicar como notificação. */
    public boolean uiVisivel = false;
    /** Serviço de escuta rodando? Evita re-start sem fim quando o app é reaberto. */
    public boolean servicoAtivo = false;
    public Status status = Status.CONECTANDO;

    public void add(Listener l) {
        ls.add(l);
    }

    public void remove(Listener l) {
        ls.remove(l);
    }

    public void item(final FimItem it) {
        main.post(() -> {
            for (Listener l : ls) l.onItem(it);
        });
    }

    public void refresh() {
        main.post(() -> {
            for (Listener l : ls) l.onRefresh();
        });
    }

    public void status(Status s) {
        status = s;
        main.post(() -> {
            for (Listener l : ls) l.onStatus(s);
        });
    }
}
