package com.example.autoauction.vehicle.web;

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

    @GetMapping("/{id}")
    public PublicVehicleResponse getVehicle(@PathVariable Long id) {
        return vehicleRepository.findById(id)
                .map(PublicVehicleResponse::from)   // SEC-12: public allowlist
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + id));
    }
}
