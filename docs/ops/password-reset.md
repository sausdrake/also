# Password reset by an administrator (AUTH-01)

There is no email yet (PROC-04), so a user who forgot their password asks support. An
administrator resets the password to a one-time temporary password **only after checking who is
asking**. This is the interim procedure; a reset link by email replaces it once email exists.

## Who can be reset

- Only a user whose identity is **verified (KYC)**. The system refuses everyone else: without a
  verified document there is nothing to check the caller against. Such a user waits for email
  recovery or registers again. (Product Owner: confirm this rule, or define another identity check
  for users without KYC.)
- Not yourself: an administrator changes their own password in the profile.

## Procedure

1. The user contacts support and names their username.
2. In **Admin → Users**, open the user (Просмотр) and compare the caller with the verified KYC
   record (**Admin → Identity**): full name, document number, and the face on a live video call
   against the selfie and document photo. Any doubt - stop, do not reset.
3. Press **Сбросить пароль** and confirm. The temporary password (16 characters) is shown
   **once**; it is not stored anywhere in clear.
4. Give it to the user over the same verified channel (in person or on the video call). Never by
   an unverified message, never to a third party.
5. The user signs in with it. Until they set their own password, every action except the password
   change is refused, so nobody - including the administrator - keeps a working password of theirs.

## What the reset does

- replaces the password; the old one stops working at once;
- revokes every refresh token: all the user's sessions end;
- an access token still in circulation can only reach the password change;
- writes `PASSWORD_RESET` with the acting administrator and time to the user's journal
  (`GET /api/v1/admin/users/{id}/actions`).

A repeated reset is allowed and journaled again; frequent resets of one account are a reason to
investigate.
