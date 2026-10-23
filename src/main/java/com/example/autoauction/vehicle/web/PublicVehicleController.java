package com.example.autoauction.vehicle.web;

import com.example.autoauction.identity.application.VinDisclosurePolicy;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import com.example.autoauction.vehicle.web.dto.PublicVehicleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/vehicles")
@RequiredArgsConstructor
public class PublicVehicleController {

    private final VehicleRepository vehicleRepository;
    private final VinDisclosurePolicy vinDisclosurePolicy;

    @GetMapping("/{id}")
    public PublicVehicleResponse getVehicle(@PathVariable Long id) {
        boolean mayViewVin = vinDisclosurePolicy.currentViewerMaySeeVin();   // VIN-AUTH-01
        return vehicleRepository.findById(id)
                .map(v -> PublicVehicleResponse.from(v, mayViewVin))   // SEC-12: public allowlist
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + id));
    }
}
