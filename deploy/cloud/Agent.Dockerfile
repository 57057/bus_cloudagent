FROM python:3.11-slim-bookworm
WORKDIR /opt/cloudagent
COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home cloudagent
COPY app ./app
COPY agent ./agent
COPY mock_data ./mock_data
USER 10001:10001
ENTRYPOINT ["python", "-m", "uvicorn", "app.app_main:app", "--host", "0.0.0.0", "--port", "5001", "--no-access-log"]
