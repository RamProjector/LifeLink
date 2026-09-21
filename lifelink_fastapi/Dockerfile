FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

COPY requirements.txt ./
RUN python -m pip install --upgrade --no-cache-dir pip setuptools wheel \
    && python -m pip install --no-cache-dir --only-binary=cryptography -r requirements.txt

COPY app ./app
COPY sql ./sql
COPY pyproject.toml ./

CMD sh -c 'uvicorn app.main_postgres:app --host 0.0.0.0 --port ${PORT:-8000}'
