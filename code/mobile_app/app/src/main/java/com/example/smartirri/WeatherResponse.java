package com.example.smartirri;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class WeatherResponse {
    private String name; // city name
    private Main main;
    private Wind wind;
    private List<Weather> weather;

    public String getName() { return name; }
    public Main getMain() { return main; }
    public Wind getWind() { return wind; }
    public List<Weather> getWeather() { return weather; }

    public static class Main {
        private double temp;
        private int humidity;
        @SerializedName("feels_like")
        private Double feelsLike; // optional

        public double getTemp() { return temp; }
        public int getHumidity() { return humidity; }
        public Double getFeelsLike() { return feelsLike; }
    }

    public static class Wind {
        private double speed;

        public double getSpeed() { return speed; }
    }

    public static class Weather {
        private String description;

        public String getDescription() { return description; }
    }
}
