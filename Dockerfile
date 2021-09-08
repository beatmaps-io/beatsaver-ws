FROM openjdk:15.0.1-jdk-slim
EXPOSE 8080

RUN apt-get update
RUN apt-get install -y curl

WORKDIR /app
COPY . .

WORKDIR /app/bin
ENTRYPOINT ["./BeatMaps-WS"]
