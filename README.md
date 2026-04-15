# RisiKo! Backend — Completo

## Requisiti
- Java 17+
- MySQL 8+
- Maven 3.8+

## Setup

### 1. Crea il database MySQL
```sql
CREATE DATABASE risiko;
```

### 2. Configura le credenziali
Modifica `src/main/resources/application.properties`:
```properties
spring.datasource.username=root        # tuo username MySQL
spring.datasource.password=password   # tua password MySQL
```

### 3. Avvia
```bash
mvn spring-boot:run
```
Il backend parte su http://localhost:8080
Le tabelle vengono create automaticamente da Hibernate.
I 16 obiettivi vengono caricati automaticamente al primo avvio.

---

## API Endpoints

### Autenticazione (pubbliche)
| Metodo | URL | Descrizione |
|--------|-----|-------------|
| POST | /api/auth/register | Registra nuovo utente |
| POST | /api/auth/login | Login, restituisce JWT |

### Quiz (pubbliche)
| Metodo | URL | Descrizione |
|--------|-----|-------------|
| GET | /api/quiz/domanda?userId=x&difficolta=1 | Genera domanda |
| POST | /api/quiz/risposta | Valuta risposta |
| GET | /api/quiz/stats/{userId} | Statistiche utente |
| DELETE | /api/quiz/stats/{userId} | Reset statistiche |

### Obiettivi (pubbliche)
| Metodo | URL | Descrizione |
|--------|-----|-------------|
| GET | /api/obiettivi | Tutti i 16 obiettivi |
| GET | /api/obiettivi/{id} | Singolo obiettivo |

### Profilo (🔒 richiede JWT)
| Metodo | URL | Descrizione |
|--------|-----|-------------|
| GET | /api/profilo | Profilo + statistiche utente loggato |
| POST | /api/partita | Salva partita giocata |

### Classifica (pubblica)
| Metodo | URL | Descrizione |
|--------|-----|-------------|
| GET | /api/classifica | Top giocatori per punteggio |

### Impostazioni (🔒 richiede JWT)
| Metodo | URL | Descrizione |
|--------|-----|-------------|
| GET | /api/impostazioni/{userId} | Leggi impostazioni |
| PUT | /api/impostazioni/{userId} | Salva impostazioni |

---

## Come usare il JWT

Dopo login/register ricevi:
```json
{ "token": "eyJhbGc...", "username": "mario", "avatar": "⚔️", "userId": 1 }
```

Nelle chiamate protette aggiungi l'header:
``` 
Authorization: Bearer eyJhbGc...
```

---
 
## Punteggio
- Facile corretto: 10 punti
- Medio corretto: 20 punti  
- Difficile corretto: 30 punti
- Sbagliata: 0 punti
