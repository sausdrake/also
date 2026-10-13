package com.example.autoauction.vehicle.web.dto;

import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.VehicleType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.annotation.Annotation;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation contract of {@link VehicleCreateRequest}: exactly the constraints declared on the record
 * components, checked with a plain Jakarta {@link Validator} (no Spring context). Assertions use the violating
 * property path and the constraint annotation type, never message text. All values are fixed literals.
 */
@DisplayName("VehicleCreateRequest validation")
class VehicleCreateRequestValidationTest {

    private static final String VALID_VIN = "1HGCM82633A004352"; // 17 characters

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    // ---------------------------------------------------------------------
    // Construction helpers (canonical constructor, record declaration order)
    // ---------------------------------------------------------------------

    private static VehicleCreateRequest request(String brand, String model, Integer year, String vin,
                                                VehicleType type, String licensePlate, String color,
                                                Integer mileage, Double engineCapacity, FuelType fuelType,
                                                String transmission, String description) {
        return new VehicleCreateRequest(brand, model, year, vin, type, licensePlate, color,
                mileage, engineCapacity, fuelType, transmission, description);
    }

    private static VehicleCreateRequest valid() {
        return request("Toyota", "Camry", 2020, VALID_VIN, VehicleType.SEDAN, "A123BC", "white",
                50000, 2.5, FuelType.PETROL, "automatic", "test vehicle");
    }

    private static VehicleCreateRequest withBrand(String brand) {
        VehicleCreateRequest v = valid();
        return request(brand, v.model(), v.year(), v.vin(), v.type(), v.licensePlate(), v.color(),
                v.mileage(), v.engineCapacity(), v.fuelType(), v.transmission(), v.description());
    }

    private static VehicleCreateRequest withModel(String model) {
        VehicleCreateRequest v = valid();
        return request(v.brand(), model, v.year(), v.vin(), v.type(), v.licensePlate(), v.color(),
                v.mileage(), v.engineCapacity(), v.fuelType(), v.transmission(), v.description());
    }

    private static VehicleCreateRequest withYear(Integer year) {
        VehicleCreateRequest v = valid();
        return request(v.brand(), v.model(), year, v.vin(), v.type(), v.licensePlate(), v.color(),
                v.mileage(), v.engineCapacity(), v.fuelType(), v.transmission(), v.description());
    }

    private static VehicleCreateRequest withVin(String vin) {
        VehicleCreateRequest v = valid();
        return request(v.brand(), v.model(), v.year(), vin, v.type(), v.licensePlate(), v.color(),
                v.mileage(), v.engineCapacity(), v.fuelType(), v.transmission(), v.description());
    }

    private static VehicleCreateRequest withType(VehicleType type) {
        VehicleCreateRequest v = valid();
        return request(v.brand(), v.model(), v.year(), v.vin(), type, v.licensePlate(), v.color(),
                v.mileage(), v.engineCapacity(), v.fuelType(), v.transmission(), v.description());
    }

    private static VehicleCreateRequest withMileage(Integer mileage) {
        VehicleCreateRequest v = valid();
        return request(v.brand(), v.model(), v.year(), v.vin(), v.type(), v.licensePlate(), v.color(),
                mileage, v.engineCapacity(), v.fuelType(), v.transmission(), v.description());
    }

    private static VehicleCreateRequest withEngineCapacity(Double engineCapacity) {
        VehicleCreateRequest v = valid();
        return request(v.brand(), v.model(), v.year(), v.vin(), v.type(), v.licensePlate(), v.color(),
                v.mileage(), engineCapacity, v.fuelType(), v.transmission(), v.description());
    }

    // ---------------------------------------------------------------------
    // Assertion helpers
    // ---------------------------------------------------------------------

    private static void assertValid(VehicleCreateRequest request) {
        assertThat(validator.validate(request)).isEmpty();
    }

    private static void assertSingle(VehicleCreateRequest request, String property,
                                     Class<? extends Annotation> annotation) {
        Set<ConstraintViolation<VehicleCreateRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        ConstraintViolation<VehicleCreateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo(property);
        assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType()).isEqualTo(annotation);
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("fully populated valid request has no violations")
    void validRequestHasNoViolations() {
        assertValid(valid());
    }

    @Nested
    @DisplayName("brand: @NotBlank @Size(min = 2, max = 50)")
    class Brand {

        @Test
        @DisplayName("whitespace-only brand violates @NotBlank")
        void blank() {
            assertSingle(withBrand("   "), "brand", NotBlank.class);
        }

        @Test
        @DisplayName("1 character violates @Size")
        void tooShort() {
            assertSingle(withBrand("A"), "brand", Size.class);
        }

        @Test
        @DisplayName("2 characters (minimum) are valid")
        void minimum() {
            assertValid(withBrand("AB"));
        }

        @Test
        @DisplayName("50 characters (maximum) are valid")
        void maximum() {
            assertValid(withBrand("B".repeat(50)));
        }

