package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathSegmentsTest {

  @Test
  void leavesAsciiSymbolUntouched() {
    assertThat(PathSegments.encode("AAPL")).isEqualTo("AAPL");
    assertThat(PathSegments.encode("AAPL230726C00200000")).isEqualTo("AAPL230726C00200000");
  }

  @Test
  void encodesSpacesAsPercent20NotPlus() {
    // %20 is the path-context encoding; "+" is the application/x-www-form-urlencoded dialect that
    // a strict path parser would treat literally.
    assertThat(PathSegments.encode("BRK A")).isEqualTo("BRK%20A");
  }

  @Test
  void encodesReservedAndUnicode() {
    assertThat(PathSegments.encode("$200")).isEqualTo("%24200");
    assertThat(PathSegments.encode("café")).isEqualTo("caf%C3%A9");
  }

  @Test
  void preservesSlashesAsSegmentSeparators() {
    // Slashes are kept because some endpoints accept multi-segment user input and the backend's
    // catch-all regex matches across them.
    assertThat(PathSegments.encode("AAPL 7/26/23 $200 Call"))
        .isEqualTo("AAPL%207/26/23%20%24200%20Call");
  }

  @Test
  void handlesLeadingTrailingAndDoubleSlashes() {
    assertThat(PathSegments.encode("/leading")).isEqualTo("/leading");
    assertThat(PathSegments.encode("trailing/")).isEqualTo("trailing/");
    assertThat(PathSegments.encode("a//b")).isEqualTo("a//b");
  }

  @Test
  void emptyStringReturnsEmpty() {
    assertThat(PathSegments.encode("")).isEqualTo("");
  }
}
