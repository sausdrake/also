package com.example.autoauction.admin.web;

import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.user.application.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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

    @GetMapping
    @Operation(summary = "Получить статистику дашборда")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        long totalAuctions = auctionRepository.findAll().size();
        long activeAuctions = auctionRepository.findByStatus(AuctionStatus.ACTIVE).size();
        long createdAuctions = auctionRepository.findByStatus(AuctionStatus.CREATED).size();
        long totalUsers = userService.countUsers();
        long pendingReports = diagnosticRequestService.getCompletedReports(PageRequest.of(0, 1000)).getTotalElements();
        long pendingRequests = diagnosticRequestService.getPendingRequests().size();

        return ResponseEntity.ok(Map.of(
                "totalAuctions", totalAuctions,
                "activeAuctions", activeAuctions,
                "createdAuctions", createdAuctions,
                "totalUsers", totalUsers,
                "pendingReports", pendingReports,
                "pendingRequests", pendingRequests
        ));
    }
}
