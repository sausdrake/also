package com.example.autoauction.user.application;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADM-01: creates the first administrator of a fresh production database. Migrations seed only
 * the roles (V19) and the dev-profile DataInitializer never runs in production, so without this
 * nobody could administer the system.
 *
 * <p>Idempotent by design: as soon as any ROLE_ADMIN user exists it does nothing, so a forgotten
 * bootstrap variable can never create a second administrator or reset a password. It never
 * promotes an existing user - a taken username or email is refused instead.
 */
@Slf4j
@Service
public class AdminBootstrapService {

    public enum Outcome { CREATED, SKIPPED_ADMIN_EXISTS }

    static final String ADMIN_ROLE = "ROLE_ADMIN";
    static final int MIN_PASSWORD_LENGTH = 12;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** SHA-256 of the dev-profile seed passwords (SEC-08: public). Never the values themselves. */
    private static final Set<String> FORBIDDEN_PASSWORD_SHA256 = Set.of(
            "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9",
            "6b30bcc2ba1bae1269e4e588333709fb3df317edd7b00914a5ab67de70b2cf43",
            "e606e38b0d8c19b24cf0ee3808183162ea7cd63ff7912dbb22b5e803286b4446");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapService(UserRepository userRepository, RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Outcome bootstrap(String username, String email, String rawPassword) {
        if (!userRepository.findByRoleName(ADMIN_ROLE).isEmpty()) {
            log.info("Admin bootstrap skipped: an administrator already exists");
            return Outcome.SKIPPED_ADMIN_EXISTS;
        }
        String name = username == null ? "" : username.trim();
        String mail = email == null ? "" : email.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Admin bootstrap: username must be 3-50 characters of letters, digits, . _ -");
        }
        if (!EMAIL.matcher(mail).matches()) {
            throw new IllegalArgumentException("Admin bootstrap: a valid email is required");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Admin bootstrap: the password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (rawPassword.equalsIgnoreCase(name) || FORBIDDEN_PASSWORD_SHA256.contains(sha256(rawPassword))) {
            throw new IllegalArgumentException("Admin bootstrap: this password is not allowed");
        }
        if (userRepository.existsByUsername(name) || userRepository.existsByEmail(mail)) {
            throw new IllegalStateException("Admin bootstrap: the username or email is already taken - existing users are never promoted");
        }
        Role adminRole = roleRepository.findByName(ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException("Admin bootstrap: ROLE_ADMIN is missing (migration V19 not applied?)"));

        User admin = userRepository.save(new User(null, name, mail, passwordEncoder.encode(rawPassword), true, Set.of(adminRole)));
        log.info("Admin bootstrap: administrator '{}' created (id {}). Remove the ALSO_ADMIN_* variables now.", name, admin.getId());
        return Outcome.CREATED;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
