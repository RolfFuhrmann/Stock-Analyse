FROM --platform=linux/arm64 python:3.12-slim
WORKDIR /app

# tzdata: python:3.12-slim enthält standardmäßig KEINE Zeitzonen-Datenbank.
# Ohne dieses Paket fällt der Container auf UTC zurück, selbst wenn TZ
# gesetzt ist (gleiches Problem wie zuvor bei history-fetcher).
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Europe/Berlin

COPY requirements.txt .
# Installiert erst die Requirements und erzwingt danach das Update für yfinance:
RUN pip install --no-cache-dir -r requirements.txt && \
    pip install --no-cache-dir --upgrade yfinance

COPY . .
EXPOSE 8011
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8011"]
