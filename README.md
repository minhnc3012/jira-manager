# ⚡ Jira Manager v2 – Vaadin 25

Ứng dụng quản lý Jira ticket với **dual authentication**: Email/Password + Google OAuth2, có account linking tự động.

---

## 🏗️ Tech Stack

| | |
|---|---|
| Framework | Vaadin 25 + Spring Boot 4 |
| Language | Java 21 |
| Auth | Spring Security + Google OAuth2 + BCrypt |
| Database | H2 (dev) / PostgreSQL (prod) |
| Jira | REST API v3 + Basic Auth |

---

## 🗄️ Database Schema

```
app_users
├── id, email (unique), first_name, last_name, phone_number
├── password_hash (null nếu chỉ dùng OAuth2)
└── enabled, created_at

auth_providers
├── id, user_id (FK), provider ("local" | "google")
├── provider_id (Google UID)
└── provider_email
```

Một user có thể có cả 2 rows trong `auth_providers` → login bằng cả 2 cách.

---

## 🔑 Setup Google OAuth2

1. [Google Cloud Console](https://console.cloud.google.com/) → APIs & Services → Credentials
2. Create **OAuth 2.0 Client ID** (Web application)
3. Authorized redirect URI: `http://localhost:8080/login/oauth2/code/google`
4. Copy Client ID & Secret

---

## 🔑 Setup Jira API Token

[Atlassian API Tokens](https://id.atlassian.com/manage-profile/security/api-tokens) → Create API token

---

## ⚙️ Cấu hình

Dùng environment variables (khuyến nghị):

```bash
export GOOGLE_CLIENT_ID=your-client-id
export GOOGLE_CLIENT_SECRET=your-client-secret
export JIRA_BASE_URL=https://your-domain.atlassian.net
export JIRA_EMAIL=your-email@example.com
export JIRA_API_TOKEN=your-api-token
```

Hoặc sửa trực tiếp `src/main/resources/application.properties`.

---

## 🚀 Chạy

```bash
./mvnw spring-boot:run
```

Mở: http://localhost:8080

---

## 🔐 Account Linking Flow

```
User đăng nhập Google với user@gmail.com
       ↓
Email đã tồn tại (đăng ký bằng password)?
       ↓ Có
Hiện dialog: "Nhập mật khẩu để liên kết"
       ↓ Xác nhận
Link 2 accounts → login bằng cả email/pass và Google
```

---

## 📁 Cấu trúc

```
src/main/java/com/jiramanager/
├── Application.java
├── model/
│   ├── AppUser.java          # User entity
│   ├── AuthProvider.java     # Provider (local/google)
│   └── JiraTicket.java       # Jira ticket DTO
├── repository/
│   ├── UserRepository.java
│   └── AuthProviderRepository.java
├── service/
│   ├── UserService.java      # Registration, login, account linking
│   ├── JiraService.java      # Jira REST API
│   └── SessionUserService.java
├── security/
│   ├── SecurityConfig.java
│   └── CustomOAuth2UserService.java  # Google callback handler
└── views/
    ├── LoginView.java         # Dual login + register + linking dialog
    └── MainView.java          # Jira ticket grid
```

---

## 🗄️ Production – đổi sang PostgreSQL

Thêm dependency trong pom.xml:
```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

Sửa application.properties:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/jiramanager
spring.datasource.username=postgres
spring.datasource.password=yourpassword
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```
