package com.example.autoauction.auth.application;

import com.example.autoauction.auth.domain.port.PasswordChangeRequirementRepository;
import com.example.autoauction.auth.domain.port.RefreshTokenRepository;
import com.example.autoauction.identity.application.VerifiedIdentityGate;
import com.example.autoauction.user.domain.AdminUserAction;
import com.example.autoauction.user.domain.AdminUserActionType;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.AdminUserActionRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * AUTH-01: password recovery without email. The user contacts support, an administrator checks the
 * caller's identity against the user's verified KYC document and resets the password to a one-time
 * temporary password, which is shown to the administrator once and handed to the user. Until the
 * user replaces it, the account can do nothing but change the password (PasswordChangeRequiredFilter),
 * so the administrator never keeps a working password of someone else.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /** Same bounds as registration (AuthService). */
    static final int MIN_PASSWORD_LENGTH = 6;
    static final int MAX_PASSWORD_LENGTH = 255;

    static final int TEMPORARY_PASSWORD_LENGTH = 16;
    /** No look-alike characters (0/O, 1/l/I): the password is read out or typed by a person. */
    private static final String TEMPORARY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordChangeRequirementRepository passwordChangeRequirementRepository;
    private final AdminUserActionRepository adminUserActionRepository;
    private final VerifiedIdentityGate verifiedIdentityGate;
    private final SecureRandom random = new SecureRandom();

    public PasswordService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordChangeRequirementRepository passwordChangeRequirementRepository,
                           AdminUserActionRepository adminUserActionRepository,
                           VerifiedIdentityGate verifiedIdentityGate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordChangeRequirementRepository = passwordChangeRequirementRepository;
        this.adminUserActionRepository = adminUserActionRepository;
        this.verifiedIdentityGate = verifiedIdentityGate;
    }

    /**
     * Replaces the user's password with a fresh temporary one and returns it (the only place it
     * ever appears in clear). Every session of the user ends: refresh tokens are revoked, and any
     * access token still in circulation can only reach the change-password endpoint.
     */
    @Transactional
    public String resetByAdmin(Long userId, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        if (userId.equals(actingAdminId)) {
            throw new IllegalArgumentException("Свой пароль администратор меняет сам, а не сбросом");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        if (!verifiedIdentityGate.isVerified(userId)) {
            // The only identity check available without email: the caller is compared with the
            // verified document. Without KYC nobody can be sure who is asking.
            throw new IllegalStateException("Сброс пароля возможен только для пользователя с подтверждённой личностью (KYC)");
        }

        String temporaryPassword = generateTemporaryPassword();
        userRepository.save(withPasswordHash(user, passwordEncoder.encode(temporaryPassword)));
        refreshTokenRepository.revokeAllUserTokens(user);
        OffsetDateTime now = OffsetDateTime.now();
        passwordChangeRequirementRepository.require(userId, actingAdminId, now);
        adminUserActionRepository.append(AdminUserAction.of(userId, actingAdminId, AdminUserActionType.PASSWORD_RESET, null, now));
        log.info("Password of user {} reset by admin {}", userId, actingAdminId);
        return temporaryPassword;
    }

    /** AUTH-TG-UX-01: whether the account has a password at all (Telegram-only accounts do not). */
    @Transactional(readOnly = true)
    public boolean hasOwnPassword(Long userId) {
        return userRepository.findById(userId).map(PasswordService::hasPassword)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
    }

    private static boolean hasPassword(User user) {
        String hash = user.getPasswordHash();
        return hash != null && !hash.isBlank() && !ExternalIdentityResolver.NO_PASSWORD.equals(hash);
    }

    /**
     * The user sets a new password, proving the current one. Clears the change requirement and ends
     * every session (all refresh tokens revoked): the user signs in again with the new password.
     */
    @Transactional
    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        if (!hasPassword(user)) {
            // AUTH-TG-UX-01: a Telegram-only account has no password to prove; setting a first one needs its own
            // secure design (open product decision P4), so it is refused plainly instead of "wrong password".
            throw new IllegalStateException("У этой учётной записи нет пароля: вход выполняется через Telegram");
        }
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Текущий пароль указан неверно");
        }
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("Новый пароль не может быть пустым");
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH || newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Новый пароль должен быть от " + MIN_PASSWORD_LENGTH + " до "
                    + MAX_PASSWORD_LENGTH + " символов");
        }
        if (newPassword.equals(currentPassword)) {
            throw new IllegalArgumentException("Новый пароль должен отличаться от текущего");
        }

        userRepository.save(withPasswordHash(user, passwordEncoder.encode(newPassword)));
        refreshTokenRepository.revokeAllUserTokens(user);
        if (passwordChangeRequirementRepository.isRequired(userId)) {
            passwordChangeRequirementRepository.clear(userId);
        }
        log.info("User {} changed their password", userId);
    }

    public boolean isPasswordChangeRequired(Long userId) {
        return passwordChangeRequirementRepository.isRequired(userId);
    }

    private String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
        for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
            sb.append(TEMPORARY_PASSWORD_ALPHABET.charAt(random.nextInt(TEMPORARY_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Keeps everything else - active flag, roles and the trust decision - exactly as it was. */
    private static User withPasswordHash(User user, String passwordHash) {
        return new User(user.getId(), user.getUsername(), user.getEmail(), passwordHash, user.isActive(),
                user.getRoles(), user.isTrusted(), user.getVerifiedByAdminId(), user.getVerifiedAt(),
                user.getCreatedAt());
    }
}
