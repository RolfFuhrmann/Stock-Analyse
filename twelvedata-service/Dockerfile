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
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8012
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8012"]
