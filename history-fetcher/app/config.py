"""
history-fetcher/app/config.py
Alle Konfigurationswerte aus Umgebungsvariablen mit sinnvollen Defaults.
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):

    # ── Abhängige Services ────────────────────────────────────
    yahoo_service_url:      str = "http://yahoo-service:8011"
    twelvedata_service_url: str = "http://twelvedata-service:8012"
    db_service_url:         str = "http://db-service:8013"

    # ── Datenmenge ────────────────────────────────────────────
    # Anzahl Tage für den initialen Befüll-Lauf (5 Jahre ≈ 1.825)
    initial_daily_days:  int = 1825
    # Anzahl Stunden für den initialen Stunden-Abruf (12 Monate ≈ 8.760)
    initial_hourly_hours: int = 8760
    # Tageskerzen für den täglichen Update-Lauf (10 Tage Puffer)
    update_daily_days:   int = 10

    # ── Scheduler ─────────────────────────────────────────────
    # Uhrzeit für den täglichen Update-Lauf (nach Börsenschluss)
    daily_update_hour:   int = 20
    daily_update_minute: int = 0

    # ── HTTP ──────────────────────────────────────────────────
    # Timeout in Sekunden für SSE-Streams vom Yahoo/TwelveData-Service
    stream_timeout_sec: int = 120
    # Pause zwischen zwei Ticker-Abrufen bei Yahoo (kurz)
    ticker_delay_sec:        float = 0.5
    # Pause nach JEDEM TwelveData-Request (Free Plan: 8 req/min = 7.5s)
    # daily + hourly = 2 Requests pro Ticker → 8s Pause nach jedem Request
    twelvedata_delay_sec:    float = 8.0

    class Config:
        env_file = ".env"


settings = Settings()
