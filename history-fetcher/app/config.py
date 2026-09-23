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
    # 4h-Kerzen: 6 Monate initiale Tiefe (6 × 30 × 6 Kerzen/Tag ≈ 1080)
    initial_4h_candles:  int = 1080
    # 4h-Kerzen: Update-Puffer (7 Tage × 6 Kerzen/Tag)
    update_4h_candles:   int = 42
    # 1h-Kerzen: Update-Puffer in Stunden (24h ≈ 1 Handelstag Reserve)
    update_hourly_buffer_hours: int = 24

    # ── Automatischer Betrieb ─────────────────────────────────
    # Standard: AUS (20.09.). Der Fetcher läuft nur manuell, um Daten in der
    # DB zu reparieren: POST /fetch/update (fehlende Kerzen nachholen) bzw.
    # POST /fetch/initial (alles neu abrufen und überschreiben). Aktuell
    # gehalten werden aktiv genutzte Ticker durch den Write-back des
    # agent-service-java. true schaltet zusätzlich den täglichen Cron-Lauf
    # und den Catch-up bei jedem Container-Start wieder ein (Env:
    # AUTO_RUN_ENABLED; die frühere Variable AUTO_INITIAL_RUN wird nicht
    # mehr gelesen).
    auto_run_enabled: bool = False

    # ── Scheduler (nur relevant bei auto_run_enabled=true) ────
    # Uhrzeit für den täglichen Update-Lauf (nach Börsenschluss)
    daily_update_hour:   int = 20
    daily_update_minute: int = 0
    # Zeitzone für den Cron-Zeitpunkt, unabhängig von der Container-Systemzeit
    # explizit gesetzt (siehe TZ/tzdata-Hinweis in Dockerfile).
    scheduler_timezone:  str = "Europe/Berlin"
    # Toleranz in Stunden, falls der geplante Lauf verpasst wurde (z.B. weil
    # der Host-Rechner zur geplanten Zeit im Schlaf-/Ruhezustand war). Ohne
    # diese Einstellung verwendet APScheduler nur 1 Sekunde Toleranz und
    # überspringt einen verpassten Lauf komplett, statt ihn nachzuholen.
    misfire_grace_hours: int = 12

    # ── HTTP ──────────────────────────────────────────────────
    # Timeout in Sekunden für SSE-Streams vom Yahoo/TwelveData-Service
    stream_timeout_sec: int = 120
    # Pause zwischen zwei Ticker-Abrufen bei Yahoo (kurz)
    ticker_delay_sec:        float = 0.5
    # Pause nach JEDEM Yahoo-Request (Anti-Scraping/Rate-Limit beim
    # VPN-Gateway). Wichtig bei mehreren Yahoo-Calls pro Ticker
    # (daily + hourly + 4h-Aggregation = 3 Requests in Folge).
    yahoo_delay_sec:         float = 2.0
    # Pause nach JEDEM TwelveData-Request (Free Plan: 8 req/min = 7.5s)
    # daily + hourly = 2 Requests pro Ticker → 8s Pause nach jedem Request
    twelvedata_delay_sec:    float = 8.0

    class Config:
        env_file = ".env"


settings = Settings()
