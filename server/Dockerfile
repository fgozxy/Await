FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY await_server.py await_backup.py ./
RUN useradd --uid 10001 --create-home await && mkdir /data && chown await:await /data
USER await
EXPOSE 8080
CMD ["python", "-u", "await_server.py"]
