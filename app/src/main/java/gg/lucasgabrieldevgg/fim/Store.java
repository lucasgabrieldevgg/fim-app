package gg.lucasgabrieldevgg.fim;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistência simples: código do usuário + últimos 100 avisos, tudo em
 * SharedPreferences. Banco de verdade seria exagero para este volume.
 */
public class Store {
    private static final String PREFS = "fim";
    private static final int CAP = 100;

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getCode() {
        return p.getString("code", null);
    }

    public void setCode(String code) {
        p.edit().putString("code", code).apply();
    }

    public boolean backfilled(String code) {
        return p.getBoolean("backfilled_" + code, false);
    }

    public void markBackfilled(String code) {
        p.edit().putBoolean("backfilled_" + code, true).apply();
    }

    public List<FimItem> getItems() {
        List<FimItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString("items", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(FimItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
            // JSON corrompido nunca deve travar o app; a lista só nasce vazia.
        }
        return out;
    }

    public void setItems(List<FimItem> items) {
        JSONArray arr = new JSONArray();
        for (FimItem it : items) {
            try {
                arr.put(it.toJson());
            } catch (Exception ignored) {
                // item isolado não pode derrubar a lista inteira
            }
        }
        p.edit().putString("items", arr.toString()).apply();
    }

    /** Insere aviso novo no topo (mais recente primeiro). Dedupe por id. */
    public boolean add(FimItem item) {
        List<FimItem> items = getItems();
        for (FimItem it : items) {
            if (it.id.equals(item.id)) return false;
        }
        items.add(0, item);
        trim(items);
        setItems(items);
        return true;
    }

    /**
     * Mescla o histórico do painel (backfill da 1ª conexão). Dedupe por id e por
     * título+mensagem — o mesmo aviso pode chegar pelo SSE do painel e pelo ntfy.
     */
    public int mergeBackfill(List<FimItem> novos) {
        List<FimItem> items = getItems();
        int added = 0;
        for (FimItem n : novos) {
            boolean dup = false;
            for (FimItem it : items) {
                if (it.id.equals(n.id)
                        || (it.title.equals(n.title) && it.message.equals(n.message))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                items.add(n);
                added++;
            }
        }
        if (added > 0) {
            Collections.sort(items, (a, b) -> Long.compare(b.time, a.time));
            trim(items);
            setItems(items);
        }
        return added;
    }

    public void clear() {
        setItems(new ArrayList<>());
    }

    private void trim(List<FimItem> items) {
        while (items.size() > CAP) items.remove(items.size() - 1);
    }
}
