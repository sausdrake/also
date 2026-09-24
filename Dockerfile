# Этап 1: Сборка приложения
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Копируем файлы проекта
COPY pom.xml .
COPY src ./src

# Сборка JAR-файла (пропускаем тесты для ускорения)
RUN mvn clean package -DskipTests

# Этап 2: Запуск приложения
FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# Копируем JAR из этапа сборки
COPY --from=builder /app/target/autoauction-*.jar app.jar

# MEDIA-3.4: FFmpeg/ffprobe for server-side video processing (see FfmpegVideoProcessor)
RUN apk add --no-cache ffmpeg

# Создаём пользователя для запуска (не root)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Открываем порт
EXPOSE 8081

# Точка входа
ENTRYPOINT ["java", "-jar", "app.jar"]