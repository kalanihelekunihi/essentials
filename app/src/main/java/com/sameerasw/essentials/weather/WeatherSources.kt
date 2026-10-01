package com.sameerasw.essentials.weather

object WeatherSources {
    private val names = mapOf(
        "openmeteo" to "Open-Meteo",
        "metno" to "MET Norway (Yr)",
        "nws" to "US National Weather Service",
        "pirateweather" to "Pirate Weather",
        "tomorrowio" to "Tomorrow.io",
        "visualcrossing" to "Visual Crossing",
        "openweathermap" to "OpenWeatherMap",
        "weatherapi" to "WeatherAPI.com",
    )

    fun displayName(id: String): String = names[id] ?: id
}
