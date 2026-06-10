"""
ml-service/app/config.py
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):

    # ── Abhängige Services ────────────────────────────────────
    db_service_url: str = "http://db-service:8013"

    # ── Modell-Speicherort ────────────────────────────────────
    model_dir: str = "/app/models"

    # ── Feature-Engineering ───────────────────────────────────
    # Anzahl Tageskerzen die als Input-Fenster verwendet werden
    lookback_days: int = 60
    # Wie viele Tage in die Zukunft wird die Umkehr vorhergesagt?
    forecast_horizon: int = 5
    # Mindest-Preisänderung (%) um als Umkehr zu gelten
    reversal_threshold_pct: float = 3.0

    # ── Training ──────────────────────────────────────────────
    # Minimale Anzahl Trainingsbeispiele pro Klasse
    min_samples_per_class: int = 30
    # Wöchentliches Retraining: Sonntag 02:00 Uhr
    retrain_weekday: int = 6   # 0=Montag, 6=Sonntag
    retrain_hour:    int = 2
    retrain_minute:  int = 0

    class Config:
        env_file = ".env"


settings = Settings()
