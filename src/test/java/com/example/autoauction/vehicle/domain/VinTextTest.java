package com.example.autoauction.vehicle.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VIN-AUTH-01: VinText")
class VinTextTest {

    @Test
    @DisplayName("removes the \"(VIN: …)\" fragment of a display name, leaves other text alone")
    void withoutVin() {
        assertThat(VinText.withoutVin("Toyota Camry 2019 (VIN: JTNB11HK503012345)")).isEqualTo("Toyota Camry 2019");
        assertThat(VinText.withoutVin("Аукцион \"BMW X5 2020 (VIN: WBA00000000000001)\" завершён. Победитель определён."))
                .isEqualTo("Аукцион \"BMW X5 2020\" завершён. Победитель определён.");
        assertThat(VinText.withoutVin("Toyota Camry 2019")).isEqualTo("Toyota Camry 2019");
        assertThat(VinText.withoutVin("Заморозка 10000 сом по аукциону #5 снята")).isEqualTo("Заморозка 10000 сом по аукциону #5 снята");
        assertThat(VinText.withoutVin(null)).isNull();
    }

    @Test
    @DisplayName("mask keeps only the last four characters")
    void mask() {
        assertThat(VinText.mask("JTNB11HK503012345")).isEqualTo("***2345");
        assertThat(VinText.mask("1234")).isEqualTo("***");
        assertThat(VinText.mask(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("an auction never hands out a stored VIN in its display name")
    void auctionGetter() {
        com.example.autoauction.auction.domain.Auction auction = new com.example.autoauction.auction.domain.Auction(
                1L, new java.math.BigDecimal("1000"), null, null, new java.math.BigDecimal("10"),
                java.time.OffsetDateTime.now().plusDays(1), java.time.OffsetDateTime.now().plusDays(2), 1L);
        auction.setVehicleInfo("Lexus RX 2018 (VIN: JTJBZMCA102000001)");
        assertThat(auction.getVehicleInfo()).isEqualTo("Lexus RX 2018");
    }
}
