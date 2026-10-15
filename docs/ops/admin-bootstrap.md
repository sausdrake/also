# First administrator (ADM-01)

A fresh production database has the roles (migration V19) but no users. The dev-profile
`DataInitializer` never runs in production, so the first administrator is created once with a
startup bootstrap.

## Procedure

1. Choose the administrator's username, a work email and a password of **at least 12
   characters**. Generate the password in a password manager; never reuse a password from
   development or from the old public repository (those are refused).
2. On the server, add to `.env` (the file is `chmod 600`, owned by the deploy user):

   ```
   ALSO_ADMIN_BOOTSTRAP=true
   ALSO_ADMIN_USERNAME=<username>
   ALSO_ADMIN_EMAIL=<email>
   ALSO_ADMIN_PASSWORD=<password>
   ```

   and pass the four variables to the `app` service in `docker-compose.yml` for this one start.
3. Start the application. The log shows
   `Admin bootstrap: administrator '<username>' created (id N). Remove the ALSO_ADMIN_* variables now.`
   If the input is refused (weak or forbidden password, taken username or email), the
   application does not start and the log says why - fix the value and start again.
4. **Remove the four variables** from `.env` and `docker-compose.yml` and restart.
5. Log in as the administrator and check the account in the admin panel.

## Guarantees

- Idempotent: if any `ROLE_ADMIN` user already exists, the bootstrap does nothing - a forgotten
  variable can never create a second administrator or reset a password.
- An existing user is never promoted: a taken username or email is refused.
- The password is stored only as a bcrypt hash; it is never logged.

## Verification (read-only)

```sql
SELECT u.username, u.active FROM users u
JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
WHERE r.name = 'ROLE_ADMIN';
```

Exactly one row, the chosen username, `active = true`. No `admin`, `diagnostic` or `user`
accounts from the development seed exist.
