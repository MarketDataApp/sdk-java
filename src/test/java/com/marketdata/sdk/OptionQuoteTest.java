package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.options.Greek;
import com.marketdata.sdk.options.OptionQuote;
import org.junit.jupiter.api.Test;

/** {@code presentGreeks()} / {@code greek(Greek)} accessors on the options-quote row. */
class OptionQuoteTest {

  /** Build a quote whose only populated fields are the five greeks. */
  private static OptionQuote withGreeks(
      Double delta, Double gamma, Double theta, Double vega, Double rho) {
    return new OptionQuote(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, delta, gamma, theta, vega, rho);
  }

  @Test
  void presentGreeksReportsEveryPopulatedGreek() {
    OptionQuote q = withGreeks(0.1, 0.2, 0.3, 0.4, 0.5);

    assertThat(q.presentGreeks())
        .containsExactlyInAnyOrder(Greek.DELTA, Greek.GAMMA, Greek.THETA, Greek.VEGA, Greek.RHO);
    assertThat(q.greek(Greek.DELTA)).isEqualTo(0.1);
    assertThat(q.greek(Greek.GAMMA)).isEqualTo(0.2);
    assertThat(q.greek(Greek.THETA)).isEqualTo(0.3);
    assertThat(q.greek(Greek.VEGA)).isEqualTo(0.4);
    assertThat(q.greek(Greek.RHO)).isEqualTo(0.5);
  }

  @Test
  void absentGreeksAreEmptyAndNull() {
    OptionQuote q = withGreeks(null, null, null, null, null);

    assertThat(q.presentGreeks()).isEmpty();
    assertThat(q.greek(Greek.DELTA)).isNull();
    assertThat(q.greek(Greek.RHO)).isNull();
  }
}
