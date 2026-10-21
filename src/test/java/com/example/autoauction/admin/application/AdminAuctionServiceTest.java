package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.AuctionCommitmentResolver;
import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PolicyVersion;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

    @Mock
    private PolicyVersionService policyVersionService;

    @Mock
    private AuctionCommitmentResolver auctionCommitmentResolver;

    @Mock
    private com.example.autoauction.deposit.application.DepositService depositService;

    @Mock
    private SystemSettingsService settingsService;

    /** DEPOSIT-01: the global participation deposit every created auction snapshots. */
    private static final BigDecimal PARTICIPATION_DEPOSIT = new BigDecimal("15000.00");

    @InjectMocks
    private AdminAuctionService adminAuctionService;

    private static final Long DEFAULT_COMMITMENT_ID = 500L;

    private AuctionCreateRequest validRequest;
    private VehicleResponse approvedVehicle;
    private Auction auction;
    private PolicyVersion currentPolicy;
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

        // Slice 1b-2: a published PolicyVersion, used by every test whose scenario reaches
        // AdminAuctionService.createAuctionFromRequest (fail-closed - createAuction must never
        // proceed without one). Not stubbed globally in this @BeforeEach on purpose: several
        // tests below throw before reaching that point (validator/vehicle/existing-auction
        // rejections), and MockitoExtension's strict stubbing would flag an unused global stub
        // as UnnecessaryStubbingException - see BidServiceTest's setUp() for the same convention.
        currentPolicy = new PolicyVersion(1L, 1L, new BigDecimal("0.05"), true,
                adminId, OffsetDateTime.now(), adminId, OffsetDateTime.now(), null, 0L, 0L);

        // X3-C: lenient default so every test that happens to reach past checkExistingAuctions
        // gets a valid commitment id without having to stub it individually - matching this
        // class's existing convention of NOT stubbing globally with a strict when() for calls
        // several tests never reach (see currentPolicy's own comment above). lenient() is
        // required here specifically because, unlike currentPolicy (a plain field, stubbed
        // per-test), this IS a mock call that MockitoExtension's strict stubbing would flag as
        // unnecessary for every test that throws before reaching this point.
        lenient().when(auctionCommitmentResolver.resolveEligibleCommitment(anyLong())).thenReturn(DEFAULT_COMMITMENT_ID);
        // DEPOSIT-01: same lenient convention - only tests that reach createAuctionFromRequest read it
        lenient().when(settingsService.getRequiredBigDecimal(SystemSettingsService.PARTICIPATION_DEPOSIT_KEY))
                .thenReturn(PARTICIPATION_DEPOSIT);
    }

    @Nested
    @DisplayName("Тесты создания аукциона")
    class CreateAuctionTests {

        private void stubCreatablePath() {
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            // lenient: the fail-closed test stops at the deposit snapshot, before the policy is read
            lenient().when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
            lenient().when(policyVersionService.getById(currentPolicy.getId())).thenReturn(currentPolicy);
        }

        @Test
        @DisplayName("DEPOSIT-01: the created auction snapshots the global participation deposit")
        void createAuction_SnapshotsGlobalParticipationDeposit() {
            stubCreatablePath();
            ArgumentCaptor<Auction> saved = ArgumentCaptor.forClass(Auction.class);
            when(auctionRepository.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));

            adminAuctionService.createAuction(validRequest, adminId);

            assertEquals(0, PARTICIPATION_DEPOSIT.compareTo(saved.getValue().getRequiredDepositAmount()));
            verify(settingsService).getRequiredBigDecimal(SystemSettingsService.PARTICIPATION_DEPOSIT_KEY);
        }

        @Test
        @DisplayName("DEPOSIT-01: a setting change between two creations - each auction keeps the value read at its creation")
        void createAuction_EachAuctionKeepsItsOwnSnapshot() {
            stubCreatablePath();
            List<Auction> saved = new ArrayList<>();
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> { saved.add(invocation.getArgument(0)); return invocation.getArgument(0); });
            when(settingsService.getRequiredBigDecimal(SystemSettingsService.PARTICIPATION_DEPOSIT_KEY))
                    .thenReturn(new BigDecimal("10000.00"), new BigDecimal("15000.00"));

            adminAuctionService.createAuction(validRequest, adminId);
            adminAuctionService.createAuction(validRequest, adminId);

            assertEquals(0, new BigDecimal("10000").compareTo(saved.get(0).getRequiredDepositAmount()));
            assertEquals(0, new BigDecimal("15000").compareTo(saved.get(1).getRequiredDepositAmount()));
        }

        @Test
        @DisplayName("DEPOSIT-01: setting not set -> fail closed, nothing saved")
        void createAuction_FailsClosedWithoutParticipationDeposit() {
            stubCreatablePath();
            when(settingsService.getRequiredBigDecimal(SystemSettingsService.PARTICIPATION_DEPOSIT_KEY))
                    .thenThrow(new IllegalStateException("Обязательная настройка не задана"));

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> adminAuctionService.createAuction(validRequest, adminId));

            assertTrue(e.getMessage().contains("Депозит для участия в аукционах не задан"));
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Успешное создание аукциона с валидными данными")
        void createAuction_Success() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
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

        @Test
        @DisplayName("X3-C: резолвер вызывается с правильным vehicleId, результат записывается в снимок Auction")
        void createAuction_ResolvesCommitmentAndStoresImmutableSnapshot() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
            when(policyVersionService.getById(currentPolicy.getId())).thenReturn(currentPolicy);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            adminAuctionService.createAuction(validRequest, adminId);

            // then
            verify(auctionCommitmentResolver, times(1)).resolveEligibleCommitment(1L);
            ArgumentCaptor<Auction> auctionCaptor = ArgumentCaptor.forClass(Auction.class);
            verify(auctionRepository).save(auctionCaptor.capture());
            assertEquals(DEFAULT_COMMITMENT_ID, auctionCaptor.getValue().getSellerDiagnosticCommitmentId());
        }

        @Test
        @DisplayName("X3-C: резолвер отклоняет (нет eligible commitment) -> Auction не создаётся")
        void createAuction_CommitmentResolutionFails_NoAuctionCreated() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(auctionCommitmentResolver.resolveEligibleCommitment(1L))
                    .thenThrow(new IllegalArgumentException("Для диагностической заявки нет seller diagnostic commitment"));

            // when & then
            assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(validRequest, adminId));

            verify(auctionRepository, never()).save(any(Auction.class));
            // и резолвер вызывается ПОСЛЕ уже существующих проверок (checkExistingAuctions),
            // не до них - порядок сохранён
            verify(auctionRepository, times(1)).existsByVehicleIdAndStatusIn(eq(1L), anyList());
        }

        @Test
        @DisplayName("X3-C: резолвер вызывается после checkExistingAuctions и до auctionRepository.save")
        void createAuction_ResolverCalledBetweenExistingAuctionCheckAndSave() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
            when(policyVersionService.getById(currentPolicy.getId())).thenReturn(currentPolicy);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            adminAuctionService.createAuction(validRequest, adminId);

            // then
            InOrder inOrder = inOrder(auctionRepository, auctionCommitmentResolver);
            inOrder.verify(auctionRepository).existsByVehicleIdAndStatusIn(eq(1L), anyList());
            inOrder.verify(auctionCommitmentResolver).resolveEligibleCommitment(1L);
            inOrder.verify(auctionRepository).save(any(Auction.class));
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
        @EnumSource(value = AuctionStatus.class, names = {"ACTIVE", "CREATED", "WAITING_FOR_PAYMENT", "SOLD", "COMPLETED"})
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
                case WAITING_FOR_PAYMENT -> "Аукцион для этого автомобиля ожидает подтверждения продажи";
                case SOLD -> "Автомобиль уже был продан на аукционе";
                case COMPLETED -> "Автомобиль уже участвовал в завершённом аукционе";
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
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
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
            // Vehicle Auction-State Wiring: startAuction now delegates the actual transition to
            // AuctionFinalizer.startAuction (mocked here) - matching the existing
            // cancelAuction()-delegation tests' pattern below, the mock must actually invoke
            // Auction.start() on the passed-in object to observe the real status change.
            doAnswer(inv -> {
                Auction a = inv.getArgument(0);
                a.start();
                return null;
            }).when(auctionFinalizer).startAuction(any(Auction.class));

            // when
            AuctionResponse response = adminAuctionService.startAuction(1L, adminId);

            // then
            assertEquals(AuctionStatus.ACTIVE, response.status());
            verify(auctionRepository, times(1)).findByIdWithLock(1L);
            verify(auctionFinalizer, times(1)).startAuction(auction);
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
            if (status != AuctionStatus.ACTIVE) {
                // AdminAuctionService's own ACTIVE pre-check short-circuits before ever
                // delegating to AuctionFinalizer.startAuction - only non-ACTIVE statuses reach
                // the delegated call, whose mock must actually invoke Auction.start() so its
                // own generic-message guard can throw exactly as before this slice.
                doAnswer(inv -> {
                    Auction a = inv.getArgument(0);
                    a.start();
                    return null;
                }).when(auctionFinalizer).startAuction(any(Auction.class));
            }

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
        @EnumSource(value = AuctionStatus.class, names = {"COMPLETED", "SOLD", "SALE_FAILED", "EXPIRED", "CANCELLED", "WAITING_FOR_PAYMENT"})
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
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
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
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
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
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
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
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
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
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
            when(auctionRepository.save(any(Auction.class))).thenReturn(auction);

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then
            assertNotNull(response);
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }
    }

    @Nested
    @DisplayName("Slice 1b-2: привязка аукциона к PolicyVersion")
    class PolicyVersionBindingTests {

        @Test
        @DisplayName("Нет опубликованной ценовой политики — createAuction fail closed, IllegalStateException")
        void createAuction_NoPolicyPublished_ShouldFailClosed() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished())
                    .thenThrow(new IllegalStateException("Нет опубликованной ценовой политики"));

            // when & then: no silent fallback to a default/global commission rate
            assertThrows(IllegalStateException.class, () ->
                    adminAuctionService.createAuction(validRequest, adminId)
            );
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Новый аукцион привязывается к id текущей опубликованной PolicyVersion")
        void createAuction_ShouldBindToCurrentPublishedPolicyVersionId() {
            // given
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
            when(policyVersionService.getById(currentPolicy.getId())).thenReturn(currentPolicy);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            adminAuctionService.createAuction(validRequest, adminId);

            // then: the client cannot influence this - AuctionCreateRequest has no
            // policyVersionId/commissionRate field, it is resolved purely server-side
            ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
            verify(auctionRepository).save(captor.capture());
            assertEquals(currentPolicy.getId(), captor.getValue().getPolicyVersionId());
        }

        @Test
        @DisplayName("Ответ на создание отражает buyer-facing цену, вычисленную по конкретной PolicyVersion аукциона")
        void createAuction_ResponseShouldReflectBuyerFacingPriceForBoundPolicy() {
            // given: sellerAmount 1,000,000 reserve @ 5% commission -> 1,050,000.00 exact
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(currentPolicy);
            // response mapping resolves the rate via getById(policyVersionId), never
            // getCurrentPublished() again - this is the call the response path actually makes
            when(policyVersionService.getById(currentPolicy.getId())).thenReturn(currentPolicy);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            AuctionResponse response = adminAuctionService.createAuction(validRequest, adminId);

            // then
            assertEquals(0, new BigDecimal("1260000.00").compareTo(response.reservePrice()));
            assertEquals(0, new BigDecimal("1575000.00").compareTo(response.buyNowPrice()));
        }
    }

    @Nested
    @DisplayName("Slice 1b-2 (B1 fix): reserve/buyNow-vs-startingPrice validation moved to the application layer")
    class PolicyAwarePriceValidationTests {

        private PolicyVersion policyAt(BigDecimal rate) {
            return new PolicyVersion(2L, 2L, rate, true, adminId, OffsetDateTime.now(), adminId, OffsetDateTime.now(), null, 0L, 0L);
        }

        @Test
        @DisplayName("a) policy-bound: sellerReserve(99000) < startingPrice(100000) but buyerReserve(100980) >= startingPrice -> MUST succeed")
        void createAuction_PolicyBound_SellerReserveBelowStartingPrice_ButBuyerFacingAboveIt_ShouldSucceed() {
            // given: startingPrice=100,000, sellerReserve=99,000, commission=2% -> buyerReserve=100,980
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("100000"), new BigDecimal("99000"), null,
                    new BigDecimal("1000"),
                    OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(7), null, null
            );
            PolicyVersion policy = policyAt(new BigDecimal("0.02"));
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(policy);
            when(policyVersionService.getById(policy.getId())).thenReturn(policy);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then: no exception, and the response shows the buyer-facing reserve
            assertNotNull(response);
            assertEquals(0, new BigDecimal("100980.00").compareTo(response.reservePrice()));
        }

        @Test
        @DisplayName("b) policy-bound Buy Now: sellerBuyNow below startingPrice but buyer-facing Buy Now above it -> MUST succeed")
        void createAuction_PolicyBound_SellerBuyNowBelowStartingPrice_ButBuyerFacingAboveIt_ShouldSucceed() {
            // given: startingPrice=100,000, sellerBuyNow=99,000, commission=2% -> buyerBuyNow=100,980
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("100000"), null, new BigDecimal("99000"),
                    new BigDecimal("1000"),
                    OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(7), null, null
            );
            PolicyVersion policy = policyAt(new BigDecimal("0.02"));
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(policy);
            when(policyVersionService.getById(policy.getId())).thenReturn(policy);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            AuctionResponse response = adminAuctionService.createAuction(request, adminId);

            // then
            assertNotNull(response);
            assertEquals(0, new BigDecimal("100980.00").compareTo(response.buyNowPrice()));
        }

        @Test
        @DisplayName("c) policy-bound: buyer-facing reserve < startingPrice -> MUST reject")
        void createAuction_PolicyBound_BuyerFacingReserveBelowStartingPrice_ShouldReject() {
            // given: startingPrice=100,000, sellerReserve=90,000, commission=2% -> buyerReserve=91,800
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("100000"), new BigDecimal("90000"), null,
                    new BigDecimal("1000"),
                    OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(7), null, null
            );
            PolicyVersion policy = policyAt(new BigDecimal("0.02"));
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(policy);

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(request, adminId)
            );
            assertTrue(exception.getMessage().contains("Резервная цена"));
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("d) policy-bound: buyer-facing Buy Now <= startingPrice -> MUST reject")
        void createAuction_PolicyBound_BuyerFacingBuyNowAtOrBelowStartingPrice_ShouldReject() {
            // given: startingPrice=100,000, sellerBuyNow=95,000, commission=2% -> buyerBuyNow=96,900
            AuctionCreateRequest request = new AuctionCreateRequest(
                    1L, new BigDecimal("100000"), null, new BigDecimal("95000"),
                    new BigDecimal("1000"),
                    OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(7), null, null
            );
            PolicyVersion policy = policyAt(new BigDecimal("0.02"));
            doNothing().when(auctionValidator).validateBasicRequest(any());
            doNothing().when(auctionValidator).validateBusinessRules(any(), any());
            when(vehicleService.getVehicleDetails(1L)).thenReturn(approvedVehicle);
            when(auctionRepository.existsByVehicleIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
            when(policyVersionService.getCurrentPublished()).thenReturn(policy);

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.createAuction(request, adminId)
            );
            assertTrue(exception.getMessage().contains("Цена мгновенной покупки"));
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("e) legacy (policyVersionId == null): raw reserve < startingPrice -> reject (old constructor semantics preserved)")
        void validatePolicyAwarePrices_LegacyAuction_RawReserveBelowStartingPrice_ShouldReject() {
            // given: a legacy auction - Auction's own constructor no longer rejects this (B1 fix),
            // so this must now be rejected by AdminAuctionService's legacy branch instead.
            Auction legacyAuction = new Auction(1L, new BigDecimal("100000"), new BigDecimal("90000"),
                    null, new BigDecimal("1000"), OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), adminId);
            assertNull(legacyAuction.getPolicyVersionId());

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.validatePolicyAwarePrices(legacyAuction, null)
            );
            assertTrue(exception.getMessage().contains("Резервная цена"));
        }

        @Test
        @DisplayName("e) legacy (policyVersionId == null): raw buyNow <= startingPrice -> reject (old constructor semantics preserved)")
        void validatePolicyAwarePrices_LegacyAuction_RawBuyNowAtOrBelowStartingPrice_ShouldReject() {
            Auction legacyAuction = new Auction(1L, new BigDecimal("100000"), null,
                    new BigDecimal("100000"), new BigDecimal("1000"), OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(7), adminId);
            assertNull(legacyAuction.getPolicyVersionId());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    adminAuctionService.validatePolicyAwarePrices(legacyAuction, null)
            );
            assertTrue(exception.getMessage().contains("Цена мгновенной покупки"));
        }
    }

    @Nested
    @DisplayName("X3-D: releaseWinner(auctionId) - status guard hardening")
    class ReleaseWinnerTests {

        private Auction releaseAuction;
        private final Long releaseAuctionId = 42L;
        private final Long releaseWinnerId = 100L;

        @BeforeEach
        void setUpReleaseAuction() {
            releaseAuction = new Auction(
                    1L, new BigDecimal("1000000"), new BigDecimal("1200000"),
                    new BigDecimal("1500000"), new BigDecimal("10000"),
                    OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(7), adminId
            );
            releaseAuction.setId(releaseAuctionId);
            releaseAuction.setWinnerId(releaseWinnerId);
        }

        @Test
        @DisplayName("ACTIVE -> rejected before touching DepositService")
        void releaseWinner_Active_Rejected() {
            releaseAuction.setStatus(AuctionStatus.ACTIVE);
            when(auctionRepository.findById(releaseAuctionId)).thenReturn(Optional.of(releaseAuction));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> adminAuctionService.releaseWinner(releaseAuctionId));
            assertTrue(ex.getMessage().contains("исход ещё не определён"));

            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("WAITING_FOR_PAYMENT -> rejected before touching DepositService")
        void releaseWinner_WaitingForPayment_Rejected() {
            releaseAuction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            when(auctionRepository.findById(releaseAuctionId)).thenReturn(Optional.of(releaseAuction));

            assertThrows(IllegalStateException.class,
                    () -> adminAuctionService.releaseWinner(releaseAuctionId));

            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("SOLD -> allowed, delegates to DepositService.releaseWinnerDeposit (preserved behavior)")
        void releaseWinner_Sold_Allowed() {
            releaseAuction.setStatus(AuctionStatus.SOLD);
            when(auctionRepository.findById(releaseAuctionId)).thenReturn(Optional.of(releaseAuction));

            adminAuctionService.releaseWinner(releaseAuctionId);

            verify(depositService, times(1)).releaseWinnerDeposit(releaseWinnerId, releaseAuctionId);
        }

        @Test
        @DisplayName("SALE_FAILED -> allowed, delegates to DepositService.releaseWinnerDeposit (preserved behavior)")
        void releaseWinner_SaleFailed_Allowed() {
            releaseAuction.setStatus(AuctionStatus.SALE_FAILED);
            when(auctionRepository.findById(releaseAuctionId)).thenReturn(Optional.of(releaseAuction));

            adminAuctionService.releaseWinner(releaseAuctionId);

            verify(depositService, times(1)).releaseWinnerDeposit(releaseWinnerId, releaseAuctionId);
        }

        @Test
        @DisplayName("Legacy COMPLETED -> allowed, delegates to DepositService.releaseWinnerDeposit (existing real-world usage preserved)")
        @SuppressWarnings("deprecation")
        void releaseWinner_LegacyCompleted_Allowed() {
            releaseAuction.setStatus(AuctionStatus.COMPLETED);
            when(auctionRepository.findById(releaseAuctionId)).thenReturn(Optional.of(releaseAuction));

            adminAuctionService.releaseWinner(releaseAuctionId);

            verify(depositService, times(1)).releaseWinnerDeposit(releaseWinnerId, releaseAuctionId);
        }

        @Test
        @DisplayName("No winner -> IllegalStateException regardless of status, unchanged existing behavior")
        void releaseWinner_NoWinner_ThrowsRegardlessOfStatus() {
            releaseAuction.setStatus(AuctionStatus.SOLD);
            releaseAuction.setWinnerId(null);
            when(auctionRepository.findById(releaseAuctionId)).thenReturn(Optional.of(releaseAuction));

            assertThrows(IllegalStateException.class,
                    () -> adminAuctionService.releaseWinner(releaseAuctionId));

            verifyNoInteractions(depositService);
        }
    }
}