FROM python:3.12-slim

# tzdata: python:3.12-slim enthält standardmäßig KEINE Zeitzonen-Datenbank.
# Ohne dieses Paket fällt der Container auf UTC zurück, selbst wenn TZ
# gesetzt ist (betrifft sowohl Log-Zeitstempel als auch die APScheduler-
# Cron-Zeit für den täglichen Update-Lauf).
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Europe/Berlin

WORKDIR /app

# Abhängigkeiten zuerst (besseres Layer-Caching)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# App-Code
COPY app/ ./app/

EXPOSE 8014

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8014"]
