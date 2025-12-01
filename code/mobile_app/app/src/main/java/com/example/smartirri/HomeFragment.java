package com.example.smartirri;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

// Google Location
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import com.example.smartirri.GeoResult;
import com.example.smartirri.WeatherResponse;
import com.example.smartirri.WeatherApiService;

public class HomeFragment extends Fragment {

    // ================== CONFIG ==================
    // ThingSpeak
    private static final String THINGSPEAK_CHANNEL_ID = "3064466";
    private static final String THINGSPEAK_READ_API_KEY = "E7ZHRK9KHNZDS65L";
    private static final String THINGSPEAK_WRITE_API_KEY = "YG054O3BH6N2936R";
    private static final String THINGSPEAK_READ_URL =
            "https://api.thingspeak.com/channels/" + THINGSPEAK_CHANNEL_ID + "/feeds/last.json?api_key=" + THINGSPEAK_READ_API_KEY + "&results=1";
    private static final String THINGSPEAK_WRITE_URL_BASE =
            "https://api.thingspeak.com/update?api_key=" + THINGSPEAK_WRITE_API_KEY;

    // OpenWeather
    private static final String WEATHER_BASE_URL = "https://api.openweathermap.org/"; // root + slash
    private static final String WEATHER_API_KEY = "2038d94718601e310000ab4aa3aaa150"; // <-- put your valid key
    private static final String UNITS = "metric";

    // ================== UI ==================
    // Weather UI
    private EditText editTextCity;
    private ImageButton btnSearchWeather, btnRefresh;
    private TextView textWeatherLocation, textCurrentTemp, textWeatherDescription, textWindHumiditySummary;

    // Sensor UI
    private TextView textHumiditySensor, textTemperatureSensor, textMoistureSensor, textRainStatus, textTankLevel;
    private Button btnPumpToggle;
    private TextView textSystemMode, textSystemStatus;

    // ================== Infra ==================
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable thingSpeakRunnable;

    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "SmartIrriPrefs";
    private static final String KEY_LAST_CITY = "LastSearchedCity";

    private static final int THINGSPEAK_UPDATE_INTERVAL = 20000; // 20s
    private static final int THINGSPEAK_UPLOAD_INTERVAL = 30000; // 30s

    private boolean isPumpOn = false;
    private boolean isManualMode = false;

    // ================== Location ==================
    private FusedLocationProviderClient fusedClient;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private CancellationTokenSource currentLocCts;

    private double lastLat = Double.NaN, lastLon = Double.NaN;

    private ActivityResultLauncher<String[]> locationPermsLauncher;

