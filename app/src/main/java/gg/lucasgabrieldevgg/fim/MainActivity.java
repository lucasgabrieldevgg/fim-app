package gg.lucasgabrieldevgg.fim;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Duas telas numa activity só: setup (colar código) e lista de avisos.
 * Design segue o Fim! web: dark, verde #16A34A, cards arredondados.
 */
public class MainActivity extends AppCompatActivity implements Bus.Listener {
    private static final String SITE = "https://fim-flax.vercel.app";
    private static final String COMO_FUNCIONA = SITE + "/#/como-funciona";
    private static final String CODIGO_RX = "[A-Za-z0-9_-]{4,64}";

    private Store store;
    private final OkHttpClient http = new OkHttpClient();

    private View telaSetup;
    private View telaPrincipal;
    private TextInputLayout campoLayout;
    private TextInputEditText campoCodigo;
    private TextView txtStatus;
    private TextView txtCodigo;
    private View listaVazia;
    private View dot;
    private RecyclerView lista;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);

        telaSetup = findViewById(R.id.tela_setup);
        telaPrincipal = findViewById(R.id.tela_principal);
        campoLayout = findViewById(R.id.campo_layout);
        campoCodigo = findViewById(R.id.campo_codigo);
        txtStatus = findViewById(R.id.txt_status);
        txtCodigo = findViewById(R.id.txt_codigo);
        listaVazia = findViewById(R.id.lista_vazia);
        dot = findViewById(R.id.dot);
        lista = findViewById(R.id.lista);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);

        lista.setLayoutManager(new LinearLayoutManager(this));
        lista.setAdapter(adapter);

        findViewById(R.id.btn_comecar).setOnClickListener(v -> comecar());
        findViewById(R.id.btn_bateria).setOnClickListener(v ->
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        findViewById(R.id.txt_como_pegar).setOnClickListener(v ->
                abrirUrl(COMO_FUNCIONA));
        TextView comoPegar = findViewById(R.id.txt_como_pegar);
        comoPegar.setPaintFlags(comoPegar.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        txtCodigo.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Código do Fim!", txtCodigo.getText()));
            Toast.makeText(this, R.string.codigo_copiado, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Bus.get().uiVisivel = true;
        Bus.get().add(this);
        atualizar();
    }

    @Override
    protected void onStop() {
        Bus.get().remove(this);
        Bus.get().uiVisivel = false;
        super.onStop();
    }

    private void comecar() {
        String cod = campoCodigo.getText() == null ? "" : campoCodigo.getText().toString().trim();
        if (!cod.matches(CODIGO_RX)) {
            campoLayout.setError(getString(R.string.erro_codigo));
            return;
        }
        campoLayout.setError(null);
        store.setCode(cod);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return; // continua no onRequestPermissionsResult
        }
        iniciar();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 1 && perms.length > 0 && Manifest.permission.POST_NOTIFICATIONS.equals(perms[0])) {
            if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.sem_permissao_notif, Toast.LENGTH_LONG).show();
            }
            iniciar();
        }
    }

    private void iniciar() {
        atualizar();
    }

    /** Puxa o histórico do painel uma vez por código, para não começar de lista vazia. */
    private void backfill() {
        final String cod = store.getCode();
        if (cod == null || store.backfilled(cod)) return;
        String enc;
        try {
            // URLEncoder.encode(String, Charset) só existe na API 33+; usamos a variante String.
            enc = URLEncoder.encode(cod, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            enc = cod;
        }
        String url = SITE + "/api/history?code=" + enc + "&limit=20";
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull java.io.IOException e) {
                // sem histórico (site fora do ar / self-host) — o app funciona do mesmo jeito
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response resp) throws java.io.IOException {
                List<FimItem> novos = new ArrayList<>();
                try {
                    JSONObject o = new JSONObject(resp.body() != null ? resp.body().string() : "{}");
                    JSONArray items = o.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject it = items.getJSONObject(i);
                            long ms;
                            try {
                                ms = Instant.parse(it.optString("createdAt")).toEpochMilli();
                            } catch (Exception ex) {
                                ms = System.currentTimeMillis();
                            }
                            String titulo = it.optString("title", "");
                            novos.add(new FimItem(
                                    "web-" + it.optString("id", String.valueOf(ms)),
                                    titulo.isEmpty() ? "🏁 Fim!" : titulo,
                                    it.optString("message", ""),
                                    ms));
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    resp.close();
                }
                final List<FimItem> finalNovos = novos;
                final boolean ok = resp.isSuccessful();
                runOnUiThread(() -> {
                    store.mergeBackfill(finalNovos);
                    // Só marca se o painel respondeu bem; senão tenta de novo na próxima abertura.
                    if (ok) store.markBackfilled(cod);
                    mostraLista();
                });
            }
        });
    }

    private void atualizar() {
        String cod = store.getCode();
        boolean tem = cod != null && !cod.isEmpty();
        telaSetup.setVisibility(tem ? View.GONE : View.VISIBLE);
        telaPrincipal.setVisibility(tem ? View.VISIBLE : View.GONE);
        if (tem) {
            txtCodigo.setText(cod);
            // Reabriu o app e o serviço não está de pé? Religa a escuta.
            if (!Bus.get().servicoAtivo) {
                ContextCompat.startForegroundService(this, new Intent(this, ListenService.class));
            }
            backfill();
            mostraLista();
            aplicarStatus(Bus.get().status);
        }
    }

    private void mostraLista() {
        adapter.setItens(store.getItems());
        listaVazia.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        lista.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void aplicarStatus(Bus.Status s) {
        int cor;
        String txt;
        switch (s) {
            case AO_VIVO:
                cor = ContextCompat.getColor(this, R.color.verde_claro);
                txt = getString(R.string.status_ao_vivo);
                break;
            case CONECTANDO:
                cor = ContextCompat.getColor(this, R.color.ambar);
                txt = getString(R.string.status_conectando);
                break;
            case SEM_INTERNET:
                cor = ContextCompat.getColor(this, R.color.vermelho);
                txt = getString(R.string.status_sem_internet);
                break;
            default:
                cor = ContextCompat.getColor(this, R.color.ambar);
                txt = getString(R.string.status_reconectando);
        }
        dot.setBackgroundTintList(ColorStateList.valueOf(cor));
        txtStatus.setText(txt);
    }

    private void abrirUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    // ----- Bus.Listener (callbacks já chegam na main thread) -----

    @Override
    public void onItem(FimItem item) {
        adapter.insereNoTopo(item);
        listaVazia.setVisibility(View.GONE);
        lista.setVisibility(View.VISIBLE);
    }

    @Override
    public void onStatus(Bus.Status s) {
        aplicarStatus(s);
    }

    @Override
    public void onRefresh() {
        mostraLista();
    }

    // ----- Menu -----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_limpar) {
            store.clear();
            mostraLista();
            Toast.makeText(this, R.string.historico_limpo, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.menu_trocar) {
            stopService(new Intent(this, ListenService.class));
            store.setCode(null);
            store.clear();
            adapter.setItens(new ArrayList<>());
            atualizar();
            return true;
        }
        if (id == R.id.menu_site) {
            abrirUrl(SITE);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ----- Adapter da lista -----

    private static class VH extends RecyclerView.ViewHolder {
        final TextView titulo;
        final TextView mensagem;
        final TextView tempo;

        VH(View v) {
            super(v);
            titulo = v.findViewById(R.id.txt_titulo);
            mensagem = v.findViewById(R.id.txt_mensagem);
            tempo = v.findViewById(R.id.txt_tempo);
        }
    }

    private class Adapter extends RecyclerView.Adapter<VH> {
        private final List<FimItem> itens = new ArrayList<>();

        void setItens(List<FimItem> novos) {
            itens.clear();
            itens.addAll(novos);
            notifyDataSetChanged();
        }

        void insereNoTopo(FimItem it) {
            for (FimItem existente : itens) {
                if (existente.id.equals(it.id)) return;
            }
            itens.add(0, it);
            notifyItemInserted(0);
        }

        @Override
        public int getItemCount() {
            return itens.size();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_aviso, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            FimItem it = itens.get(pos);
            h.titulo.setText(it.title.isEmpty() ? "🏁 Fim!" : it.title);
            if (it.message.isEmpty()) {
                h.mensagem.setVisibility(View.GONE);
            } else {
                h.mensagem.setVisibility(View.VISIBLE);
                h.mensagem.setText(it.message);
            }
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    it.time,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            h.tempo.setText(rel);
        }
    }
}
