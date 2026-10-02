FROM python:3.12-slim
WORKDIR /app
COPY await_server.py .
RUN useradd --uid 10001 --create-home await && mkdir /data && chown await:await /data
USER await
EXPOSE 8080
CMD ["python", "-u", "await_server.py"]
