package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса управления аукционами для админа")
class AdminAuctionServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private VehicleService vehicleService;

    @Mock
    private AuctionValidator auctionValidator;

    @Mock
    private AuctionFinalizer auctionFinalizer;

    @InjectMocks
    private AdminAuctionService adminAuctionService;

    private AuctionCreateRequest validRequest;
    private VehicleResponse approvedVehicle;
    private Auction auction;
    private final Long adminId = 1L;
    private final Long diagnosticId = 16L;
    private final String diagnosticName = "diagnostic";

    @BeforeEach
    void setUp() {
        log.debug("========== НАСТРОЙКА ТЕСТОВ ==========");

        OffsetDateTime startTime = OffsetDateTime.now().plusDays(1);
        OffsetDateTime endTime = startTime.plusDays(7);
        OffsetDateTime now = OffsetDateTime.now();

        validRequest = new AuctionCreateRequest(
                1L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                startTime,
                endTime, null, null
        );

        approvedVehicle = new VehicleResponse(
                1L, "BMW", "M5", 2023, "WBSDE91070CZ12345",
                "A123BC", VehicleType.SEDAN, "black", 10000, 4.4,
                FuelType.PETROL, "auto", "Test car",
                VehicleStatus.APPROVED, diagnosticId, diagnosticName,
                adminId, "admin", null, now, now, now.minusDays(1), now
        );

        auction = new Auction(
                1L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                startTime,
                endTime,
                adminId
        );
        auction.setId(1L);
    }

    @Nested
    @DisplayName("Тесты создания аукциона")
    class CreateAuctionTests {

        @Test
        @DisplayName("Успешное создание аукциона с валидными данными")
        void createAuction_Success() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.createAuction(validRequest, adminId);

            // then
            assertAll("Проверка созданного аукциона",
                    () -> assertNotNull(response),
                    () -> assertEquals(1L, response.vehicleId()),
                    () -> assertEquals(new BigDecimal("1000000"), response.startingPrice()),
                    () -> assertEquals(AuctionStatus.CREATED, response.status())
            );
            verify(auctionRepository, times(1)).save(any(Auction.class));
            verify(auctionRepository, times(1)).existsByVehicleIdAndStatusIn(eq(1L), anyList());
            verify(auctionValidator, times(1)).validateBasicRequest(any());
            verify(auctionValidator, times(1)).validateBusinessRules(any(), any());
        }

        @ParameterizedTest
        @MethodSource("invalidPriceProvider")
        @DisplayName("Создание аукциона с невалидными ценами")
        void createAuction_WithInvalidPrices_ThrowsException(BigDecimal startingPrice,
                                                             BigDecimal reservePrice,
                                                             BigDecimal buyNowPrice,
                                                             String expectedMessage) {
            // given
            AuctionCreateRequest invalidRequest = new AuctionCreateRequest(
                    1L, startingPrice, reservePrice, buyNowPrice,
                    new BigDecimal("10000"),
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), null, null
            );

            // Мокаем ВСЕ вызовы, которые происходят до validateBusinessRules
            doNothing().when(auctionValidator).validateBasicRequest(invalidRequest);
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);

            // Мокаем валидатор, чтобы он выбрасывал исключение
            doThrow(new IllegalArgumentException(expectedMessage))
                    .when(auctionValidator).validateBusinessRules(eq(invalidRequest), any());

            // when & then
            Exception exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(invalidRequest, adminId)
            );
            assertTrue(exception.getMessage().contains(expectedMessage));
            verify(auctionRepository, never()).save(any(Auction.class));
            verify(auctionRepository, never()).existsByVehicleIdAndStatusIn(anyLong(), anyList());
        }

        static Stream<Arguments> invalidPriceProvider() {
            return Stream.of(
                    Arguments.of(new BigDecimal("-1000"), new BigDecimal("1000"), new BigDecimal("2000"), "Стартовая цена должна быть больше 0"),
                    Arguments.of(new BigDecimal("0"), new BigDecimal("1000"), new BigDecimal("2000"), "Стартовая цена должна быть больше 0"),
                    Arguments.of(new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("2000"), "Резервная цена не может быть меньше стартовой"),
                    Arguments.of(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("900"), "Цена мгновенной покупки должна быть больше стартовой"),
                    Arguments.of(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1000"), "Цена мгновенной покупки должна быть больше стартовой")
            );
        }

        @ParameterizedTest
        @MethodSource("invalidStepProvider")
        @DisplayName("Создание аукциона с невалидным шагом ставки")
        void createAuction_WithInvalidStep_ThrowsException(BigDecimal minBidStep, String expectedMessage) {
            // given
            AuctionCreateRequest invalidRequest = new AuctionCreateRequest(
                    1L, new BigDecimal("1000000"), new BigDecimal("1200000"),
                    new BigDecimal("1500000"), minBidStep,
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), null, null
            );

            // Мокаем ВСЕ вызовы, которые происходят до validateBusinessRules
            doNothing().when(auctionValidator).validateBasicRequest(invalidRequest);
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);

            // Мокаем валидатор, чтобы он выбрасывал исключение
            doThrow(new IllegalArgumentException(expectedMessage))
                    .when(auctionValidator).validateBusinessRules(eq(invalidRequest), any());

            // when & then
            Exception exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(invalidRequest, adminId)
            );
            assertTrue(exception.getMessage().contains(expectedMessage));
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        static Stream<Arguments> invalidStepProvider() {
            return Stream.of(
                    Arguments.of(new BigDecimal("0"), "Шаг ставки должен быть больше 0"),
                    Arguments.of(new BigDecimal("-100"), "Шаг ставки должен быть больше 0"),
                    Arguments.of(new BigDecimal("5000"), "Шаг ставки не может быть меньше 1% от стартовой цены")
            );
        }

        @ParameterizedTest
        @MethodSource("invalidTimeProvider")
        @DisplayName("Создание аукциона с невалидным временем")
        void createAuction_WithInvalidTime_ThrowsException(OffsetDateTime startTime,
                                                           OffsetDateTime endTime,
                                                           String expectedMessage) {
            // given
            AuctionCreateRequest invalidRequest = new AuctionCreateRequest(
                    1L, new BigDecimal("1000000"), new BigDecimal("1200000"),
                    new BigDecimal("1500000"), new BigDecimal("10000"),
                    startTime, endTime, null, null
            );

            // Мокаем ВСЕ вызовы, которые происходят до validateBusinessRules
            doNothing().when(auctionValidator).validateBasicRequest(invalidRequest);
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);

            // Мокаем валидатор, чтобы он выбрасывал исключение
            doThrow(new IllegalArgumentException(expectedMessage))
                    .when(auctionValidator).validateBusinessRules(eq(invalidRequest), any());

            // when & then
            Exception exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(invalidRequest, adminId)
            );
            assertTrue(exception.getMessage().contains(expectedMessage));
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        static Stream<Arguments> invalidTimeProvider() {
            OffsetDateTime now = OffsetDateTime.now();
            return Stream.of(
                    Arguments.of(now.minusDays(1), now.plusDays(6), "Время начала не может быть в прошлом"),
                    Arguments.of(now.plusDays(1), now, "Время окончания должно быть позже времени начала"),
                    Arguments.of(now.plusHours(1), now.plusHours(1).plusMinutes(30), "Аукцион должен длиться минимум 1 час"),
                    Arguments.of(now.plusMinutes(30), now.plusHours(2), "Аукцион должен быть создан минимум за 1 час до начала"),
                    Arguments.of(now.plusDays(35), now.plusDays(40), "Аукцион не может длиться больше 30 дней")
            );
        }

        @Test
        @DisplayName("Создание аукциона с null vehicleId")
        void createAuction_WithNullVehicleId_ThrowsException() {
            // given
            AuctionCreateRequest nullVehicleRequest = new AuctionCreateRequest(
                    null, new BigDecimal("1000000"), new BigDecimal("1200000"),
                    new BigDecimal("1500000"), new BigDecimal("10000"),
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), null, null
            );

            // Мокаем валидатор, чтобы он выбрасывал исключение
            doThrow(new NullPointerException("ID автомобиля не может быть null"))
                    .when(auctionValidator).validateBasicRequest(nullVehicleRequest);

            // when & then
            assertThrows(NullPointerException.class, () ->
                    adminAuctionService.createAuction(nullVehicleRequest, adminId)
            );
            verify(auctionRepository, never()).save(any(Auction.class));
            verify(vehicleService, never()).getVehicleDetails(any());
        }

        @ParameterizedTest
        @EnumSource(value = VehicleStatus.class, names = {"APPROVED"}, mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Создание аукциона с автомобилем не в статусе APPROVED")
        void createAuction_WithVehicleNotApproved_ThrowsException(VehicleStatus status) {
            // given
            OffsetDateTime now = OffsetDateTime.now();
            VehicleResponse notApprovedVehicle = new VehicleResponse(
                    1L, "BMW", "M5", 2023, "WBSDE91070CZ12345",
                    "A123BC", VehicleType.SEDAN, "black", 10000, 4.4,
                    FuelType.PETROL, "auto", "Test car",
                    status, diagnosticId, diagnosticName,
                    null, null, null, now, now, null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(validRequest);
            when(vehicleService.getVehicleDetails(1L)).thenReturn(notApprovedVehicle);

            // when & then
            Exception exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(validRequest, adminId)
            );
            assertTrue(exception.getMessage().contains("Требуется статус APPROVED"));
            verify(auctionRepository, never()).existsByVehicleIdAndStatusIn(anyLong(), anyList());
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @ParameterizedTest
        @EnumSource(value = AuctionStatus.class, names = {"ACTIVE", "CREATED", "SOLD"})
        @DisplayName("Создание аукциона при существующем аукционе в разных статусах")
        void createAuction_WithExistingAuction_ThrowsException(AuctionStatus existingStatus) {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(true);
            when(auctionRepository.findStatusesByVehicleId(1L)).thenReturn(List.of(existingStatus));

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(validRequest, adminId)
            );

            String expectedMessage = switch (existingStatus) {
                case ACTIVE -> "Автомобиль уже участвует в активном аукционе";
                case CREATED -> "Для этого автомобиля уже создан аукцион";
                case SOLD -> "Автомобиль уже был продан на аукционе";
                default -> "";
            };
            assertTrue(exception.getMessage().contains(expectedMessage));
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Создание аукциона с DataIntegrityViolationException")
        void createAuction_WithDataIntegrityViolation_ThrowsException() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionRepository.save(any(Auction.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry for vehicle_id"));

            // when & then
            assertThrows(DataIntegrityViolationException.class, () ->
                    adminAuctionService.createAuction(validRequest, adminId)
            );
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }
    }

    @Nested
    @DisplayName("Тесты управления статусами аукциона")
    class AuctionStatusTests {

        @Test
        @DisplayName("Запуск аукциона со статусом CREATED")
        void startAuction_WhenCreated_ShouldSucceed() {
            // given
            when(auctionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(auction));
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.startAuction(1L, adminId);

            // then
            assertEquals(AuctionStatus.ACTIVE, response.status());
            verify(auctionRepository, times(1)).findByIdWithLock(1L);
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }

        @ParameterizedTest
        @EnumSource(value = AuctionStatus.class, names = {"CREATED"}, mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Запуск аукциона с неподходящим статусом")
        void startAuction_WithInvalidStatus_ThrowsException(AuctionStatus status) {
            // given
            Auction auctionWithInvalidStatus = new Auction(
                    1L,
                    new BigDecimal("1000000"),
                    new BigDecimal("1200000"),
                    new BigDecimal("1500000"),
                    new BigDecimal("10000"),
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7),
                    adminId
            );
            auctionWithInvalidStatus.setId(1L);
            auctionWithInvalidStatus.setStatus(status);

            when(auctionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(auctionWithInvalidStatus));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    adminAuctionService.startAuction(1L, adminId)
            );

            String message = exception.getMessage();

            // Для разных статусов ожидаем разные сообщения
            if (status == AuctionStatus.ACTIVE) {
                assertEquals("Аукцион уже запущен", message);
            } else {
                assertTrue(message.contains("Невозможно запустить аукцион в статусе"),
                        String.format("Expected message to contain 'Невозможно запустить аукцион в статусе', but was: '%s'", message));
                assertTrue(message.contains(status.name()),
                        String.format("Expected message to contain status '%s', but was: '%s'", status.name(), message));
            }

            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Запуск уже запущенного аукциона")
        void startAuction_WhenAlreadyActive_ThrowsException() {
            // given
            auction.setStatus(AuctionStatus.ACTIVE);
            when(auctionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(auction));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    adminAuctionService.startAuction(1L, adminId)
            );
            assertEquals("Аукцион уже запущен", exception.getMessage());
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Отмена созданного аукциона — делегируется AuctionFinalizer (разморозка депозитов)")
        void cancelAuction_WhenCreated_ShouldSucceed() {
            // given
            when(auctionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(auction));
            when(auctionFinalizer.cancelAuction(any(Auction.class))).thenAnswer(inv -> {
                Auction a = inv.getArgument(0);
                a.cancel();
                return true;
            });

            // when
            AuctionResponse response = adminAuctionService.cancelAuction(1L, adminId);

            // then
            assertEquals(AuctionStatus.CANCELLED, response.status());
            verify(auctionRepository, times(1)).findByIdWithLock(1L);
            verify(auctionFinalizer, times(1)).cancelAuction(auction);
        }

        @Test
        @DisplayName("Отмена активного аукциона — делегируется AuctionFinalizer (разморозка депозитов)")
        void cancelAuction_WhenActive_ShouldSucceed() {
            // given
            auction.start();
            when(auctionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(auction));
            when(auctionFinalizer.cancelAuction(any(Auction.class))).thenAnswer(inv -> {
                Auction a = inv.getArgument(0);
                a.cancel();
                return true;
            });

            // when
            AuctionResponse response = adminAuctionService.cancelAuction(1L, adminId);

            // then
            assertEquals(AuctionStatus.CANCELLED, response.status());
            verify(auctionRepository, times(1)).findByIdWithLock(1L);
            verify(auctionFinalizer, times(1)).cancelAuction(auction);
        }

        @Test
        @DisplayName("D-1: отмена активного аукциона со ставками передаёт живой объект в AuctionFinalizer.cancelAuction (не голое auction.cancel())")
        void cancelAuction_WhenActiveWithBids_DelegatesToFinalizerNotBareCancel() {
            // given: ранее cancelAuction() вызывал только auction.cancel()+save(), не размораживая
            // депозиты участников. Регрессия должна гарантировать делегирование в AuctionFinalizer,
            // единственное место, где реализована разморозка всех участников при отмене.
            auction.start();
            auction.setTotalBids(3);
            when(auctionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(auction));
            when(auctionFinalizer.cancelAuction(auction)).thenReturn(true);

            // when
            adminAuctionService.cancelAuction(1L, adminId);

            // then: AdminAuctionService больше не отменяет и не сохраняет аукцион напрямую -
            // это делегировано AuctionFinalizer, которое отвечает за разморозку депозитов.
            verify(auctionFinalizer, times(1)).cancelAuction(auction);
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @ParameterizedTest
        @EnumSource(value = AuctionStatus.class, names = {"COMPLETED", "SOLD", "EXPIRED", "CANCELLED"})
        @DisplayName("Отмена завершенного аукциона")
        void cancelAuction_WhenCompleted_ThrowsException(AuctionStatus status) {
            // given
            auction.setStatus(status);
            when(auctionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(auction));

            // when & then
            assertThrows(IllegalStateException.class, () ->
                    adminAuctionService.cancelAuction(1L, adminId)
            );
            verify(auctionRepository, never()).save(any(Auction.class));
            verify(auctionFinalizer, never()).cancelAuction(any());
        }

        @Test
        @DisplayName("Запуск несуществующего аукциона")
        void startAuction_NotFound_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.startAuction(999L, adminId)
            );
            assertTrue(exception.getMessage().contains("Аукцион с ID 999 не найден"));
        }
    }

    @Nested
    @DisplayName("Тесты получения аукционов")
    class GetAuctionsTests {

        @Test
        @DisplayName("Получение всех аукционов")
        void getAllAuctions_ShouldReturnList() {
            // given
            when(auctionRepository.findAll()).thenReturn(List.of(auction));

            // when
            List<AuctionResponse> responses = adminAuctionService.getAllAuctions();

            // then
            assertEquals(1, responses.size());
            verify(auctionRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Получение пустого списка аукционов")
        void getAllAuctions_WhenEmpty_ShouldReturnEmptyList() {
            // given
            when(auctionRepository.findAll()).thenReturn(List.of());

            // when
            List<AuctionResponse> responses = adminAuctionService.getAllAuctions();

            // then
            assertTrue(responses.isEmpty());
            verify(auctionRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Получение аукциона по существующему ID")
        void getAuction_ExistingId_ShouldReturnAuction() {
            // given
            when(auctionRepository.findById(1L)).thenReturn(Optional.of(auction));

            // when
            AuctionResponse response = adminAuctionService.getAuction(1L);

            // then
            assertNotNull(response);
            assertEquals(1L, response.id());
            verify(auctionRepository, times(1)).findById(1L);
        }

        @ParameterizedTest
        @ValueSource(longs = {-1L, 0L, 999L, Long.MAX_VALUE})
        @DisplayName("Получение аукциона по несуществующему ID")
        void getAuction_NonExistingId_ThrowsException(Long invalidId) {
            // given
            when(auctionRepository.findById(invalidId)).thenReturn(Optional.empty());

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.getAuction(invalidId)
            );
            assertTrue(exception.getMessage().contains("Аукцион с ID " + invalidId + " не найден"));
            verify(auctionRepository, times(1)).findById(invalidId);
        }

        @ParameterizedTest
        @EnumSource(AuctionStatus.class)
        @DisplayName("Получение аукционов по всем статусам")
        void getAuctionsByStatus_ShouldReturnList(AuctionStatus status) {
            // given
            when(auctionRepository.findByStatus(status)).thenReturn(List.of(auction));

            // when
            List<AuctionResponse> responses = adminAuctionService.getAuctionsByStatus(status.name());

            // then
            assertEquals(1, responses.size());
            verify(auctionRepository, times(1)).findByStatus(status);
        }

        @Test
        @DisplayName("Получение аукционов по несуществующему статусу")
        void getAuctionsByStatus_WithInvalidStatus_ThrowsException() {
            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.getAuctionsByStatus("INVALID_STATUS")
            );
            assertTrue(exception.getMessage().contains("Неверный статус аукциона: INVALID_STATUS"));
            verify(auctionRepository, never()).findByStatus(any());
        }

        @Test
        @DisplayName("Получение аукционов по ID автомобиля")
        void getAuctionsByVehicleId_ShouldReturnList() {
            // given
            when(auctionRepository.findByVehicleId(1L)).thenReturn(List.of(auction));

            // when
            List<AuctionResponse> responses = adminAuctionService.getAuctionsByVehicleId(1L);

            // then
            assertEquals(1, responses.size());
            verify(auctionRepository, times(1)).findByVehicleId(1L);
        }

        @Test
        @DisplayName("Получение аукционов по ID автомобиля без аукционов")
        void getAuctionsByVehicleId_WhenEmpty_ShouldReturnEmptyList() {
            // given
            when(auctionRepository.findByVehicleId(999L)).thenReturn(List.of());

            // when
            List<AuctionResponse> responses = adminAuctionService.getAuctionsByVehicleId(999L);

            // then
            assertTrue(responses.isEmpty());
            verify(auctionRepository, times(1)).findByVehicleId(999L);
        }
    }

    @Nested
    @DisplayName("Тесты валидации времени")
    class TimeValidationTests {

        @Test
        @DisplayName("Время начала в прошлом должно вызывать исключение")
        void startTimeInPast_ShouldThrowException() {
            // given
            OffsetDateTime pastTime = OffsetDateTime.now().minusHours(2);
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("1000000"), new BigDecimal("1200000"),
                    new BigDecimal("1500000"), new BigDecimal("10000"),
                    pastTime, pastTime.plusDays(7), null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(request);
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            doThrow(new IllegalArgumentException("Время начала не может быть в прошлом"))
                    .when(auctionValidator).validateBusinessRules(eq(request), any());

            // when & then
            Exception exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(request, adminId)
            );
            assertTrue(exception.getMessage().contains("Время начала не может быть в прошлом"));
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Время окончания раньше времени начала должно вызывать исключение")
        void endTimeBeforeStartTime_ShouldThrowException() {
            // given
            OffsetDateTime startTime = OffsetDateTime.now().plusDays(1);
            OffsetDateTime endTime = startTime.minusHours(1);
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("1000000"), new BigDecimal("1200000"),
                    new BigDecimal("1500000"), new BigDecimal("10000"),
                    startTime, endTime, null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(request);
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            doThrow(new IllegalArgumentException("Время окончания должно быть позже времени начала"))
                    .when(auctionValidator).validateBusinessRules(eq(request), any());

            // when & then
            Exception exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(request, adminId)
            );
            assertTrue(exception.getMessage().contains("Время окончания должно быть позже времени начала"));
            verify(auctionRepository, never()).save(any(Auction.class));
        }
    }


    @Nested
    @DisplayName("Тесты производительности")
    class PerformanceTests {

        @Test
        @DisplayName("Получение 1000 аукционов должно выполняться быстро")
        void getAuctions_Performance_ShouldBeFast() {
            // given
            List<Auction> manyAuctions = IntStream.range(0, 1000)
                    .mapToObj(i -> {
                        Auction newAuction = new Auction(
                                (long) i,
                                new BigDecimal("1000000"),
                                new BigDecimal("1200000"),
                                new BigDecimal("1500000"),
                                new BigDecimal("10000"),
                                OffsetDateTime.now().plusDays(1),
                                OffsetDateTime.now().plusDays(7),
                                adminId
                        );
                        newAuction.setId((long) i);
                        return newAuction;
                    })
                    .toList();

            when(auctionRepository.findAll()).thenReturn(manyAuctions);

            // when
            long startTime = System.currentTimeMillis();
            List<AuctionResponse> responses = adminAuctionService.getAllAuctions();
            long duration = System.currentTimeMillis() - startTime;

            // then
            assertEquals(1000, responses.size());
            assertTrue(duration < 500, "Получение 1000 аукционов заняло " + duration + "мс, что больше 500мс");
            verify(auctionRepository, times(1)).findAll();
        }
    }

    @Nested
    @DisplayName("Тесты граничных значений")
    class BoundaryTests {

        @Test
        @DisplayName("Создание аукциона с минимальной стартовой ценой")
        void createAuction_WithMinimumStartingPrice_Success() {
            // given
            BigDecimal minPrice = new BigDecimal("0.01");
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, minPrice, null, null,
                    new BigDecimal("10000"),
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then
            assertNotNull(response);
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }

        @Test
        @DisplayName("Создание аукциона с резервной ценой равной стартовой")
        void createAuction_ReservePriceEqualsStartingPrice_Success() {
            // given
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L,
                    new BigDecimal("1000000"),
                    new BigDecimal("1000000"), // равна стартовой
                    null,
                    new BigDecimal("10000"),
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then
            assertNotNull(response);
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }

        @Test
        @DisplayName("Создание аукциона с минимальным шагом ставки")
        void createAuction_WithMinimumBidStep_Success() {
            // given
            BigDecimal minBidStep = new BigDecimal("10000"); // 1% от 1,000,000
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("1000000"), null, null,
                    minBidStep,
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then
            assertNotNull(response);
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }

        @Test
        @DisplayName("Создание аукциона с минимальной длительностью")
        void createAuction_WithMinimumDuration_Success() {
            // given
            OffsetDateTime startTime = OffsetDateTime.now().plusHours(2);
            OffsetDateTime endTime = startTime.plusHours(1); // Ровно 1 час

            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"),
                    startTime, endTime, null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then
            assertNotNull(response);
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }

        @Test
        @DisplayName("Создание аукциона ровно за 1 час до начала")
        void createAuction_ExactlyOneHourBeforeStart_Success() {
            // given
            OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
            OffsetDateTime endTime = startTime.plusHours(24);

            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"),
                    startTime, endTime, null, null
            );

            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then
            assertNotNull(response);
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }
    }
}