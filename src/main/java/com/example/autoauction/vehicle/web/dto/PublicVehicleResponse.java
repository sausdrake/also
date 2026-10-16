package com.example.autoauction.vehicle.web.dto;

import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.Vehicle;

/**
 * SEC-12: the vehicle as an anonymous visitor sees it on an auction page - exactly the fields that
 * page renders (allowlist). No licence plate, no diagnostician or administrator ids and usernames
 * (a public admin username is half of a login), no rejection reason, no workflow status or
 * timestamps - {@link VehicleResponse} remains the staff view.
 */
public record PublicVehicleResponse(
        Long id,
        String brand,
        String model,
        Integer year,
        String vin,
        String color,
        Integer mileage,
        Double engineCapacity,
        FuelType fuelType,
        String transmission,
        String description
) {
    public static PublicVehicleResponse from(VehicleResponse v) {
        return new PublicVehicleResponse(v.id(), v.brand(), v.model(), v.year(), v.vin(), v.color(),
                v.mileage(), v.engineCapacity(), v.fuelType(), v.transmission(), v.description());
    }

    public static PublicVehicleResponse from(Vehicle vehicle) {
        return from(VehicleResponse.fromDomain(vehicle));
    }
}
