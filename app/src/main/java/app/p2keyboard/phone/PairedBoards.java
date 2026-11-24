package app.p2keyboard.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.p2keyboard.core.NaclBox;
import app.p2keyboard.core.Pairing;

/** Boards this phone has paired with (the device list on the home screen). */
public final class PairedBoards {

    private final SharedPreferences prefs;

    public PairedBoards(Context c) {
        prefs = c.getSharedPreferences("phone", Context.MODE_PRIVATE);
    }

    /**
     * Saved boards, most recently used first. {@code secret} is only kept until the first
     * successful connection; after that the board knows this phone and no code is needed.
     */
    public synchronized List<Pairing> list() {
        List<Pairing> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("boards", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Pairing(NaclBox.unhex(o.getString("k")), o.getString("h"),
                        o.optString("s", ""), o.optString("n", "")));
            }
        } catch (JSONException | IllegalArgumentException ignored) {
        }
        return out;
    }

    private void save(List<Pairing> boards) {
        JSONArray a = new JSONArray();
        try {
            for (Pairing p : boards) {
                a.put(new JSONObject().put("k", NaclBox.hex(p.boardKey)).put("h", p.host)
                        .put("s", p.secret).put("n", p.name));
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("boards", a.toString()).apply();
    }

    public synchronized Pairing find(String keyHex) {
        for (Pairing p : list()) if (NaclBox.hex(p.boardKey).equals(keyHex)) return p;
        return null;
    }

    /** Adds or updates a board and moves it to the top of the list. */
    public synchronized void put(Pairing board) {
        List<Pairing> boards = list();
        String hex = NaclBox.hex(board.boardKey);
        boards.removeIf(p -> NaclBox.hex(p.boardKey).equals(hex));
        boards.add(0, board);
        save(boards);
    }

    /** After the board accepted us: forget the one-time secret, remember the board's name. */
    public synchronized void markPaired(String keyHex, String boardName) {
        Pairing p = find(keyHex);
        if (p == null) return;
        String name = boardName == null || boardName.isEmpty() ? p.name : boardName;
        put(new Pairing(p.boardKey, p.host, "", name));
    }

    public synchronized void remove(String keyHex) {
        List<Pairing> boards = list();
        boards.removeIf(p -> NaclBox.hex(p.boardKey).equals(keyHex));
        save(boards);
    }
}
