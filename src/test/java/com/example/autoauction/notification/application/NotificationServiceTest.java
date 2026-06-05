package com.example.autoauction.notification.application;

import com.example.autoauction.notification.domain.Notification;
import com.example.autoauction.notification.infrastructure.persistence.JpaNotificationEntity;
import com.example.autoauction.notification.infrastructure.persistence.SpringDataNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты NotificationService")
class NotificationServiceTest {

    @Mock
    private SpringDataNotificationRepository repository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private NotificationService service;

    private JpaNotificationEntity entity;

    @BeforeEach
    void setUp() {
        entity = new JpaNotificationEntity();
        entity.setId(1L);
        entity.setUserId(42L);
        entity.setType("BID_OUTBID");
        entity.setTitle("Вас обогнали");
        entity.setMessage("Новая цена: 150000");
        entity.setRead(false);
        entity.setCreatedAt(LocalDateTime.now());
    }

    @Nested
    @DisplayName("send()")
    class SendTests {

        @Test
        @DisplayName("Сохраняет уведомление и отправляет по WebSocket")
        void send_SavesAndPushes() {
            when(repository.save(any())).thenReturn(entity);

            service.send(42L, "BID_OUTBID", "Вас обогнали", "Новая цена: 150000");

            verify(repository).save(any(JpaNotificationEntity.class));
            verify(messagingTemplate).convertAndSend(eq("/topic/user/42"), any(Notification.class));
        }

        @Test
        @DisplayName("Не бросает исключение если WebSocket упал")
        void send_DoesNotThrowOnWsError() {
            when(repository.save(any())).thenReturn(entity);
            doThrow(new RuntimeException("WS error"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            // Не должно выбрасывать
            service.send(42L, "TYPE", "Title", "Message");
        }
    }

    @Nested
    @DisplayName("getForUser()")
    class GetForUserTests {

        @Test
        @DisplayName("Возвращает страницу уведомлений пользователя")
        void getForUser_ReturnsPage() {
            Page<JpaNotificationEntity> page = new PageImpl<>(List.of(entity));
            when(repository.findByUserIdOrderByCreatedAtDesc(eq(42L), any()))
                    .thenReturn(page);

            Page<Notification> result = service.getForUser(42L, PageRequest.of(0, 20));

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getUserId()).isEqualTo(42L);
            assertThat(result.getContent().get(0).getType()).isEqualTo("BID_OUTBID");
        }
    }

    @Nested
    @DisplayName("countUnread()")
    class CountUnreadTests {

        @Test
        @DisplayName("Возвращает количество непрочитанных")
        void countUnread_ReturnsCount() {
            when(repository.countByUserIdAndReadFalse(42L)).thenReturn(3L);

            long count = service.countUnread(42L);

            assertThat(count).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("markRead()")
    class MarkReadTests {

        @Test
        @DisplayName("Помечает уведомление как прочитанное если оно принадлежит пользователю")
        void markRead_MarksWhenOwner() {
            when(repository.findById(1L)).thenReturn(Optional.of(entity));

            service.markRead(1L, 42L);

            assertThat(entity.isRead()).isTrue();
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("Игнорирует если уведомление принадлежит другому пользователю")
        void markRead_IgnoresWhenNotOwner() {
            when(repository.findById(1L)).thenReturn(Optional.of(entity));

            service.markRead(1L, 99L); // чужой userId

            assertThat(entity.isRead()).isFalse();
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markAllRead()")
    class MarkAllReadTests {

        @Test
        @DisplayName("Вызывает bulk-update для всех уведомлений пользователя")
        void markAllRead_CallsBulkUpdate() {
            when(repository.markAllReadByUserId(42L)).thenReturn(5);

            service.markAllRead(42L);

            verify(repository).markAllReadByUserId(42L);
        }
    }
}