    public HomeFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);

        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initUI(v);
        wireListeners();
        initLocation();

        // Start ThingSpeak polling
        startFetchingThingSpeakData();

        // Start fast location → weather
        ensureLocationPermissionAndStart();

        return v;
    }

    // ---------- UI ----------
    private void initUI(View v) {
        editTextCity = v.findViewById(R.id.edit_text_city);
        btnSearchWeather = v.findViewById(R.id.btn_search_weather);
        btnRefresh = v.findViewById(R.id.btn_refresh);

        textWeatherLocation = v.findViewById(R.id.text_weather_location);
        textCurrentTemp = v.findViewById(R.id.text_current_temp);
        textWeatherDescription = v.findViewById(R.id.text_weather_description);
        textWindHumiditySummary = v.findViewById(R.id.text_wind_humidity_summary);

        textHumiditySensor = v.findViewById(R.id.text_humidity);
        textTemperatureSensor = v.findViewById(R.id.text_temperature);
        textMoistureSensor = v.findViewById(R.id.text_moisture);
        textRainStatus = v.findViewById(R.id.text_rain_status);
        textTankLevel = v.findViewById(R.id.text_tank_level);

        textSystemStatus = v.findViewById(R.id.text_system_status);
        textSystemMode = v.findViewById(R.id.text_system_mode);
        btnPumpToggle = v.findViewById(R.id.btn_pump_toggle);
    }

    private void wireListeners() {
        btnSearchWeather.setOnClickListener(v -> manualCitySearch());

        editTextCity.setOnEditorActionListener((v, actionId, event) -> {
            boolean imeSearch = (actionId == EditorInfo.IME_ACTION_SEARCH);
            boolean enterPressed = (event != null &&
                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN);
            if (imeSearch || enterPressed) {
                manualCitySearch();
                return true;
            }
            return false;
        });

        btnRefresh.setOnClickListener(v -> {
            Toast.makeText(getContext(), "Refreshing…", Toast.LENGTH_SHORT).show();
            fetchThingSpeakData();
            if (!Double.isNaN(lastLat) && !Double.isNaN(lastLon)) {
                fetchWeatherByCoord(lastLat, lastLon);
            } else {
                String city = loadLastCity();
                if (!TextUtils.isEmpty(city)) fetchWeatherByCity(city);
            }
        });

        btnPumpToggle.setOnClickListener(v -> {
            if (!isManualMode) {
                Toast.makeText(getContext(), "Switch to MANUAL mode to control pump", Toast.LENGTH_SHORT).show();
                return;
            }
            isPumpOn = !isPumpOn;
            sendControlCommand(isPumpOn, isManualMode);
            btnPumpToggle.setText(isPumpOn ? "PUMP: ON" : "PUMP: OFF");
        });

        textSystemMode.setOnClickListener(v -> {
            isManualMode = !isManualMode;
            sendControlCommand(isPumpOn, isManualMode);
            textSystemMode.setText(isManualMode ? "MODE: MANUAL" : "MODE: AUTO");
            Toast.makeText(getContext(), isManualMode ?
                    "Manual mode enabled. Pump control unlocked." :
                    "Auto mode enabled. System is automatic.", Toast.LENGTH_SHORT).show();
        });
    }

    private void manualCitySearch() {
        String city = editTextCity.getText().toString().trim();
        if (TextUtils.isEmpty(city)) {
            Toast.makeText(getContext(), "Type a city or allow location", Toast.LENGTH_SHORT).show();
            return;
        }
        textWeatherDescription.setText("Searching…");
        saveLastCity(city);
        fetchWeatherByCity(city);

        // Hide keyboard
        View focus = requireActivity().getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    // ---------- Prefs ----------
    private void saveLastCity(String city) {
        sharedPreferences.edit().putString(KEY_LAST_CITY, city).apply();
    }

    private String loadLastCity() {
        return sharedPreferences.getString(KEY_LAST_CITY, "Bengaluru,IN");
    }

    // ================== LOCATION ==================
    private void initLocation() {
        fusedClient = LocationServices.getFusedLocationProviderClient(requireContext());

        // Balanced defaults (fast + battery friendly)
        locationRequest = new LocationRequest.Builder(5 * 60 * 1000)  // 5 minutes
                .setMinUpdateIntervalMillis(60 * 1000)                // at most 1/min
                .setMinUpdateDistanceMeters(100)                      // or 100m move
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                lastLat = loc.getLatitude();
                lastLon = loc.getLongitude();
                Log.d("LOC", "update: lat=" + lastLat + " lon=" + lastLon);
                fetchWeatherByCoord(lastLat, lastLon);
            }
        };

        locationPermsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grants -> {
                    boolean fine = Boolean.TRUE.equals(grants.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false));
                    boolean coarse = Boolean.TRUE.equals(grants.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false));
                    if (fine || coarse) {
                        requestFastLocationThenStartUpdates();
                    } else {
                        Toast.makeText(getContext(), "Location permission denied", Toast.LENGTH_SHORT).show();
                        fetchWeatherByCity(loadLastCity());
                    }
                });
    }

    private void ensureLocationPermissionAndStart() {
        boolean fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            requestFastLocationThenStartUpdates();
        } else {
            locationPermsLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    // Fast strategy: last-known → quick current (balanced) → 5s timeout → one high-accuracy shot → start updates
    private void requestFastLocationThenStartUpdates() {
        if (textWeatherDescription != null) textWeatherDescription.setText("Getting location…");

        // 1) Last known (instant)
        try {
            fusedClient.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) {
                    lastLat = loc.getLatitude();
                    lastLon = loc.getLongitude();
                    textWeatherDescription.setText("Using last known location");
                    fetchWeatherByCoord(lastLat, lastLon);
                } else {
                    textWeatherDescription.setText("Getting current location…");
                }
            });
        } catch (SecurityException ignored) {}

        // 2) One-shot current (balanced) with ~5s timeout
        try {
            cancelCurrentLoc();
            currentLocCts = new CancellationTokenSource();

            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, currentLocCts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            lastLat = loc.getLatitude();
                            lastLon = loc.getLongitude();
                            textWeatherDescription.setText("Got location");
                            fetchWeatherByCoord(lastLat, lastLon);
                        }
                    })
                    .addOnFailureListener(e -> Log.w("LOC", "getCurrentLocation(balanced) failed: " + e.getMessage()));

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (Double.isNaN(lastLat) || Double.isNaN(lastLon)) {
                    tryHighAccuracyOneShot();
                }
            }, 5000);
        } catch (SecurityException ignored) {}

        // 3) Continuous updates
        startLocationUpdates();
    }

    private void tryHighAccuracyOneShot() {
        if (!isAdded()) return;
        textWeatherDescription.setText("Improving location accuracy…");

        try {
            cancelCurrentLoc();
            currentLocCts = new CancellationTokenSource();

            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, currentLocCts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            lastLat = loc.getLatitude();
                            lastLon = loc.getLongitude();
                            fetchWeatherByCoord(lastLat, lastLon);
                        } else {
                            // Fallback: saved city
                            fetchWeatherByCity(loadLastCity());
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w("LOC", "HA one-shot failed: " + e.getMessage());
                        fetchWeatherByCity(loadLastCity());
                    });
        } catch (SecurityException ignored) {}
    }

    private void startLocationUpdates() {
        try {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException se) {
            Log.e("LOC", "No permission: " + se.getMessage());
        }
    }

    private void stopLocationUpdates() {
        fusedClient.removeLocationUpdates(locationCallback);
        cancelCurrentLoc();
    }

    private void cancelCurrentLoc() {
        if (currentLocCts != null) {
            currentLocCts.cancel();
            currentLocCts = null;
        }
    }

    @Override public void onStart() { super.onStart(); ensureLocationPermissionAndStart(); }
    @Override public void onStop()  { super.onStop();  stopLocationUpdates(); }

    // ================== WEATHER ==================
    private Retrofit buildRetrofit() {
        HttpLoggingInterceptor logger = new HttpLoggingInterceptor(message -> Log.d("OKHTTP", message));
        logger.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(logger).build();

        return new Retrofit.Builder()
                .baseUrl(WEATHER_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
    }

    private void fetchWeatherByCoord(double lat, double lon) {
        if ("YOUR_OPENWEATHERMAP_API_KEY".equals(WEATHER_API_KEY)) {
            textWeatherDescription.setText("Add Weather API Key");
            return;
        }
        WeatherApiService api = buildRetrofit().create(WeatherApiService.class);
        api.getCurrentWeatherByCoord(lat, lon, WEATHER_API_KEY, UNITS)
                .enqueue(new Callback<WeatherResponse>() {
                    @Override public void onResponse(@NonNull Call<WeatherResponse> call, @NonNull Response<WeatherResponse> res) {
                        if (res.isSuccessful() && res.body() != null) {
                            updateWeatherUI(res.body());
                        } else {
                            textWeatherDescription.setText("API Error (" + res.code() + ")");
                        }
                    }
                    @Override public void onFailure(@NonNull Call<WeatherResponse> call, @NonNull Throwable t) {
                        textWeatherDescription.setText("Network Failed");
                    }
                });
    }

    private void fetchWeatherByCity(String city) {
        if ("YOUR_OPENWEATHERMAP_API_KEY".equals(WEATHER_API_KEY)) {
            textWeatherDescription.setText("Add Weather API Key");
            return;
        }
        final String cityParam = city.replaceAll("\\s*,\\s*", ",").trim();

        WeatherApiService api = buildRetrofit().create(WeatherApiService.class);
        api.geocode(cityParam, 1, WEATHER_API_KEY).enqueue(new Callback<List<GeoResult>>() {
            @Override public void onResponse(@NonNull Call<List<GeoResult>> call, @NonNull Response<List<GeoResult>> resp) {
                if (resp.isSuccessful() && resp.body() != null && !resp.body().isEmpty()) {
                    GeoResult g = resp.body().get(0);
                    lastLat = g.getLat();
                    lastLon = g.getLon();
                    fetchWeatherByCoord(lastLat, lastLon);
                } else {
                    // Try a couple fallbacks quickly
                    List<String> candidates = new ArrayList<>();
                    candidates.add(cityParam);
                    if (cityParam.endsWith(",IN") && !cityParam.toLowerCase(Locale.ROOT).contains("karnataka")) {
                        candidates.add(cityParam.replace(",IN", ",Karnataka,IN"));
                    }
                    String cityOnly = cityParam.contains(",") ? cityParam.split(",")[0] : cityParam;
                    if (!candidates.contains(cityOnly)) candidates.add(cityOnly);
                    if (!candidates.contains("Bengaluru,IN")) candidates.add("Bengaluru,IN");
                    tryNextCandidate(api, candidates, 0);
                }
            }
            @Override public void onFailure(@NonNull Call<List<GeoResult>> call, @NonNull Throwable t) {
                textWeatherDescription.setText("Network Failed");
            }
        });
    }

    private void tryNextCandidate(WeatherApiService api, List<String> candidates, int idx) {
        if (idx >= candidates.size()) {
            textWeatherDescription.setText("Place not found");
            return;
        }
        String q = candidates.get(idx);
        api.geocode(q, 1, WEATHER_API_KEY).enqueue(new Callback<List<GeoResult>>() {
            @Override public void onResponse(@NonNull Call<List<GeoResult>> call, @NonNull Response<List<GeoResult>> resp) {
                if (resp.isSuccessful() && resp.body() != null && !resp.body().isEmpty()) {
                    GeoResult g = resp.body().get(0);
                    lastLat = g.getLat();
                    lastLon = g.getLon();
                    fetchWeatherByCoord(lastLat, lastLon);
                } else {
                    tryNextCandidate(api, candidates, idx + 1);
                }
            }
            @Override public void onFailure(@NonNull Call<List<GeoResult>> call, @NonNull Throwable t) {
                textWeatherDescription.setText("Network Failed");
            }
        });
    }

    private void updateWeatherUI(WeatherResponse wr) {
        try {
            if (wr == null || wr.getMain() == null || wr.getWind() == null
                    || wr.getWeather() == null || wr.getWeather().isEmpty()) {
                textWeatherDescription.setText("No weather data");
                return;
            }
            DecimalFormat df = new DecimalFormat("#");
            double temp = wr.getMain().getTemp();
            String desc = wr.getWeather().get(0).getDescription();
            if (desc == null || desc.isEmpty()) desc = "—";
            else desc = Character.toUpperCase(desc.charAt(0)) + desc.substring(1);

            textCurrentTemp.setText(String.format(Locale.getDefault(), "%s°C", df.format(temp)));
            textWeatherLocation.setText(wr.getName());
            textWeatherDescription.setText(desc);

            double windSpeed = wr.getWind().getSpeed();
            int humidity = wr.getMain().getHumidity();
            String summary = String.format(Locale.getDefault(), "Wind: %s m/s | Hum: %d%%", df.format(windSpeed), humidity);
            textWindHumiditySummary.setText(summary);

        } catch (Exception e) {
            Log.e("WEATHER_API", "Parse error: " + e.getMessage());
            textWeatherDescription.setText("Parse Error");
        }
    }

    // ================== THINGSPEAK ==================
    private void startFetchingThingSpeakData() {
        thingSpeakRunnable = new Runnable() {
            @Override public void run() {
                if (!isAdded()) return;
                fetchThingSpeakData();
                mainHandler.postDelayed(this, THINGSPEAK_UPDATE_INTERVAL);
            }
        };
        mainHandler.post(thingSpeakRunnable);
    }

    private void setSystemStatus(String status, int colorResId) {
        if (!isAdded()) return;
        textSystemStatus.setText(status);
        textSystemStatus.setTextColor(ContextCompat.getColor(requireContext(), colorResId));
    }

    private void fetchThingSpeakData() {
        if ("YOUR_READ_API_KEY".equals(THINGSPEAK_READ_API_KEY)) {
            setSystemStatus("Add Read API Key", android.R.color.holo_red_dark);
            return;
        }

        executor.execute(() -> {
            try {
                URL url = new URL(THINGSPEAK_READ_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) content.append(line);
                    in.close();
                    connection.disconnect();
                    mainHandler.post(() -> updateThingSpeakUI(content.toString()));
                } else {
                    Log.e("THINGSPEAK_READ", "GET failed: " + code);
                    mainHandler.post(() -> {
                        if (code == 404) setSystemStatus("Wrong Channel ID?", android.R.color.holo_red_dark);
                        else setSystemStatus("Error: " + code, android.R.color.holo_red_dark);
                    });
                    connection.disconnect();
                }
            } catch (Exception e) {
                Log.e("THINGSPEAK_READ", "Error: " + e.getMessage());
                mainHandler.post(() -> setSystemStatus("Offline", android.R.color.darker_gray));
            }
        });
    }

    private void updateThingSpeakUI(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty() || !isAdded()) return;

        try {
            JSONObject feed = new JSONObject(jsonResponse);

            String createdAt = feed.optString("created_at");
            if (createdAt.isEmpty()) {
                setSystemStatus("No Data Yet", android.R.color.holo_orange_dark);
                return;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date lastUpdate = sdf.parse(createdAt);
            long now = new Date().getTime();
            long diff = now - lastUpdate.getTime();

            if (diff > (THINGSPEAK_UPLOAD_INTERVAL * 2)) {
                setSystemStatus("Offline (Lag)", android.R.color.holo_orange_dark);
            } else {
                setSystemStatus("Online", android.R.color.holo_green_dark);
            }

            String field1 = feed.optString("field1", "0"); // Moisture
            String field2 = feed.optString("field2", "0"); // Temperature
            String field3 = feed.optString("field3", "0"); // Humidity
            String field4 = feed.optString("field4", "0"); // Pump
            String field5 = feed.optString("field5", "0"); // Manual
            String field6 = feed.optString("field6", "0"); // Rain
            String field7 = feed.optString("field7", "0"); // Tank

            int pumpStatus = (int) Double.parseDouble(field4);
            int modeStatus = (int) Double.parseDouble(field5);
            int rainStatus = (int) Double.parseDouble(field6);
            int tankStatus = (int) Double.parseDouble(field7);

            isPumpOn = (pumpStatus == 1);
            isManualMode = (modeStatus == 1);

            textMoistureSensor.setText(String.format(Locale.getDefault(), "%.1f %%", Double.parseDouble(field1)));
            textTemperatureSensor.setText(String.format(Locale.getDefault(), "%.1f °C", Double.parseDouble(field2)));
            textHumiditySensor.setText(String.format(Locale.getDefault(), "%.1f %%", Double.parseDouble(field3)));

            textRainStatus.setText(rainStatus == 1 ? "RAINING" : "CLEAR");
            textTankLevel.setText(tankStatus == 1 ? "FULL" : "LOW");

            btnPumpToggle.setText(isPumpOn ? "PUMP: ON" : "PUMP: OFF");
            textSystemMode.setText(isManualMode ? "MODE: MANUAL" : "MODE: AUTO");

        } catch (Exception e) {
            Log.e("THINGSPEAK_PARSE", "Error parsing JSON: " + e.getMessage());
            setSystemStatus("Data Error", android.R.color.holo_red_dark);
        }
    }

    private void sendControlCommand(boolean pumpOn, boolean manualMode) {
        if ("YOUR_WRITE_API_KEY".equals(THINGSPEAK_WRITE_API_KEY)) {
            Toast.makeText(getContext(), "Add Write API Key to send commands", Toast.LENGTH_LONG).show();
            return;
        }

        executor.execute(() -> {
            try {
                int pumpState = pumpOn ? 1 : 0;
                int modeState = manualMode ? 1 : 0;

                String urlParams = "&field4=" + pumpState + "&field5=" + modeState;

                URL url = new URL(THINGSPEAK_WRITE_URL_BASE + urlParams);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);

                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    Log.i("THINGSPEAK_WRITE", "Command sent");
                    mainHandler.post(() -> Toast.makeText(getContext(), "Command Sent!", Toast.LENGTH_SHORT).show());
                } else {
                    Log.e("THINGSPEAK_WRITE", "POST failed: " + code);
                }
                connection.disconnect();

            } catch (Exception e) {
                Log.e("THINGSPEAK_WRITE", "Error: " + e.getMessage());
            }
        });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
    }
}
