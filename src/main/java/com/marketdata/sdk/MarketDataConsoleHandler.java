package com.marketdata.sdk;

import java.util.logging.ConsoleHandler;

/**
 * Marker subclass of {@link ConsoleHandler} so {@link MarketDataClient#configureLogging} can tell
 * its own handler apart from anything the host application or another library installed on the same
 * logger. The previous discriminator inspected {@code getFormatter() instanceof
 * MarketDataLogFormatter}, which is structurally accurate today but trips up the rare case of a
 * consumer attaching {@code MarketDataLogFormatter} to a vanilla {@code ConsoleHandler}.
 *
 * <p>No behavior beyond {@link ConsoleHandler}; the type identity is the whole point.
 */
final class MarketDataConsoleHandler extends ConsoleHandler {

  MarketDataConsoleHandler() {
    setFormatter(new MarketDataLogFormatter());
  }
}
