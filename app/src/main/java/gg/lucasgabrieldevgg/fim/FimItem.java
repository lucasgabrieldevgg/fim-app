package gg.lucasgabrieldevgg.fim;

import org.json.JSONException;
import org.json.JSONObject;

/** Um aviso de "tarefa concluída" que chegou. */
public class FimItem {
    public final String id;
    public final String title;
    public final String message;
    public final long time; // epoch millis

    public FimItem(String id, String title, String message, long time) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.time = time;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("message", message);
        o.put("time", time);
        return o;
    }

    public static FimItem fromJson(JSONObject o) {
        return new FimItem(
                o.optString("id", ""),
                o.optString("title", ""),
                o.optString("message", ""),
                o.optLong("time", 0L));
    }
}
