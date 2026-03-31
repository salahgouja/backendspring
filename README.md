<p align="center">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen?logo=springboot" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Java-22-orange?logo=openjdk" alt="Java">
  <img src="https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/Redis-7-red?logo=redis" alt="Redis">
  <img src="https://img.shields.io/badge/License-Private-lightgrey" alt="License">
</p>

# 🏦 Amen Bank — Core Banking API

A comprehensive **RESTful banking backend** built with Spring Boot, designed for the Tunisian banking market. It covers account management, transfers (internal, batch, recurring), credit requests, a full loan engine with amortization, fraud detection, 2FA authentication, and more.

---

## 📑 Table of Contents

- [Features](#-features)
- [Tech Stack](#-tech-stack)
- [Architecture](#-architecture)
- [Prerequisites](#-prerequisites)
- [Getting Started](#-getting-started)
- [Environment Variables](#-environment-variables)
- [API Documentation](#-api-documentation)
- [API Endpoints](#-api-endpoints)
- [Project Structure](#-project-structure)
- [Database Schema](#-database-schema)
- [Security](#-security)
- [Testing](#-testing)
- [Deployment](#-deployment)
- [Contributing](#-contributing)

---

## ✨ Features

### 🔐 Authentication & Security
- JWT-based authentication (access + refresh tokens)
- Two-Factor Authentication (2FA) via TOTP (Google Authenticator)
- Password reset via email OTP
- Rate limiting on sensitive endpoints (Bucket4j)
- Account lockout after failed login attempts
- Role-based access control (PARTICULIER, COMMERCANT, AGENT, ADMIN)

### 🏧 Account Management
- Multi-account support (Courant, Épargne, Commercial)
- Account lifecycle: request → approval → active → suspended → closed
- Transaction history with filters (date, amount, type)
- PDF account statement generation (OpenPDF)

### 💸 Transfers
- **Internal transfers** — between own accounts
- **Beneficiary transfers** — to saved recipients
- **Batch transfers** — grouped transfers to multiple beneficiaries
- **Recurring transfers** — scheduled via cron expressions
- Reference number generation for receipts
- 2FA validation before execution

### 📋 Credit Requests
- Credit simulation calculator
- Full application workflow: simulation → submitted → review → approved/rejected → disbursed
- Document upload (pay slips, ID, bank statements, etc.)
- AI risk scoring placeholder

### 🏛️ Loan Engine
- Loan product catalog with configurable parameters
- Variable & fixed rate loans
- Tunisian TMM (Taux du Marché Monétaire) reference rate support
- Amortization schedule generation (Actual/360 day count)
- Daily interest accrual
- Automatic rate revision for variable-rate loans
- Grace period support (total or interest-only)
- Early repayment handling
- Penalty calculation for overdue payments

### 🛡️ Fraud Detection
- Transaction scoring (amount, velocity, time patterns)
- Alert management (open, investigating, resolved, false positive)

### 🔔 Notifications
- In-app notification system
- Email notifications (Gmail SMTP)
- Types: transfer, security, credit, account, fraud, promotion

### 📊 Administration
- User management (activate/deactivate)
- Agent creation
- Audit trail for all sensitive operations
- Agency management by governorate

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Java 22 |
| **Framework** | Spring Boot 3.4.3 |
| **Security** | Spring Security + JWT (jjwt 0.12.6) |
| **2FA** | TOTP (dev.samstevens.totp 1.7.1) |
| **Database** | PostgreSQL 16 |
| **ORM** | Spring Data JPA / Hibernate |
| **Cache** | Redis + Spring Cache |
| **API Docs** | SpringDoc OpenAPI 2.8.5 (Swagger UI) |
| **PDF** | OpenPDF 2.0.3 |
| **Email** | Spring Boot Mail (Gmail SMTP) |
| **Rate Limiting** | Bucket4j 8.14.0 |
| **Build** | Maven (mvnw wrapper included) |
| **Env Config** | spring-dotenv 4.0.0 |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Client (React)                    │
│              :3000 / :5173 / :5174                  │
└───────────────────────┬─────────────────────────────┘
                        │ HTTPS / REST
┌───────────────────────▼─────────────────────────────┐
│              Spring Boot API (:8080/api)             │
│  ┌─────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │  Auth   │  │ Security │  │   Rate Limiter     │  │
│  │ Filter  │──│  Config  │──│   (Bucket4j)       │  │
│  └────┬────┘  └──────────┘  └────────────────────┘  │
│       │                                              │
│  ┌────▼─────────────────────────────────────────┐   │
│  │              Controllers (REST)               │   │
│  │  Auth · Account · Transfer · Credit · Loan   │   │
│  │  Beneficiary · Notification · FraudAlert     │   │
│  │  Admin · Agency                               │   │
│  └────┬──────────────────────────────────────────┘  │
│       │                                              │
│  ┌────▼─────────────────────────────────────────┐   │
│  │              Services (Business Logic)        │   │
│  │  AuthService · AccountService · TransferSvc  │   │
│  │  CreditService · LoanEngineService           │   │
│  │  FraudService · EmailService · AuditService  │   │
│  │  ScheduledTransferService · StatementService │   │
│  └────┬──────────────────────────────────────────┘  │
│       │                                              │
│  ┌────▼─────────────────────────────────────────┐   │
│  │           Repositories (Spring Data JPA)      │   │
│  └────┬──────────────────────────────────────────┘  │
└───────┼──────────────────────────────────────────────┘
        │
┌───────▼──────┐   ┌──────────┐
│  PostgreSQL  │   │  Redis   │
│   :5432      │   │  :6379   │
└──────────────┘   └──────────┘
```

---

## 📋 Prerequisites

Make sure you have the following installed:

| Tool | Version | Purpose |
|---|---|---|
| **Java JDK** | 22+ | Runtime |
| **PostgreSQL** | 14+ | Primary database |
| **Redis** | 6+ | Caching |
| **Maven** | 3.9+ | Build (or use included `mvnw`) |
| **Git** | Latest | Version control |

---

## 🚀 Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/your-org/amenbank-core-banking.git
cd amenbank-core-banking
```

### 2. Set up the database

```sql
-- Connect to PostgreSQL and create the database & user
CREATE USER amenbank WITH PASSWORD 'your_secure_password';
CREATE DATABASE amenbank OWNER amenbank;
GRANT ALL PRIVILEGES ON DATABASE amenbank TO amenbank;
```

### 3. Configure environment variables

```bash
# Copy the example env file
cp .env.example .env

# Edit .env with your actual values
# At minimum, set: DB_PASSWORD, JWT_SECRET
```

### 4. Start Redis

```bash
# Linux/Mac
redis-server

# Windows (via WSL or Docker)
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### 5. Build and run

```bash
# Using Maven wrapper (recommended)
./mvnw spring-boot:run          # Linux/Mac
.\mvnw.cmd spring-boot:run      # Windows

# Or use the provided script
start.bat                        # Windows
```

### 6. Access the application

| Resource | URL |
|---|---|
| **API Base** | `http://localhost:8080/api` |
| **Swagger UI** | `http://localhost:8080/api/swagger-ui.html` |
| **OpenAPI JSON** | `http://localhost:8080/api/api-docs` |

> 📌 The application auto-creates the database schema on first run (`ddl-auto: create`) and seeds demo data via `DataLoader`.

---

## 🔧 Environment Variables

All configuration is managed through `.env` file (loaded by `spring-dotenv`).

See [`.env.example`](.env.example) for the complete list. Key variables:

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `amenbank` | Database name |
| `DB_USERNAME` | `amenbank` | Database user |
| `DB_PASSWORD` | — | Database password |
| `JWT_SECRET` | — | JWT signing key (min 256 bits) |
| `JWT_EXPIRATION` | `86400000` | Access token TTL (ms) — 24h |
| `JWT_REFRESH_EXPIRATION` | `604800000` | Refresh token TTL (ms) — 7d |
| `EMAIL_ENABLED` | `false` | Enable email sending |
| `MAIL_USERNAME` | — | SMTP username |
| `MAIL_PASSWORD` | — | SMTP password (Gmail App Password) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,...` | Allowed frontend origins |

---

## 📖 API Documentation

Interactive API docs are available via **Swagger UI** at:

```
http://localhost:8080/api/swagger-ui.html
```

### Default Test Accounts (seeded by DataLoader)

| Role | Email | Password |
|---|---|---|
| **Admin** | `admin@amenbank.com.tn` | `Admin@123` |
| **Agent** | `agent.tunis@amenbank.com.tn` | `Agent@123` |
| **Client** | `salahgouja2001@gmail.com` | `Client@123` |

> After login, copy the `accessToken` from the response and click **"Authorize"** in Swagger UI → paste: `Bearer <your_token>`

---

## 🔗 API Endpoints

### Authentication (`/api/auth`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/auth/register` | Register new client | ❌ |
| `POST` | `/auth/login` | Login (returns JWT) | ❌ |
| `POST` | `/auth/refresh` | Refresh access token | ❌ |
| `POST` | `/auth/2fa/enable` | Enable 2FA (TOTP setup) | ✅ |
| `POST` | `/auth/2fa/verify` | Verify 2FA code | ✅ |
| `POST` | `/auth/2fa/disable` | Disable 2FA | ✅ |
| `POST` | `/auth/forgot-password` | Request password reset OTP | ❌ |
| `POST` | `/auth/reset-password` | Reset password with OTP | ❌ |
| `PUT` | `/auth/change-password` | Change password | ✅ |
| `GET` | `/auth/profile` | Get current user profile | ✅ |

### Accounts (`/api/accounts`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/accounts` | List my accounts | ✅ |
| `GET` | `/accounts/{id}` | Account details | ✅ |
| `GET` | `/accounts/{id}/transactions` | Transaction history | ✅ |
| `GET` | `/accounts/{id}/statement` | Download PDF statement | ✅ |
| `POST` | `/accounts` | Request new account | ✅ |

### Transfers (`/api/transfers`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/transfers/internal` | Internal transfer | ✅ |
| `POST` | `/transfers/beneficiary` | Transfer to beneficiary | ✅ |
| `POST` | `/transfers/batch` | Batch transfer | ✅ |
| `POST` | `/transfers/schedule` | Schedule recurring transfer | ✅ |
| `GET` | `/transfers/history` | Transfer history | ✅ |
| `PUT` | `/transfers/{id}/cancel` | Cancel pending transfer | ✅ |

### Beneficiaries (`/api/beneficiaries`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/beneficiaries` | List my beneficiaries | ✅ |
| `POST` | `/beneficiaries` | Add beneficiary | ✅ |
| `DELETE` | `/beneficiaries/{id}` | Remove beneficiary | ✅ |

### Credits (`/api/credits`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/credits/simulate` | Credit simulation | ✅ |
| `POST` | `/credits/submit` | Submit credit request | ✅ |
| `GET` | `/credits` | My credit requests | ✅ |
| `GET` | `/credits/{id}` | Credit details | ✅ |
| `POST` | `/credits/{id}/documents` | Upload document | ✅ |
| `GET` | `/credits/{id}/documents` | List documents | ✅ |
| `PUT` | `/credits/{id}/review` | Review (Agent) | ✅ 🔒 |
| `PUT` | `/credits/{id}/disburse` | Disburse (Agent) | ✅ 🔒 |
| `PUT` | `/credits/{id}/cancel` | Cancel request | ✅ |

### Loans (`/api/loans`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/loans` | My active loans | ✅ |
| `GET` | `/loans/{id}` | Loan details | ✅ |
| `GET` | `/loans/{id}/schedule` | Amortization schedule | ✅ |
| `GET` | `/loans/{id}/payments` | Payment history | ✅ |
| `POST` | `/loans/{id}/pay` | Make a payment | ✅ |
| `POST` | `/loans/simulate` | Loan simulation | ✅ |
| `GET` | `/loans/products` | Loan product catalog | ✅ |

### Notifications (`/api/notifications`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/notifications` | My notifications | ✅ |
| `GET` | `/notifications/unread-count` | Unread count | ✅ |
| `PUT` | `/notifications/{id}/read` | Mark as read | ✅ |
| `PUT` | `/notifications/read-all` | Mark all as read | ✅ |

### Fraud Alerts (`/api/fraud-alerts`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/fraud-alerts` | List alerts | ✅ 🔒 |
| `PUT` | `/fraud-alerts/{id}/resolve` | Resolve alert | ✅ 🔒 |

### Admin (`/api/admin`)
| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/admin/users` | List all users | ✅ 🔒 |
| `PUT` | `/admin/users/{id}/activate` | Activate user | ✅ 🔒 |
| `PUT` | `/admin/users/{id}/deactivate` | Deactivate user | ✅ 🔒 |
| `POST` | `/admin/agents` | Create agent | ✅ 🔒 |
| `GET` | `/admin/audit-logs` | Audit trail | ✅ 🔒 |
| `GET` | `/admin/dashboard` | Statistics | ✅ 🔒 |

> 🔒 = Requires ADMIN or AGENT role

---

## 📂 Project Structure

```
src/main/java/com/amenbank/banking_webapp/
├── BankingApplication.java              # Entry point
├── config/
│   ├── CacheConfig.java                 # Cache configuration
│   ├── DataLoader.java                  # Demo data seeder
│   ├── OpenApiConfig.java               # Swagger/OpenAPI config
│   ├── SecurityConfig.java              # Spring Security config
│   └── WebMvcConfig.java                # CORS & interceptors
├── controller/
│   ├── AccountController.java           # Account endpoints
│   ├── AdminController.java             # Admin endpoints
│   ├── AgencyController.java            # Agency endpoints
│   ├── AuthController.java              # Auth endpoints
│   ├── BeneficiaryController.java       # Beneficiary endpoints
│   ├── CreditController.java           # Credit request endpoints
│   ├── FraudAlertController.java        # Fraud alert endpoints
│   ├── LoanController.java             # Loan engine endpoints
│   ├── NotificationController.java      # Notification endpoints
│   └── TransferController.java          # Transfer endpoints
├── dto/
│   ├── request/                         # 14 request DTOs
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   ├── TransferRequest.java
│   │   ├── BatchTransferRequest.java
│   │   ├── CreditSimulationRequest.java
│   │   └── ...
│   └── response/                        # 14 response DTOs
│       ├── AuthResponse.java
│       ├── AccountResponse.java
│       ├── LoanContractResponse.java
│       └── ...
├── exception/
│   ├── BankingException.java            # Custom exception
│   └── GlobalExceptionHandler.java      # Global error handler
├── model/                               # 21 JPA entities
│   ├── User.java
│   ├── Account.java
│   ├── Transaction.java
│   ├── Transfer.java
│   ├── CreditRequest.java
│   ├── LoanContract.java
│   ├── LoanProduct.java
│   ├── AmortizationSchedule.java
│   ├── FraudAlert.java
│   ├── AuditLog.java
│   └── ...
├── repository/                          # 21 Spring Data repositories
│   ├── UserRepository.java
│   ├── AccountRepository.java
│   └── ...
├── security/
│   ├── JwtAuthFilter.java              # JWT authentication filter
│   ├── JwtTokenProvider.java            # JWT token utilities
│   ├── RateLimitInterceptor.java        # Rate limiting
│   └── UserDetailsServiceImpl.java      # Spring Security UserDetails
└── service/                             # 12 service classes
    ├── AuthService.java
    ├── AccountService.java
    ├── TransferService.java
    ├── CreditService.java
    ├── LoanEngineService.java
    ├── FraudService.java
    ├── EmailService.java
    ├── AuditService.java
    ├── ScheduledTransferService.java
    ├── StatementService.java
    ├── BeneficiaryService.java
    └── CreditDocumentService.java
```

---

## 🗄️ Database Schema

The application uses **21 tables** across these domains:

```
USERS ─────────── ACCOUNTS ─────── TRANSACTIONS
  │                  │                  │
  ├── NOTIFICATIONS  ├── TRANSFERS ─────┘
  │                  │       │
  ├── BENEFICIARIES  │  BATCH_TRANSFERS
  │                  │       │
  ├── CREDIT_REQUESTS│  BATCH_TRANSFER_ITEMS
  │       │          │
  │  CREDIT_DOCUMENTS│
  │       │          │
  │  LOAN_CONTRACTS──┘
  │       │
  │  ┌────┴──────────────────────┐
  │  │ AMORTIZATION_SCHEDULE     │
  │  │ LOAN_PAYMENTS             │
  │  │ INTEREST_ACCRUALS         │
  │  │ RATE_REVISIONS            │
  │  └──────────────────────────-┘
  │
  ├── FRAUD_ALERTS
  ├── PASSWORD_RESET_TOKENS
  └── AGENCIES

LOAN_PRODUCTS        (catalog - standalone)
REFERENCE_RATES      (TMM history - standalone)
AUDIT_LOGS           (immutable trail)
```

> Schema is auto-generated by Hibernate. For production, switch `ddl-auto` to `validate` and use Flyway/Liquibase migrations.

---

## 🔒 Security

### Authentication Flow
```
1. POST /api/auth/login  { email, password }
        │
        ▼
2. If 2FA enabled → returns { requires2FA: true }
        │
        ▼
3. POST /api/auth/2fa/verify  { email, code }
        │
        ▼
4. Returns { accessToken, refreshToken }
        │
        ▼
5. Use header: Authorization: Bearer <accessToken>
```

### Security Features
- **BCrypt** password hashing
- **JWT** with configurable expiration
- **TOTP 2FA** compatible with Google Authenticator / Authy
- **Rate limiting** on login, register, password reset
- **Account lockout** after 5 failed login attempts (15 min)
- **CORS** restricted to configured frontend origins
- **Audit logging** for all sensitive operations

---

## 🧪 Testing

```bash
# Run all tests
./mvnw test

# Run with verbose output
./mvnw test -Dspring-boot.test.debug=true
```

### Manual Testing via Swagger UI

1. Open `http://localhost:8080/api/swagger-ui.html`
2. Login with a test account → copy `accessToken`
3. Click **Authorize** 🔓 → enter `Bearer <token>`
4. Test any endpoint directly from the browser

---

## 🚢 Deployment

### Production Checklist

- [ ] Set `spring.jpa.hibernate.ddl-auto` to `validate`
- [ ] Set a strong `JWT_SECRET` (256+ bit random key)
- [ ] Configure a production PostgreSQL instance
- [ ] Set `EMAIL_ENABLED=true` with valid SMTP credentials
- [ ] Set `CORS_ALLOWED_ORIGINS` to your production domain
- [ ] Enable HTTPS (TLS)
- [ ] Set up database backup strategy
- [ ] Add Flyway or Liquibase for DB migrations
- [ ] Set logging level to `INFO` for production

### Docker (optional)

```bash
# Build
./mvnw clean package -DskipTests
docker build -t amenbank-api .

# Run
docker run -d \
  --name amenbank-api \
  -p 8080:8080 \
  --env-file .env \
  amenbank-api
```

---

## 🤝 Contributing

1. Create a feature branch: `git checkout -b feature/my-feature`
2. Commit your changes: `git commit -m 'feat: add my feature'`
3. Push to the branch: `git push origin feature/my-feature`
4. Open a Pull Request

### Commit Convention

| Prefix | Usage |
|---|---|
| `feat:` | New feature |
| `fix:` | Bug fix |
| `docs:` | Documentation |
| `refactor:` | Code refactoring |
| `test:` | Adding tests |
| `chore:` | Maintenance tasks |

---

## 📄 License

This project is proprietary software developed for **Amen Bank Tunisia**. All rights reserved.

---

<p align="center">
  <b>Built with ❤️ for Amen Bank</b><br>
  <sub>Spring Boot 3.4.3 · Java 22 · PostgreSQL · Redis</sub>
</p>

