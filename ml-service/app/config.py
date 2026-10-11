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
    # Anzahl Kerzen die als Input-Fenster verwendet werden (gilt für alle Intervals)
    lookback_days: int = 60
    # Mindestanzahl Kerzen für das Training (weniger = kein Training für diesen Ticker).
    # Vorher an zwei Stellen (db_client.py, trainer.py) fest verdrahtete Werte
    # (100/200/200), jetzt hier zentral - beide Stellen lesen ab 21.09. von hier.
    min_daily_candles: int = 100
    min_4h_candles:    int = 200
    min_1h_candles:    int = 200

    # ── Zeitreihen-Split (train/val/test), 21.09. ──────────────
    # Aufgeteilt wird PRO Ticker und Zeitrahmen chronologisch (ältester Teil
    # zuerst) - nicht mehr über den ganzen Datensatz nach Listenreihenfolge,
    # siehe trainer.py. val dient dem Early Stopping und der Kalibrierung,
    # test ist ein davon unberührter Holdout für die berichteten Metriken.
    train_frac: float = 0.70
    val_frac:   float = 0.10
    # test_frac ergibt sich aus dem Rest (0.20)
    # Horizont/Schwelle PRO ZEITRAHMEN (01.10., vorher eine gemeinsame Zahl für
    # alle drei - mit eigenständigen Modellen pro Zeitrahmen (29.09.) ergibt
    # "5 Kerzen" aber ganz unterschiedliche Zeiträume (5 Tageskerzen = 1 Woche,
    # 5 Stundenkerzen < 1 Handelstag). 1d ist das primäre Signal (muss eine
    # echte, mehrtägige Umkehr zeigen); 4h/1h dienen laut Rolf gezielt dem
    # Timing des Einstiegs, dafür kürzerer Horizont und kleinere Schwelle.
    # Ausgangswerte, noch nicht an echten Ergebnissen kalibriert - gerne
    # nachjustieren, sobald die ersten Trainings-Metriken vorliegen.
    forecast_horizon: dict[str, int] = {"1d": 10, "4h": 12, "1h": 16}
    # Mindest-Preisänderung (%) um als Umkehr zu gelten
    reversal_threshold_pct: dict[str, float] = {"1d": 5.0, "4h": 3.0, "1h": 2.0}
    # ── Reversal-Label-Vorbedingung (29.09., ersetzt das reine
    # trendunabhängige Anstiegs-Label) ──────────────────────────
    # Nur Zeitpunkte mit vorherigem signifikanten Abwärtstrend zählen
    # überhaupt als Trainingsbeispiel: Schlusskurs >= downtrend_threshold_pct%
    # unter dem höchsten Schlusskurs der letzten downtrend_lookback Kerzen.
    downtrend_lookback:      int   = 20
    downtrend_threshold_pct: float = 8.0
    # Toleranz für "neues Tief" (verhindert, dass einzelne Docht-Ausreißer
    # ein sonst gültiges Reversal-Label kippen)
    low_break_tolerance_pct: float = 0.3

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
