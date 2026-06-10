FROM python:3.12-slim

WORKDIR /app

# Abhängigkeiten zuerst (besseres Layer-Caching)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# App-Code
COPY app/ ./app/

EXPOSE 8014

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8014"]
