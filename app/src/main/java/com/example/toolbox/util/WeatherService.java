package com.example.toolbox.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Calendar;
import java.util.Locale;

/**
 * 首页天气的数据获取与缓存服务。
 *
 * <p>调用方先通过 Android 定位能力取得真实设备坐标，再在后台线程调用 {@link #load(Location)}。
 * 本类使用系统 Geocoder 反查城市，并请求对应坐标的预报。</p>
 *
 * <p>读取顺序如下：</p>
 * <ol>
 *     <li>天气缓存未超过 30 分钟且缓存坐标与当前坐标相距不超过 3 公里时，直接使用缓存；</li>
 *     <li>否则使用 Geocoder 反查城市并请求当天预报；</li>
 *     <li>请求失败时仅允许回退到同一位置附近的旧缓存，避免显示其他城市的天气。</li>
 * </ol>
 */
public final class WeatherService {
    private static final String PREFS = "home_weather";
    private static final long WEATHER_TTL = 30 * 60 * 1000L;
    private static final float CACHE_DISTANCE_METERS = 3_000f;
    private static final int LOCATION_VERSION = 3;
    private final Context context;
    private final SharedPreferences preferences;

    public WeatherService(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public WeatherData load(Location deviceLocation) throws Exception {
        if (deviceLocation == null) throw new IllegalArgumentException("无法获取手机位置");
        double latitude = deviceLocation.getLatitude();
        double longitude = deviceLocation.getLongitude();
        String cached = preferences.getString("weather", "");
        long fetchedAt = preferences.getLong("fetched_at", 0);
        boolean sameLocation = isNearCachedLocation(latitude, longitude);
        if (!cached.isEmpty() && sameLocation
                && preferences.getInt("location_version", 0) == LOCATION_VERSION
                && System.currentTimeMillis() - fetchedAt < WEATHER_TTL) {
            return parseWeather(new JSONObject(cached), preferences.getString("city", "当前位置"));
        }

        String city = resolveCity(latitude, longitude,
                sameLocation ? preferences.getString("city", "") : "");
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
                        + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=1",
                latitude, longitude);
        try {
            JSONObject response = new JSONObject(get(url));
            WeatherData result = parseWeather(response, city);
            preferences.edit().putString("weather", response.toString()).putString("city", city)
                    .putLong("latitude", Double.doubleToRawLongBits(latitude))
                    .putLong("longitude", Double.doubleToRawLongBits(longitude))
                    .putInt("location_version", LOCATION_VERSION)
                    .putLong("fetched_at", System.currentTimeMillis()).apply();
            return result;
        } catch (Exception e) {
            if (!cached.isEmpty() && sameLocation) return parseWeather(new JSONObject(cached),
                    preferences.getString("city", city));
            throw e;
        }
    }

    private boolean isNearCachedLocation(double latitude, double longitude) {
        if (preferences.getInt("location_version", 0) != LOCATION_VERSION
                || !preferences.contains("latitude") || !preferences.contains("longitude")) {
            return false;
        }
        float[] distance = new float[1];
        Location.distanceBetween(latitude, longitude,
                Double.longBitsToDouble(preferences.getLong("latitude", 0)),
                Double.longBitsToDouble(preferences.getLong("longitude", 0)), distance);
        return distance[0] <= CACHE_DISTANCE_METERS;
    }

    private String resolveCity(double latitude, double longitude, String cachedCity) {
        if (!Geocoder.isPresent()) return fallbackCity(cachedCity);
        try {
            List<Address> addresses = new Geocoder(context, Locale.getDefault())
                    .getFromLocation(latitude, longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                String city = firstNonBlank(address.getLocality(), address.getSubAdminArea(),
                        address.getAdminArea(), address.getFeatureName());
                if (!city.isEmpty()) return city;
            }
        } catch (Exception ignored) { }
        return fallbackCity(cachedCity);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String fallbackCity(String cachedCity) {
        return cachedCity == null || cachedCity.trim().isEmpty() ? "当前位置" : cachedCity.trim();
    }

    private static WeatherData parseWeather(JSONObject json, String city) throws Exception {
        // Open-Meteo 的 daily 字段按天返回数组，本请求只取 forecast_days=1，因此读取索引 0。
        JSONObject current = json.getJSONObject("current");
        JSONObject daily = json.getJSONObject("daily");
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        JSONArray rain = daily.optJSONArray("precipitation_probability_max");
        int code = current.getInt("weather_code");
        return new WeatherData(city, current.getDouble("temperature_2m"),
                current.getDouble("apparent_temperature"), current.getInt("relative_humidity_2m"),
                current.getDouble("wind_speed_10m"), highs.getDouble(0), lows.getDouble(0),
                rain == null || rain.length() == 0 || rain.isNull(0) ? -1 : rain.getInt(0), code,
                condition(code));
    }

    private static String get(String address) throws Exception {
        // 限制连接/读取等待时间及响应体大小，避免首页后台任务无限占用唯一的串行执行线程。
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "ToolBox/1.0 Android");
        try {
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && result.length() < 262144) result.append(line);
            }
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String condition(int code) {
        if (code == 0) return "晴";
        if (code <= 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "有雾";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "有雨";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "有雪";
        if (code >= 95) return "雷暴";
        return "天气变化中";
    }

    public static int scene(int code) {
        // UI 只需要晴、云、降水三类背景；此处是视觉分组，不是完整气象语义映射。
        if ((code >= 51 && code <= 99)) return 2;
        if (code >= 2) return 1;
        return 0;
    }

    public static String dailyQuote() {
        String[] quotes = {
                "把复杂留给工具，把专注留给自己。",
                "清醒地选择，然后耐心地完成。",
                "今天的一小步，也在改变明天的路径。",
                "先完成，再让它变得更好。",
                "真正可靠的系统，来自每一次认真修正。",
                "保持好奇，也保持行动。",
                "让工具安静工作，让想法自由生长。"
        };
        // 以年内天数稳定轮换，同一天内进出首页不会随机改变文案。
        return quotes[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % quotes.length];
    }

    public static final class WeatherData {
        public final String city, condition; public final double temperature, apparent, wind, high, low;
        public final int humidity, rainProbability, code;
        WeatherData(String city, double temperature, double apparent, int humidity, double wind,
                    double high, double low, int rainProbability, int code, String condition) {
            this.city=city; this.temperature=temperature; this.apparent=apparent; this.humidity=humidity;
            this.wind=wind; this.high=high; this.low=low; this.rainProbability=rainProbability;
            this.code=code; this.condition=condition;
        }
    }
}
