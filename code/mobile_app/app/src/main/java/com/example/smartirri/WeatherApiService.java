package com.example.smartirri;
import com.example.smartirri.GeoResult;
import com.example.smartirri.WeatherResponse;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface WeatherApiService {

    @GET("geo/1.0/direct")
    Call<List<GeoResult>> geocode(
            @Query("q") String query,
            @Query("limit") int limit,
            @Query("appid") String apiKey
    );

    @GET("data/2.5/weather")
    Call<WeatherResponse> getCurrentWeatherByCoord(
            @Query("lat") double lat,
            @Query("lon") double lon,
            @Query("appid") String apiKey,
            @Query("units") String units
    );
}