        @Test
        @DisplayName("51 characters violate @Size")
        void tooLong() {
            assertSingle(withBrand("B".repeat(51)), "brand", Size.class);
        }
    }

    @Nested
    @DisplayName("model: @NotBlank @Size(min = 1, max = 50)")
    class Model {

        @Test
        @DisplayName("whitespace-only model violates @NotBlank")
        void blank() {
            assertSingle(withModel("  "), "model", NotBlank.class);
        }

        @Test
        @DisplayName("1 character (minimum) is valid")
        void minimum() {
            assertValid(withModel("X"));
        }

        @Test
        @DisplayName("50 characters (maximum) are valid")
        void maximum() {
            assertValid(withModel("M".repeat(50)));
        }

        @Test
        @DisplayName("51 characters violate @Size")
        void tooLong() {
            assertSingle(withModel("M".repeat(51)), "model", Size.class);
        }
    }

    @Nested
    @DisplayName("year: @NotNull @Min(1900) @Max(2026)")
    class Year {

        @Test
        @DisplayName("null year violates @NotNull")
        void missing() {
            assertSingle(withYear(null), "year", NotNull.class);
        }

        @Test
        @DisplayName("1899 violates @Min")
        void belowMinimum() {
            assertSingle(withYear(1899), "year", Min.class);
        }

        @Test
        @DisplayName("1900 (minimum) is valid")
        void minimum() {
            assertValid(withYear(1900));
        }

        @Test
        @DisplayName("2026 (maximum) is valid")
        void maximum() {
            assertValid(withYear(2026));
        }

        @Test
        @DisplayName("2027 violates @Max")
        void aboveMaximum() {
            assertSingle(withYear(2027), "year", Max.class);
        }
    }

    @Nested
    @DisplayName("vin: @NotBlank @Size(min = 17, max = 17)")
    class Vin {

        @Test
        @DisplayName("17 spaces violate @NotBlank")
        void blank() {
            assertSingle(withVin(" ".repeat(17)), "vin", NotBlank.class);
        }

        @Test
        @DisplayName("3 characters violate @Size")
        void tooShort() {
            assertSingle(withVin("ABC"), "vin", Size.class);
        }

        @Test
        @DisplayName("exactly 17 characters are valid")
        void exactLength() {
            assertValid(withVin(VALID_VIN));
        }

        @Test
        @DisplayName("18 characters violate @Size")
        void tooLong() {
            assertSingle(withVin(VALID_VIN + "X"), "vin", Size.class);
        }
    }

    @Nested
    @DisplayName("type: @NotNull")
    class Type {

        @Test
        @DisplayName("null type violates @NotNull")
        void missing() {
            assertSingle(withType(null), "type", NotNull.class);
        }

        @ParameterizedTest(name = "{0} is valid")
        @EnumSource(VehicleType.class)
        @DisplayName("every VehicleType value is valid")
        void everyValue(VehicleType type) {
            assertValid(withType(type));
        }
    }

    @Nested
    @DisplayName("mileage: @Min(0), nullable")
    class Mileage {

        @Test
        @DisplayName("null mileage is valid")
        void missing() {
            assertValid(withMileage(null));
        }

        @Test
        @DisplayName("0 (minimum) is valid")
        void minimum() {
            assertValid(withMileage(0));
        }

        @Test
        @DisplayName("-1 violates @Min")
        void negative() {
            assertSingle(withMileage(-1), "mileage", Min.class);
        }
    }

    @Nested
    @DisplayName("engineCapacity: @DecimalMin(\"0.0\") inclusive, nullable")
    class EngineCapacity {

        @Test
        @DisplayName("null engine capacity is valid")
        void missing() {
            assertValid(withEngineCapacity(null));
        }

        @Test
        @DisplayName("0.0 (inclusive minimum) is valid")
        void minimum() {
            assertValid(withEngineCapacity(0.0));
        }

        @Test
        @DisplayName("-0.1 violates @DecimalMin")
        void negative() {
            assertSingle(withEngineCapacity(-0.1), "engineCapacity", DecimalMin.class);
        }
    }

    @Nested
    @DisplayName("unconstrained components: licensePlate, color, fuelType, transmission, description")
    class Unconstrained {

        @Test
        @DisplayName("all unconstrained components null produce no violations")
        void allNull() {
            VehicleCreateRequest v = valid();
            assertValid(request(v.brand(), v.model(), v.year(), v.vin(), v.type(), null, null,
                    v.mileage(), v.engineCapacity(), null, null, null));
        }

        @Test
        @DisplayName("ordinary values for unconstrained components produce no violations")
        void ordinaryValues() {
            assertValid(request("Toyota", "Camry", 2020, VALID_VIN, VehicleType.SEDAN, "B777OP", "black",
                    50000, 2.5, FuelType.DIESEL, "manual", "second test vehicle"));
        }
    }
}
