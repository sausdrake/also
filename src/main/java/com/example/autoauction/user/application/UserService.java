package com.example.autoauction.user.application;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public UserService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        log.info("UserService initialized.");
    }

    public Optional<User> findByUsername(String username) { return userRepository.findByUsername(username); }
    public Optional<User> findByEmail(String email) { return userRepository.findByEmail(email); }
    public Optional<User> getUser(Long id) { return userRepository.findById(id); }

    public User createUser(User user) {
        log.info("Creating user: {}", user.getUsername());
        return userRepository.save(user);
    }

    public List<User> listUsers() { return userRepository.findAll(); }

    public List<User> listUsersByRole(String roleName) {
        return userRepository.findByRoleName(roleName);
    }

    public List<User> listUsersByStatus(boolean active) {
        return userRepository.findByActive(active);
    }

    @Transactional
    public User blockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        User blocked = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), false, user.getRoles());
        User saved = userRepository.save(blocked);
        log.info("User {} blocked", userId);
        return saved;
    }

    @Transactional
    public User unblockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        User unblocked = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), true, user.getRoles());
        User saved = userRepository.save(unblocked);
        log.info("User {} unblocked", userId);
        return saved;
    }

    @Transactional
    public User setUserRole(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Роль не найдена: " + roleName));
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        User updated = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), user.isActive(), roles);
        User saved = userRepository.save(updated);
        log.info("User {} role set to {}", userId, roleName);
        return saved;
    }

    public long countUsers() { return userRepository.findAll().size(); }

    public void deleteUser(Long id) { userRepository.deleteById(id); }
}

