package com.example.autoauction.admin.web;

import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.vehicle.application.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Дашборд администратора", description = "Статистика системы")
public class DashboardController {

    private final AuctionRepository auctionRepository;
    private final UserService userService;
    private final DiagnosticRequestService diagnosticRequestService;
    private final VehicleService vehicleService;

    @GetMapping
    @Operation(summary = "Получить статистику дашборда")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        long totalVehicles = vehicleService.countAllVehicles();
        long totalUsers = userService.countUsers();
        long activeAuctions = auctionRepository.findByStatus(AuctionStatus.ACTIVE).size();
        long finishedAuctions = auctionRepository.findByStatus(AuctionStatus.COMPLETED).size()
                + auctionRepository.findByStatus(AuctionStatus.SOLD).size()
                + auctionRepository.findByStatus(AuctionStatus.EXPIRED).size();
        long pendingReports = diagnosticRequestService.getCompletedReports(org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
        long approvedReports = diagnosticRequestService.countApprovedReports();

        return ResponseEntity.ok(Map.of(
                "totalVehicles", totalVehicles,
                "totalUsers", totalUsers,
                "activeAuctions", activeAuctions,
                "finishedAuctions", finishedAuctions,
                "pendingReports", pendingReports,
                "approvedReports", approvedReports
        ));
    }
}
