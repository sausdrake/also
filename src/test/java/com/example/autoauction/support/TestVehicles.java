package com.example.autoauction.support;

import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A real vehicle row for tests that build auctions directly. Since AUC-NOSALE-01 / BUYNOW-01 the auction
 * lifecycle moves its vehicle (Buy Now: APPROVED -> IN_AUCTION; EXPIRED / CANCELLED: -> NEEDS_REDIAGNOSTIC)
 * and fails closed when the vehicle does not exist, so a made-up vehicle id is no longer enough.
 */
public final class TestVehicles {

    private static final AtomicLong SEQ = new AtomicLong();

    private TestVehicles() {
    }

    public static Long create(VehicleRepository repository, VehicleStatus status) {
        String vin = ("TV" + System.nanoTime() + SEQ.incrementAndGet() + "000000000000000").substring(0, 17);
        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, vin, VehicleType.SEDAN, 9L, "diag");
        vehicle.setStatus(status);
        return repository.save(vehicle).getId();
    }

    public static Long approved(VehicleRepository repository) {
        return create(repository, VehicleStatus.APPROVED);
    }
}
