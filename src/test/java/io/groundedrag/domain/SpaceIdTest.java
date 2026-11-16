package io.groundedrag.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpaceIdTest {

    @Test
    void normalisesCasingAndWhitespaceSoIngestAndQueryAgree() {
        assertThat(SpaceId.of("  RatedInventory ")).isEqualTo(SpaceId.of("RATEDINVENTORY"));
        assertThat(SpaceId.of("Bill-Preview").value()).isEqualTo("bill-preview");
    }

    @Test
    void rejectsValuesThatCouldBreakFiltersOrPaths() {
        assertThatThrownBy(() -> SpaceId.of("a,b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpaceId.of("../etc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpaceId.of("")).isInstanceOf(IllegalArgumentException.class);
    }
}
