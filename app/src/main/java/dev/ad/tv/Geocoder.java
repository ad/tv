package dev.ad.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Geocoder {
    private Geocoder() {}

    static List<City> search(String query) throws Exception {
        String lang = Locale.getDefault().getLanguage();
        String json = Http.get(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&addressdetails=1" +
                "&featureType=settlement&accept-language=" + lang + "&q=" + Http.enc(query)
        );
        JSONArray arr = new JSONArray(json);
        List<City> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String display = o.optString("display_name");
            String name = o.optString("name");
            out.add(new City(
                name.trim().isEmpty() ? display.split(",", 2)[0] : name,
                display,
                Double.parseDouble(o.getString("lat")),
                Double.parseDouble(o.getString("lon"))
            ));
        }
        return out;
    }
}
