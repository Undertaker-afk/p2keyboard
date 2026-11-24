package app.p2keyboard.board;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.p2keyboard.core.BoardSession;
import app.p2keyboard.core.DeviceId;
import app.p2keyboard.core.NaclBox;

/** Phones paired with this board. They connect without a code until unpaired here. */
public final class PairedPhones implements BoardSession.TrustStore {

    public static final class Phone {
        public final String keyHex;
        public final String name;
        public final long pairedAt;

        Phone(String keyHex, String name, long pairedAt) {
            this.keyHex = keyHex;
            this.name = name;
            this.pairedAt = pairedAt;
        }

        public String deviceId() {
            return DeviceId.of(NaclBox.unhex(keyHex));
        }
    }

    private final SharedPreferences prefs;

    public PairedPhones(Context c) {
        prefs = c.getSharedPreferences(BoardLinkService.PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Phone> list() {
        List<Phone> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("phones", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Phone(o.getString("k"), o.optString("n", ""), o.optLong("t", 0)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void save(List<Phone> phones) {
        JSONArray a = new JSONArray();
        try {
            for (Phone p : phones) a.put(new JSONObject().put("k", p.keyHex).put("n", p.name).put("t", p.pairedAt));
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("phones", a.toString()).apply();
    }

    public synchronized Phone find(String keyHex) {
        for (Phone p : list()) if (p.keyHex.equals(keyHex)) return p;
        return null;
    }

    @Override
    public synchronized boolean isTrusted(byte[] phoneKey) {
        return find(NaclBox.hex(phoneKey)) != null;
    }

    @Override
    public synchronized void trust(byte[] phoneKey, String phoneName) {
        String hex = NaclBox.hex(phoneKey);
        List<Phone> phones = list();
        long pairedAt = System.currentTimeMillis();
        for (int i = 0; i < phones.size(); i++) {
            if (phones.get(i).keyHex.equals(hex)) {
                pairedAt = phones.get(i).pairedAt;
                phones.remove(i);
                break;
            }
        }
        phones.add(0, new Phone(hex, phoneName, pairedAt));
        save(phones);
    }

    public synchronized void remove(String keyHex) {
        List<Phone> phones = list();
        phones.removeIf(p -> p.keyHex.equals(keyHex));
        save(phones);
    }
}
