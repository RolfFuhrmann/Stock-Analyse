FROM --platform=linux/arm64 python:3.12-slim
WORKDIR /app

COPY requirements.txt .
# Installiert erst die Requirements und erzwingt danach das Update für yfinance:
RUN pip install --no-cache-dir -r requirements.txt && \
    pip install --no-cache-dir --upgrade yfinance

COPY . .
EXPOSE 8011
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8011"]
