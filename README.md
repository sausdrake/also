# AutoAuction

Платформа онлайн-аукционов автомобилей с ставками в реальном времени, системой диагностики и многоролевым управлением.

## Стек технологий

**Backend**
- Java 17 + Spring Boot 3.1.5
- Spring Security (JWT-аутентификация)
- Spring WebSocket (STOMP) — ставки в реальном времени
- Spring Data JPA + PostgreSQL 16
- Flyway — миграции базы данных
- Bucket4j — rate limiting
- Springdoc OpenAPI — Swagger-документация

**Frontend**
- React 18 + TypeScript
- Vite
- React Router DOM, React Query, Zustand
- Axios + SockJS/STOMP
- Tailwind CSS, React Hook Form, Zod

**Инфраструктура**
- Docker + Docker Compose
- Maven 3.9

## Функциональность

- **Аукционы** — создание, просмотр, ставки в реальном времени, покупка «Купить сейчас», автоматическое продление при ставке в последние 30 секунд
- **Роли** — пользователь (`USER`), диагност (`DIAGNOSTIC`), администратор (`ADMIN`)
- **Депозиты** — пополнение, заморозка 1% от стартовой цены для участия в аукционе, история транзакций
- **Диагностика** — создание заявок на диагностику, шаблоны осмотра с группами и элементами (оценка, фото, видео)
- **Управление автомобилями** — жизненный цикл: `DRAFT → PENDING_REVIEW → APPROVED → IN_AUCTION → SOLD`
- **Администрирование** — панель со статистикой, управление аукционами и автомобилями, проверка диагностических отчётов

## Архитектура

Проект построен по **гексагональной архитектуре (DDD)**:

```
web (Controllers / DTOs)
    ↓
application (Services / Use Cases)
    ↓
domain (Entities / Business Logic / Ports)
    ↓
infrastructure (JPA Repositories / Mappers)
```

```
src/main/java/com/example/autoauction/
├── admin/          # Управление аукционами и автомобилями
├── auction/        # Домен аукционов и ставок
├── auth/           # Аутентификация и JWT
├── deposit/        # Депозиты и транзакции
├── diagnostic/     # Диагностика и шаблоны осмотра
├── user/           # Пользователи и роли
├── vehicle/        # Управление автомобилями
└── config/         # Spring-конфигурация

client/src/
├── api/            # Axios-клиент
├── components/     # ProtectedRoute, AuctionTimer, BidHistory
├── hooks/          # useWebSocket
└── pages/          # HomePage, AuctionPage, LoginPage, RegisterPage,
                    # AdminDashboard, DiagnosticDashboard
```

## Быстрый старт

### Docker Compose (рекомендуется)

```bash
docker-compose up -d
```

> База — PostgreSQL 16. Том `postgres_data`, созданный прежней версией (15), под 16 не запустится:
> перенос данных — только через `pg_dump`/восстановление, см. `docs/ops/postgresql-version.md`.

- Бэкенд: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- БД: `localhost:5432`

Остановить:

```bash
docker-compose down
```

### Локальная разработка

**Требования:** Java 17, Node.js 16+, PostgreSQL 16, Maven 3.9

**Backend:**

```bash
# Создайте БД и пользователя PostgreSQL (совпадающие с application.properties)
./mvnw spring-boot:run
```

**Frontend:**

```bash
cd client
npm install
npm run dev
```

Фронтенд доступен на http://localhost:5173, бэкенд на http://localhost:8080.

## Переменные окружения

Файл `.env` в корне проекта:

```env
JWT_SECRET=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
SPRING_DATASOURCE_USERNAME=auto_auction_user
SPRING_DATASOURCE_PASSWORD=auto_auction_password
```

В `application.properties` настраивается:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/auto_auction
jwt.expiration=86400000          # 24 часа
jwt.refresh-expiration=604800000 # 7 дней

# Автоматическое продление аукциона
auction.extension.enabled=true
auction.extension.threshold-seconds=30
auction.extension.extension-minutes=5
```

## Основные API-эндпоинты

| Метод | URL | Описание |
|-------|-----|----------|
| POST | `/api/auth/register` | Регистрация |
| POST | `/api/auth/login` | Вход (возвращает JWT + refresh token) |
| POST | `/api/auth/refresh` | Обновление токена |
| GET | `/api/public/auctions` | Список активных аукционов |
| GET | `/api/public/auctions/{id}` | Детали аукциона |
| POST | `/api/auctions/{id}/bids` | Сделать ставку |
| POST | `/api/auctions/{id}/buy-now` | Купить сейчас |
| GET | `/api/deposits` | Баланс депозита |
| POST | `/api/deposits/top-up` | Пополнение депозита |
| POST | `/api/admin/auctions` | Создать аукцион (ADMIN) |
| POST | `/api/admin/vehicles/{id}/approve` | Одобрить автомобиль (ADMIN) |

Полная интерактивная документация: http://localhost:8080/swagger-ui.html

## WebSocket

Подписка на обновления аукциона в реальном времени:

```
ws://localhost:8080/ws/auctions
STOMP topic: /topic/auction/{auctionId}
```

Сообщение обновления:

```json
{
  "auctionId": 1,
  "currentPrice": 15000.00,
  "totalBids": 5,
  "winnerId": 42,
  "status": "ACTIVE",
  "extensionCount": 1
}
```

## Миграции базы данных

Flyway автоматически применяет миграции при запуске. Файлы находятся в:

```
src/main/resources/db/migration/
├── V1__user_roles_tables.sql
├── V4__add_deposit_frozen_table.sql
├── V9__diagnostic_system.sql
└── V10__init_diagnostic_templates.sql
```

## Сборка

```bash
# Backend — сборка JAR
./mvnw clean package -DskipTests

# Frontend — продакшн-сборка
cd client && npm run build

# Docker-образ
docker build -t autoauction .
```

## Тестирование

В директории `postman/` находится коллекция Postman для ручного тестирования API.

Быстрая проверка через curl:

```bash
# Регистрация
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"user1","email":"user1@example.com","password":"password123"}'

# Вход
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user1","password":"password123"}'

# Список аукционов
curl http://localhost:8080/api/public/auctions \
  -H "Authorization: Bearer <accessToken>"
```

## Статусы сущностей

**Аукцион:** `CREATED → ACTIVE → COMPLETED / SOLD / EXPIRED / CANCELLED`

**Автомобиль:** `DRAFT → PENDING_REVIEW → APPROVED / NEEDS_FIXES → IN_AUCTION → SOLD`

**Диагностическая заявка:** `PENDING → IN_PROGRESS → COMPLETED → APPROVED / REJECTED`
