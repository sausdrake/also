package com.example.autoauction.notification.application;

import com.example.autoauction.deposit.application.DepositFrozenEvent;
import com.example.autoauction.deposit.application.DepositReleasedEvent;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BE-NOTIF-01 on real PostgreSQL: a notification sent from an AFTER_COMMIT
 * {@code @TransactionalEventListener} must be a persisted user_notifications row.
 *
 * <p>The existing *EventAfterCommitPgIntegrationTest classes prove the listeners are invoked exactly
 * once (NotificationService is a spy there) - none of them reads user_notifications. In the
 * AFTER_COMMIT phase the original transaction is already committed but its resources are still
 * bound, so a {@code @Transactional(REQUIRED)} call joins that finished transaction and nothing
 * commits the new INSERT. These tests drive the real production path (event published inside a
 * physically committing transaction -> DepositFrozenEventListener -> NotificationService.send) and
 * read the table.
 */
@DisplayName("BE-NOTIF-01: notifications sent after commit are persisted (PG)")
class NotificationPersistenceAfterCommitPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NotificationService notificationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @SpyBean private SimpMessagingTemplate messagingTemplateSpy;

    private Long userId;

    @BeforeEach
    void createUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "notif" + System.nanoTime();
        userId = userRepository.save(new User(null, name, name + "@test.com", "x", true, Set.of(role))).getId();
    }

    private int rows(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE user_id = ? AND type = ?",
                Integer.class, userId, type);
    }

    @Test
    @DisplayName("control: send() outside any transaction persists the row")
    void sendOutsideTransactionPersists() {
        notificationService.send(userId, "DEPOSIT_FROZEN", "t", "m");
        assertThat(rows("DEPOSIT_FROZEN")).isEqualTo(1);
    }

    @Test
    @DisplayName("an event committed by a real transaction -> the AFTER_COMMIT listener's notification is persisted")
    void afterCommitListenerNotificationIsPersisted() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publisher.publishEvent(new DepositFrozenEvent(userId, 777L, new BigDecimal("10000.00"))));

        assertThat(rows("DEPOSIT_FROZEN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message FROM user_notifications WHERE user_id = ? AND type = 'DEPOSIT_FROZEN'",
                String.class, userId)).contains("#777").contains("10000.00");
    }

    @Test
    @DisplayName("a rolled-back transaction sends no notification")
    void rolledBackTransactionSendsNothing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(new DepositFrozenEvent(userId, 778L, new BigDecimal("10000.00")));
            status.setRollbackOnly();
        });

        assertThat(rows("DEPOSIT_FROZEN")).isZero();
    }

    @Test
    @DisplayName("every AFTER_COMMIT listener kind persists: DepositReleased too")
    void releasedListenerNotificationIsPersisted() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publisher.publishEvent(new DepositReleasedEvent(userId, 779L, new BigDecimal("500.00"))));

        assertThat(rows("DEPOSIT_RELEASED")).isEqualTo(1);
    }

    /** Runs {@code work} in the AFTER_COMMIT phase of a real committed transaction - where the listeners run. */
    private void afterCommitOf(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        work.run();
                    }
                }));
    }

    @Test
    @DisplayName("adversarial: after commit, one failed notification does not lose the next one (per-recipient isolation)")
    void oneFailureDoesNotLoseTheOthers() {
        long missingUser = -userId;
        afterCommitOf(() -> {
            try {
                notificationService.sendAfterCommit(missingUser, "AUCTION_LOST", "t", "m", 1L, "AUCTION");
            } catch (RuntimeException expected) {
                // FK violation for this recipient only - the listeners catch per recipient too
            }
            notificationService.sendAfterCommit(userId, "AUCTION_COMPLETED", "t", "m", 1L, "AUCTION");
        });

        assertThat(rows("AUCTION_COMPLETED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE user_id = ?", Integer.class, missingUser)).isZero();
    }

    @Test
    @DisplayName("adversarial: the WebSocket push happens only for a committed row")
    void pushOnlyAfterTheRowCommits() {
        clearInvocations(messagingTemplateSpy);
        long missingUser = -userId;
        afterCommitOf(() -> {
            try {
                notificationService.sendAfterCommit(missingUser, "AUCTION_LOST", "t", "m", 1L, "AUCTION");
            } catch (RuntimeException expected) {
                // the row cannot commit
            }
        });
        verify(messagingTemplateSpy, never()).convertAndSendToUser(eq(String.valueOf(missingUser)), any(), any());

        afterCommitOf(() -> notificationService.sendAfterCommit(userId, "AUCTION_WON", "t", "m", 1L, "AUCTION"));
        verify(messagingTemplateSpy, times(1)).convertAndSendToUser(eq(String.valueOf(userId)), eq("/queue/notifications"), any());
        assertThat(rows("AUCTION_WON")).isEqualTo(1);
    }

    @Test
    @DisplayName("adversarial: when the push goes out, the row is already committed (visible to another connection)")
    void pushSeesACommittedRow() {
        List<Integer> visibleAtPush = new ArrayList<>();
        doAnswer(invocation -> {
            // read on a separate pooled connection: only committed rows are visible there
            visibleAtPush.add(rowsInNewConnection("AUCTION_LEADER"));
            return null;
        }).when(messagingTemplateSpy).convertAndSendToUser(eq(String.valueOf(userId)), eq("/queue/notifications"), any());

        afterCommitOf(() -> notificationService.sendAfterCommit(userId, "AUCTION_LEADER", "t", "m", 1L, "AUCTION"));

        assertThat(visibleAtPush).containsExactly(1);
    }

    private int rowsInNewConnection(String type) {
        try (var connection = jdbc.getDataSource().getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM user_notifications WHERE user_id = ? AND type = ?")) {
            statement.setLong(1, userId);
            statement.setString(2, type);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("unchanged: send() inside a live business transaction commits and rolls back with it")
    void sendInsideBusinessTransactionStaysAtomic() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notificationService.send(userId, "DIAG_ACCEPTED", "t", "m", 5L, "DIAGNOSTIC"));
        assertThat(rows("DIAG_ACCEPTED")).isEqualTo(1);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notificationService.send(userId, "DIAG_REPORT_APPROVED", "t", "m", 5L, "DIAGNOSTIC");
            status.setRollbackOnly();
        });
        assertThat(rows("DIAG_REPORT_APPROVED")).isZero();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notificationService.send(userId, "DIAG_REDIAGNOSTIC_ORDERED", "t", "m", 5L, "DIAGNOSTIC");
            throw new IllegalStateException("business failure after the notification");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows("DIAG_REDIAGNOSTIC_ORDERED")).isZero();
    }
}
