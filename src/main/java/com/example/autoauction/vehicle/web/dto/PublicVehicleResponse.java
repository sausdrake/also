package com.example.autoauction.vehicle.web.dto;

import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.Vehicle;

/**
 * SEC-12: the vehicle as an anonymous visitor sees it on an auction page - exactly the fields that
 * page renders (allowlist). No licence plate, no diagnostician or administrator ids and usernames
 * (a public admin username is half of a login), no rejection reason, no workflow status or
 * timestamps - {@link VehicleResponse} remains the staff view.
 *
 * <p>VIN-AUTH-01: {@code vin} is null unless the viewer may see it ({@code VinDisclosurePolicy});
 * {@code vinHidden} then tells the page that a VIN exists but is withheld.
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
        String description,
        boolean vinHidden
) {
    public static PublicVehicleResponse from(VehicleResponse v, boolean mayViewVin) {
        boolean hidden = !mayViewVin && v.vin() != null;
        return new PublicVehicleResponse(v.id(), v.brand(), v.model(), v.year(), mayViewVin ? v.vin() : null, v.color(),
                v.mileage(), v.engineCapacity(), v.fuelType(), v.transmission(), v.description(), hidden);
    }

    public static PublicVehicleResponse from(Vehicle vehicle, boolean mayViewVin) {
        return from(VehicleResponse.fromDomain(vehicle), mayViewVin);
    }
}
