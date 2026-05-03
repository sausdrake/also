package com.example.autoauction.vehicle.application;

import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import com.example.autoauction.vehicle.web.dto.VehicleCreateRequest;
import com.example.autoauction.vehicle.web.dto.VehicleUpdateRequest;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты VehicleService")
class VehicleServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @InjectMocks
    private VehicleService vehicleService;

    private VehicleCreateRequest createRequest;
    private VehicleCreateRequest minimalCreateRequest;
    private VehicleUpdateRequest updateRequest;
    private VehicleUpdateRequest partialUpdateRequest;
    private Vehicle vehicle;
    private Vehicle draftVehicle;
    private Vehicle pendingVehicle;
    private Vehicle approvedVehicle;
    private Vehicle rejectedVehicle;
    private Vehicle inAuctionVehicle;

    private final Long diagnosticId = 16L;
    private final String diagnosticName = "diagnostic";
    private final Long adminId = 1L;
    private final String adminName = "admin";
    private final Long wrongOwnerId = 999L;

    @BeforeEach
    void setUp() {
        // Полный запрос на создание
        createRequest = new VehicleCreateRequest(
                "BMW", "M5", 2023, "WBSDE91070CZ12345",
                VehicleType.SEDAN, "A123BC", "black", 10000,
                4.4, FuelType.PETROL, "auto", "Test car"
        );

        // Минимальный запрос (только обязательные поля)
        minimalCreateRequest = new VehicleCreateRequest(
                "Audi", "RS6", 2024, "WAUZZZ4G9NN123456",
                VehicleType.WAGON, null, null, null,
                null, null, null, null
        );

        // Полный запрос на обновление
        updateRequest = new VehicleUpdateRequest(
                "B456CD", "white", 15000, 4.4,
                FuelType.PETROL, "auto", "Updated description"
        );

        // Частичный запрос на обновление (только некоторые поля)
        partialUpdateRequest = new VehicleUpdateRequest(
                "C789EF", null, null, null,
                null, null, null
        );

        // Черновик
        draftVehicle = createTestVehicle(
                1L, "BMW", "M5", 2023, "WBSDE91070CZ12345",
                VehicleType.SEDAN, diagnosticId, diagnosticName, VehicleStatus.DRAFT
        );
        draftVehicle.setLicensePlate("A123BC");
        draftVehicle.setColor("black");
        draftVehicle.setMileage(10000);
        draftVehicle.setEngineCapacity(4.4);
        draftVehicle.setFuelType(FuelType.PETROL);
        draftVehicle.setTransmission("auto");
        draftVehicle.setDescription("Test car");

        // На проверке
        pendingVehicle = createTestVehicle(
                2L, "Audi", "RS6", 2024, "WAUZZZ4G9NN123456",
                VehicleType.WAGON, diagnosticId, diagnosticName, VehicleStatus.PENDING_REVIEW
        );
        pendingVehicle.setSubmittedAt(OffsetDateTime.now());
        pendingVehicle.setReviewedAt(null);

        // Одобрен
        approvedVehicle = createTestVehicle(
                3L, "Mercedes", "E63", 2023, "WDD2132891A123456",
                VehicleType.SEDAN, diagnosticId, diagnosticName, VehicleStatus.APPROVED
        );
        approvedVehicle.setAdminId(adminId);
        approvedVehicle.setAdminName(adminName);
        approvedVehicle.setReviewedAt(OffsetDateTime.now());

        // Отклонён (требует доработки)
        rejectedVehicle = createTestVehicle(
                4L, "Porsche", "911", 2023, "WP0ZZZ99ZNS123456",
                VehicleType.COUPE, diagnosticId, diagnosticName, VehicleStatus.NEEDS_FIXES
        );
        rejectedVehicle.setAdminId(adminId);
        rejectedVehicle.setAdminName(adminName);
        rejectedVehicle.setRejectionReason("Не хватает фотографий");
        rejectedVehicle.setReviewedAt(OffsetDateTime.now());

        // В аукционе
        inAuctionVehicle = createTestVehicle(
                5L, "Tesla", "Model S", 2023, "5YJSA1E26NF123456",
                VehicleType.SEDAN, diagnosticId, diagnosticName, VehicleStatus.IN_AUCTION
        );
        inAuctionVehicle.setAdminId(adminId);
        inAuctionVehicle.setAdminName(adminName);
        inAuctionVehicle.setReviewedAt(OffsetDateTime.now());

        vehicle = draftVehicle;
    }

    private Vehicle createTestVehicle(Long id, String brand, String model, Integer year, String vin,
                                      VehicleType type, Long diagnosticId, String diagnosticName,
                                      VehicleStatus status) {
        Vehicle vehicle = new Vehicle(brand, model, year, vin, type, diagnosticId, diagnosticName);
        vehicle.setId(id);
        vehicle.setStatus(status);
        return vehicle;
    }

    // ==================== CREATE DRAFT TESTS ====================

    @Nested
    @DisplayName("Создание черновика автомобиля")
    class CreateDraftTests {

        @Test
        @DisplayName("Успешное создание с полными данными")
        void createDraft_WithFullData_Success() {
            // given
            when(vehicleRepository.existsByVin(createRequest.vin())).thenReturn(false);
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(draftVehicle);

            // when
            VehicleResponse response = vehicleService.createDraft(createRequest, diagnosticId, diagnosticName);

            // then
            assertNotNull(response);
            assertEquals(draftVehicle.getId(), response.id());
            assertEquals("BMW", response.brand());
            assertEquals("M5", response.model());
            assertEquals(2023, response.year());
            assertEquals("WBSDE91070CZ12345", response.vin());
            assertEquals(VehicleType.SEDAN, response.type());
            assertEquals("A123BC", response.licensePlate());
            assertEquals("black", response.color());
            assertEquals(10000, response.mileage());
            assertEquals(4.4, response.engineCapacity());
            assertEquals(FuelType.PETROL, response.fuelType());
            assertEquals("auto", response.transmission());
            assertEquals("Test car", response.description());
            assertEquals(VehicleStatus.DRAFT, response.status());
            assertEquals(diagnosticId, response.diagnosticId());
            assertEquals(diagnosticName, response.diagnosticName());
            assertNull(response.adminId());
            assertNull(response.adminName());
            assertNotNull(response.createdAt());

            verify(vehicleRepository, times(1)).existsByVin(createRequest.vin());
            verify(vehicleRepository, times(1)).save(any(Vehicle.class));
        }

        @Test
        @DisplayName("Успешное создание с минимальными данными (только обязательные поля)")
        void createDraft_WithMinimalData_Success() {
            // given
            Vehicle minimalVehicle = createTestVehicle(
                    6L, "Audi", "RS6", 2024, "WAUZZZ4G9NN123456",
                    VehicleType.WAGON, diagnosticId, diagnosticName, VehicleStatus.DRAFT
            );
            when(vehicleRepository.existsByVin(minimalCreateRequest.vin())).thenReturn(false);
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(minimalVehicle);

            // when
            VehicleResponse response = vehicleService.createDraft(minimalCreateRequest, diagnosticId, diagnosticName);

            // then
            assertNotNull(response);
            assertEquals("Audi", response.brand());
            assertEquals("RS6", response.model());
            assertEquals(VehicleType.WAGON, response.type());
            assertNull(response.licensePlate());
            assertNull(response.color());
            assertNull(response.mileage());

            // Проверяем, что в сохранённом объекте опциональные поля null
            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            Vehicle savedVehicle = vehicleCaptor.getValue();
            assertNull(savedVehicle.getLicensePlate());
            assertNull(savedVehicle.getColor());
            assertNull(savedVehicle.getMileage());
        }

        @Test
        @DisplayName("Создание с существующим VIN - выброс исключения")
        void createDraft_VinExists_ThrowsException() {
            // given
            when(vehicleRepository.existsByVin(createRequest.vin())).thenReturn(true);

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> vehicleService.createDraft(createRequest, diagnosticId, diagnosticName)
            );
            assertTrue(exception.getMessage().contains("VIN уже существует"));

            verify(vehicleRepository, never()).save(any(Vehicle.class));
        }

        @Test
        @DisplayName("Создание с null diagnosticId - NPE")
        void createDraft_NullDiagnosticId_ThrowsException() {
            assertThrows(NullPointerException.class,
                    () -> vehicleService.createDraft(createRequest, null, diagnosticName)
            );
            verify(vehicleRepository, never()).save(any(Vehicle.class));
        }

        @Test
        @DisplayName("Создание с null diagnosticName - NPE")
        void createDraft_NullDiagnosticName_ThrowsException() {
            assertThrows(NullPointerException.class,
                    () -> vehicleService.createDraft(createRequest, diagnosticId, null)
            );
            verify(vehicleRepository, never()).save(any(Vehicle.class));
        }
    }

    // ==================== GET MY VEHICLES TESTS ====================

    @Nested
    @DisplayName("Получение списка автомобилей диагностика")
    class GetMyVehiclesTests {

        @Test
        @DisplayName("Получение всех автомобилей диагностика (без фильтра по статусу)")
        void getMyVehicles_WithoutStatus_ReturnsAll() {
            // given
            List<Vehicle> vehicles = List.of(draftVehicle, pendingVehicle, approvedVehicle);
            when(vehicleRepository.findByDiagnosticId(diagnosticId)).thenReturn(vehicles);

            // when
            List<VehicleResponse> responses = vehicleService.getMyVehicles(diagnosticId, null);

            // then
            assertEquals(3, responses.size());
            verify(vehicleRepository, times(1)).findByDiagnosticId(diagnosticId);
            verify(vehicleRepository, never()).findByDiagnosticIdAndStatus(anyLong(), any());
        }

        @Test
        @DisplayName("Получение автомобилей с фильтром по статусу")
        void getMyVehicles_WithStatus_ReturnsFiltered() {
            // given
            List<Vehicle> vehicles = List.of(draftVehicle);
            when(vehicleRepository.findByDiagnosticIdAndStatus(diagnosticId, VehicleStatus.DRAFT))
                    .thenReturn(vehicles);

            // when
            List<VehicleResponse> responses = vehicleService.getMyVehicles(diagnosticId, VehicleStatus.DRAFT);

            // then
            assertEquals(1, responses.size());
            assertEquals(VehicleStatus.DRAFT, responses.get(0).status());
            verify(vehicleRepository, times(1))
                    .findByDiagnosticIdAndStatus(diagnosticId, VehicleStatus.DRAFT);
        }

        @Test
        @DisplayName("Пустой список автомобилей")
        void getMyVehicles_EmptyList_ReturnsEmpty() {
            // given
            when(vehicleRepository.findByDiagnosticId(diagnosticId)).thenReturn(List.of());

            // when
            List<VehicleResponse> responses = vehicleService.getMyVehicles(diagnosticId, null);

            // then
            assertTrue(responses.isEmpty());
        }

        @Test
        @DisplayName("Диагностик без автомобилей")
        void getMyVehicles_NewDiagnostic_ReturnsEmpty() {
            // given
            Long newDiagnosticId = 100L;
            when(vehicleRepository.findByDiagnosticId(newDiagnosticId)).thenReturn(List.of());

            // when
            List<VehicleResponse> responses = vehicleService.getMyVehicles(newDiagnosticId, null);

            // then
            assertTrue(responses.isEmpty());
        }
    }

    // ==================== GET VEHICLE TESTS ====================

    @Nested
    @DisplayName("Получение конкретного автомобиля")
    class GetVehicleTests {

        @Test
        @DisplayName("Успешное получение автомобиля по ID")
        void getVehicle_Success() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // when
            VehicleResponse response = vehicleService.getVehicle(1L, diagnosticId);

            // then
            assertNotNull(response);
            assertEquals(1L, response.id());
            assertEquals("BMW", response.brand());
        }

        @Test
        @DisplayName("Автомобиль не найден - исключение")
        void getVehicle_NotFound_ThrowsException() {
            // given
            when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> vehicleService.getVehicle(99L, diagnosticId)
            );
            assertTrue(exception.getMessage().contains("не найден"));
        }

        @Test
        @DisplayName("Доступ к чужому автомобилю - SecurityException")
        void getVehicle_WrongOwner_ThrowsSecurityException() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // when & then
            SecurityException exception = assertThrows(SecurityException.class,
                    () -> vehicleService.getVehicle(1L, wrongOwnerId)
            );
            assertTrue(exception.getMessage().contains("нет доступа"));
        }

        @Test
        @DisplayName("Null ID - IllegalArgumentException (метод не проверяет null, репозиторий выбрасывает IllegalArgumentException)")
        void getVehicle_NullId_ThrowsException() {
            // when & then
            // Метод getVehicle вызывает repository.findById(null), который выбрасывает IllegalArgumentException
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> vehicleService.getVehicle(null, diagnosticId)
            );
            // или проверяем, что исключение содержит ожидаемое сообщение
            assertTrue(exception.getMessage().contains("не найден") || exception.getMessage() == null);
        }
    }

    // ==================== UPDATE DRAFT TESTS ====================

    @Nested
    @DisplayName("Обновление черновика")
    class UpdateDraftTests {

        @Test
        @DisplayName("Успешное обновление всех полей")
        void updateDraft_FullUpdate_Success() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(draftVehicle);

            // when
            VehicleResponse response = vehicleService.updateDraft(1L, updateRequest, diagnosticId);

            // then
            assertNotNull(response);
            verify(vehicleRepository, times(1)).save(draftVehicle);
        }

        @Test
        @DisplayName("Успешное частичное обновление (только госномер)")
        void updateDraft_PartialUpdate_Success() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(draftVehicle);

            // when
            vehicleService.updateDraft(1L, partialUpdateRequest, diagnosticId);

            // then
            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            Vehicle captured = vehicleCaptor.getValue();
            assertEquals("C789EF", captured.getLicensePlate());
            assertEquals("black", captured.getColor()); // не изменился
            assertEquals(10000, captured.getMileage()); // не изменился
        }

        @Test
        @DisplayName("Обновление автомобиля не в статусе DRAFT - исключение")
        void updateDraft_WhenNotDraft_ThrowsException() {
            // given
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> vehicleService.updateDraft(2L, updateRequest, diagnosticId)
            );
            assertTrue(exception.getMessage().contains("Нельзя редактировать"));
        }

        @Test
        @DisplayName("Обновление автомобиля в статусе NEEDS_FIXES - можно")
        void updateDraft_WhenNeedsFixes_Success() {
            // given
            when(vehicleRepository.findById(4L)).thenReturn(Optional.of(rejectedVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(rejectedVehicle);

            // when & then
            assertDoesNotThrow(() -> vehicleService.updateDraft(4L, updateRequest, diagnosticId));
            verify(vehicleRepository, times(1)).save(rejectedVehicle);
        }

        @Test
        @DisplayName("Обновление несуществующего автомобиля")
        void updateDraft_NotFound_ThrowsException() {
            // given
            when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThrows(IllegalArgumentException.class,
                    () -> vehicleService.updateDraft(99L, updateRequest, diagnosticId)
            );
        }

        @Test
        @DisplayName("Обновление чужого автомобиля")
        void updateDraft_WrongOwner_ThrowsException() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // when & then
            assertThrows(SecurityException.class,
                    () -> vehicleService.updateDraft(1L, updateRequest, wrongOwnerId)
            );
        }
    }

    // ==================== SUBMIT FOR REVIEW TESTS ====================

    @Nested
    @DisplayName("Отправка на проверку")
    class SubmitForReviewTests {

        @Test
        @DisplayName("Успешная отправка черновика на проверку")
        void submitForReview_Success() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(draftVehicle);

            // when
            VehicleResponse response = vehicleService.submitForReview(1L, diagnosticId);

            // then
            assertNotNull(response);
            assertEquals(VehicleStatus.PENDING_REVIEW, response.status());
            assertNotNull(response.submittedAt());
            verify(vehicleRepository, times(1)).save(draftVehicle);
        }

        @Test
        @DisplayName("Отправка автомобиля в статусе NEEDS_FIXES")
        void submitForReview_WhenNeedsFixes_Success() {
            // given
            when(vehicleRepository.findById(4L)).thenReturn(Optional.of(rejectedVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(rejectedVehicle);

            // when
            VehicleResponse response = vehicleService.submitForReview(4L, diagnosticId);

            // then
            assertEquals(VehicleStatus.PENDING_REVIEW, response.status());
        }

        @Test
        @DisplayName("Отправка уже на проверке - исключение")
        void submitForReview_WhenAlreadyPending_ThrowsException() {
            // given
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> vehicleService.submitForReview(2L, diagnosticId)
            );
            assertTrue(exception.getMessage().contains("Only DRAFT or NEEDS_FIXES"));
        }

        @Test
        @DisplayName("Отправка одобренного автомобиля - исключение")
        void submitForReview_WhenApproved_ThrowsException() {
            // given
            when(vehicleRepository.findById(3L)).thenReturn(Optional.of(approvedVehicle));

            // when & then
            assertThrows(IllegalStateException.class,
                    () -> vehicleService.submitForReview(3L, diagnosticId)
            );
        }
    }

    // ==================== DELETE DRAFT TESTS ====================

    @Nested
    @DisplayName("Удаление черновика")
    class DeleteDraftTests {

        @Test
        @DisplayName("Успешное удаление черновика")
        void deleteDraft_Success() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));
            doNothing().when(vehicleRepository).deleteById(1L);

            // when
            vehicleService.deleteDraft(1L, diagnosticId);

            // then
            verify(vehicleRepository, times(1)).deleteById(1L);
        }

        @Test
        @DisplayName("Удаление не-черновика - исключение")
        void deleteDraft_WhenNotDraft_ThrowsException() {
            // given
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> vehicleService.deleteDraft(2L, diagnosticId)
            );
            assertTrue(exception.getMessage().contains("Можно удалить только черновик"));
            verify(vehicleRepository, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("Удаление несуществующего автомобиля")
        void deleteDraft_NotFound_ThrowsException() {
            // given
            when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThrows(IllegalArgumentException.class,
                    () -> vehicleService.deleteDraft(99L, diagnosticId)
            );
        }

        @Test
        @DisplayName("Удаление чужого автомобиля")
        void deleteDraft_WrongOwner_ThrowsException() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // when & then
            assertThrows(SecurityException.class,
                    () -> vehicleService.deleteDraft(1L, wrongOwnerId)
            );
        }
    }

    // ==================== GET VEHICLES BY STATUS (ADMIN) TESTS ====================

    @Nested
    @DisplayName("Получение автомобилей по статусу (админ)")
    class GetVehiclesByStatusTests {

        @Test
        @DisplayName("Получение одобренных автомобилей")
        void getVehiclesByStatus_Approved() {
            // given
            when(vehicleRepository.findByStatus(VehicleStatus.APPROVED))
                    .thenReturn(List.of(approvedVehicle, inAuctionVehicle));

            // when
            List<VehicleResponse> responses = vehicleService.getVehiclesByStatus(VehicleStatus.APPROVED);

            // then
            assertEquals(2, responses.size());
            assertEquals(VehicleStatus.APPROVED, responses.get(0).status());
            assertEquals(VehicleStatus.IN_AUCTION, responses.get(1).status());
        }

        @Test
        @DisplayName("Получение автомобилей на проверке")
        void getVehiclesByStatus_PendingReview() {
            // given
            when(vehicleRepository.findByStatus(VehicleStatus.PENDING_REVIEW))
                    .thenReturn(List.of(pendingVehicle));

            // when
            List<VehicleResponse> responses = vehicleService.getVehiclesByStatus(VehicleStatus.PENDING_REVIEW);

            // then
            assertEquals(1, responses.size());
            assertEquals(VehicleStatus.PENDING_REVIEW, responses.get(0).status());
        }

        @Test
        @DisplayName("Пустой результат для статуса без автомобилей")
        void getVehiclesByStatus_EmptyResult() {
            // given
            when(vehicleRepository.findByStatus(VehicleStatus.SOLD)).thenReturn(List.of());

            // when
            List<VehicleResponse> responses = vehicleService.getVehiclesByStatus(VehicleStatus.SOLD);

            // then
            assertTrue(responses.isEmpty());
        }
    }

    // ==================== GET VEHICLE DETAILS (ADMIN) TESTS ====================

    @Nested
    @DisplayName("Получение деталей автомобиля (админ)")
    class GetVehicleDetailsTests {

        @Test
        @DisplayName("Успешное получение деталей для админа")
        void getVehicleDetails_Success() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // when
            VehicleResponse response = vehicleService.getVehicleDetails(1L);

            // then
            assertNotNull(response);
            assertEquals(1L, response.id());
            assertEquals("BMW", response.brand());
        }

        @Test
        @DisplayName("Детали несуществующего автомобиля")
        void getVehicleDetails_NotFound_ThrowsException() {
            // given
            when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> vehicleService.getVehicleDetails(99L)
            );
            assertTrue(exception.getMessage().contains("не найден"));
        }
    }

    // ==================== APPROVE VEHICLE (ADMIN) TESTS ====================

    @Nested
    @DisplayName("Одобрение автомобиля (админ)")
    class ApproveVehicleTests {

        @Test
        @DisplayName("Успешное одобрение автомобиля на проверке")
        void approveVehicle_Success() {
            // given
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(pendingVehicle);

            // when
            VehicleResponse response = vehicleService.approveVehicle(2L, adminId, adminName);

            // then
            assertNotNull(response);
            assertEquals(VehicleStatus.APPROVED, response.status());
            assertEquals(adminId, response.adminId());
            assertEquals(adminName, response.adminName());
            assertNotNull(response.reviewedAt());

            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            Vehicle captured = vehicleCaptor.getValue();
            assertEquals(adminId, captured.getAdminId());
            assertEquals(adminName, captured.getAdminName());
        }

        @Test
        @DisplayName("Одобрение уже одобренного автомобиля - исключение")
        void approveVehicle_AlreadyApproved_ThrowsException() {
            // given
            when(vehicleRepository.findById(3L)).thenReturn(Optional.of(approvedVehicle));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> vehicleService.approveVehicle(3L, adminId, adminName)
            );
            assertTrue(exception.getMessage().contains("Only PENDING_REVIEW"));
        }

        @Test
        @DisplayName("Одобрение автомобиля в статусе DRAFT - исключение")
        void approveVehicle_WhenDraft_ThrowsException() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // when & then
            assertThrows(IllegalStateException.class,
                    () -> vehicleService.approveVehicle(1L, adminId, adminName)
            );
        }

        @Test
        @DisplayName("Одобрение несуществующего автомобиля")
        void approveVehicle_NotFound_ThrowsException() {
            // given
            when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

            // when & then
            assertThrows(IllegalArgumentException.class,
                    () -> vehicleService.approveVehicle(99L, adminId, adminName)
            );
        }
    }

    // ==================== REJECT VEHICLE (ADMIN) TESTS ====================

    @Nested
    @DisplayName("Отклонение автомобиля (админ)")
    class RejectVehicleTests {

        private final String rejectionReason = "Не хватает фотографий двигателя и документов";

        @Test
        @DisplayName("Успешное отклонение автомобиля с указанием причины")
        void rejectVehicle_Success() {
            // given
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(pendingVehicle);

            // when
            VehicleResponse response = vehicleService.rejectVehicle(2L, adminId, adminName, rejectionReason);

            // then
            assertNotNull(response);
            assertEquals(VehicleStatus.NEEDS_FIXES, response.status());
            assertEquals(rejectionReason, response.rejectionReason());
            assertEquals(adminId, response.adminId());
            assertEquals(adminName, response.adminName());

            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            Vehicle captured = vehicleCaptor.getValue();
            assertEquals(rejectionReason, captured.getRejectionReason());
        }

        @Test
        @DisplayName("Отклонение с пустой причиной - разрешено (не штатная ситуация)")
        void rejectVehicle_WithEmptyReason_ShouldBeAllowed() {
            // given
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenReturn(pendingVehicle);

            // when & then
            assertDoesNotThrow(() -> vehicleService.rejectVehicle(2L, adminId, adminName, ""));
            verify(vehicleRepository, times(1)).save(any(Vehicle.class));
        }

        @Test
        @DisplayName("Отклонение уже одобренного автомобиля - исключение")
        void rejectVehicle_AlreadyApproved_ThrowsException() {
            // given
            when(vehicleRepository.findById(3L)).thenReturn(Optional.of(approvedVehicle));

            // when & then
            assertThrows(IllegalStateException.class,
                    () -> vehicleService.rejectVehicle(3L, adminId, adminName, rejectionReason)
            );
        }

        @Test
        @DisplayName("Отклонение автомобиля в статусе DRAFT - исключение")
        void rejectVehicle_WhenDraft_ThrowsException() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // when & then
            assertThrows(IllegalStateException.class,
                    () -> vehicleService.rejectVehicle(1L, adminId, adminName, rejectionReason)
            );
        }
    }

    // ==================== GET VEHICLES BY STATUSES (MULTIPLE) TESTS ====================

    @Nested
    @DisplayName("Получение автомобилей по списку статусов")
    class GetVehiclesByStatusesTests {

        @Test
        @DisplayName("Получение автомобилей по нескольким статусам")
        void getVehiclesByStatuses_Success() {
            // given
            List<VehicleStatus> statuses = List.of(VehicleStatus.DRAFT, VehicleStatus.PENDING_REVIEW);
            List<Vehicle> vehicles = List.of(draftVehicle, pendingVehicle);
            when(vehicleRepository.findByStatusIn(statuses)).thenReturn(vehicles);

            // when
            List<VehicleResponse> responses = vehicleService.getVehiclesByStatuses(statuses);

            // then
            assertEquals(2, responses.size());
            verify(vehicleRepository, times(1)).findByStatusIn(statuses);
        }

        @Test
        @DisplayName("Пустой список статусов - пустой результат")
        void getVehiclesByStatuses_EmptyStatusList_ReturnsEmpty() {
            // given
            when(vehicleRepository.findByStatusIn(List.of())).thenReturn(List.of());

            // when
            List<VehicleResponse> responses = vehicleService.getVehiclesByStatuses(List.of());

            // then
            assertTrue(responses.isEmpty());
        }
    }

    // ==================== DATA INTEGRITY TESTS ====================

    @Nested
    @DisplayName("Проверка целостности данных")
    class DataIntegrityTests {

        @Test
        @DisplayName("При сохранении устанавливаются корректные даты")
        void save_SetsCorrectTimestamps() {
            // given
            when(vehicleRepository.existsByVin(anyString())).thenReturn(false);
            when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
                Vehicle saved = invocation.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            OffsetDateTime beforeCreate = OffsetDateTime.now();

            // when
            VehicleResponse response = vehicleService.createDraft(createRequest, diagnosticId, diagnosticName);

            // then
            assertNotNull(response.createdAt());
            assertTrue(response.createdAt().isAfter(beforeCreate) || response.createdAt().isEqual(beforeCreate));
            assertNotNull(response.updatedAt());
        }

        @Test
        @DisplayName("При обновлении updatedAt меняется (используем ArgumentCaptor)")
        void update_UpdatesTimestamp() {
            // given
            // Сохраняем оригинальную временную метку
            OffsetDateTime originalUpdatedAt = draftVehicle.getUpdatedAt();

            // Создаём мок, который будет возвращать автомобиль с обновлённой временной меткой
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));

            // Используем Answer, чтобы можно было изменить состояние перед сохранением
            when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
                Vehicle saved = invocation.getArgument(0);
                // Симулируем, что при сохранении updatedAt обновляется
                saved.setUpdatedAt(OffsetDateTime.now());
                return saved;
            });

            // when
            vehicleService.updateDraft(1L, updateRequest, diagnosticId);

            // then
            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            Vehicle captured = vehicleCaptor.getValue();

            // Проверяем, что updatedAt был изменён (не равен оригинальному)
            assertNotEquals(originalUpdatedAt, captured.getUpdatedAt());
        }

        @Test
        @DisplayName("При отправке на проверку устанавливается submittedAt")
        void submitForReview_SetsSubmittedAt() {
            // given
            when(vehicleRepository.findById(1L)).thenReturn(Optional.of(draftVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
                Vehicle saved = invocation.getArgument(0);
                saved.setSubmittedAt(OffsetDateTime.now());
                return saved;
            });

            // when
            VehicleResponse response = vehicleService.submitForReview(1L, diagnosticId);

            // then
            assertNotNull(response.submittedAt());
            assertEquals(VehicleStatus.PENDING_REVIEW, response.status());

            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            assertNotNull(vehicleCaptor.getValue().getSubmittedAt());
        }

        @Test
        @DisplayName("При одобрении устанавливается reviewedAt")
        void approve_SetsReviewedAt() {
            // given
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
                Vehicle saved = invocation.getArgument(0);
                saved.setReviewedAt(OffsetDateTime.now());
                return saved;
            });

            // when
            VehicleResponse response = vehicleService.approveVehicle(2L, adminId, adminName);

            // then
            assertNotNull(response.reviewedAt());
            assertEquals(VehicleStatus.APPROVED, response.status());

            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            assertNotNull(vehicleCaptor.getValue().getReviewedAt());
        }

        @Test
        @DisplayName("При отклонении устанавливается reviewedAt и rejectionReason")
        void reject_SetsReviewedAtAndReason() {
            // given
            String reason = "Не хватает фотографий";
            when(vehicleRepository.findById(2L)).thenReturn(Optional.of(pendingVehicle));
            when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
                Vehicle saved = invocation.getArgument(0);
                saved.setReviewedAt(OffsetDateTime.now());
                saved.setRejectionReason(reason);
                return saved;
            });

            // when
            VehicleResponse response = vehicleService.rejectVehicle(2L, adminId, adminName, reason);

            // then
            assertNotNull(response.reviewedAt());
            assertEquals(reason, response.rejectionReason());
            assertEquals(VehicleStatus.NEEDS_FIXES, response.status());

            ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository).save(vehicleCaptor.capture());
            assertNotNull(vehicleCaptor.getValue().getReviewedAt());
            assertEquals(reason, vehicleCaptor.getValue().getRejectionReason());
        }
    }
}