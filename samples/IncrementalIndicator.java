// Extracted and sanitized from the technical-indicator engine of a market-data
// pipeline. The service is stateless per tick — a fresh calculator instance is built
// on every update and seeded entirely from the previous tick's chain state (the
// lastSmoothedTR / lastEWMAGain / lastEMA12-style fields). This is what lets the
// service restart at any time without losing convergence: as long as the seed values
// are persisted somewhere durable, indicators pick up exactly where they left off
// instead of re-warming from zero.

public void update(Double close, Double high, Double low,
                    Double lastEWMAGain, Double lastEWMALoss,
                    Double lastEMA12, Double lastEMA26,
                    Double lastSmoothedTR, Double lastSmoothedPlusDM, Double lastSmoothedMinusDM,
                    List<Double> previousHighs, List<Double> previousLows, List<Double> previousCloses) {

    double prevHigh  = previousHighs.isEmpty()  ? high  : previousHighs.get(previousHighs.size() - 1);
    double prevLow   = previousLows.isEmpty()   ? low   : previousLows.get(previousLows.size() - 1);
    double prevClose = previousCloses.isEmpty() ? close : previousCloses.get(previousCloses.size() - 1);

    // ── ADX / +DI / -DI (Wilder smoothing) ──────────────────────────────────────
    double upMove   = high - prevHigh;
    double downMove = prevLow - low;
    double plusDM   = (upMove > downMove && upMove > 0)   ? upMove   : 0.0;
    double minusDM  = (downMove > upMove && downMove > 0) ? downMove : 0.0;
    double trueRange = trueRange(high, low, prevClose);

    double alphaWilder = 1.0 / 14;
    double smoothedTR      = calcEWMA(trueRange, orElse(lastSmoothedTR, 0.0),      alphaWilder);
    double smoothedPlusDM  = calcEWMA(plusDM,     orElse(lastSmoothedPlusDM, 0.0),  alphaWilder);
    double smoothedMinusDM = calcEWMA(minusDM,    orElse(lastSmoothedMinusDM, 0.0), alphaWilder);

    double plusDI  = smoothedTR < 1e-10 ? 0.0 : 100.0 * smoothedPlusDM  / smoothedTR;
    double minusDI = smoothedTR < 1e-10 ? 0.0 : 100.0 * smoothedMinusDM / smoothedTR;
    double dx      = (plusDI + minusDI) < 1e-10 ? 0.0 : 100.0 * Math.abs(plusDI - minusDI) / (plusDI + minusDI);

    // ── RSI — delta must be close-vs-previous-close, not close-vs-previous-high ──
    double change = close - prevClose;
    double avgGain = calcEWMA(Math.max(change, 0),  orElse(lastEWMAGain, 0.0), 1.0 / 14);
    double avgLoss = calcEWMA(Math.max(-change, 0), orElse(lastEWMALoss, 0.0), 1.0 / 14);
    this.rsi = calcRSI(avgGain, avgLoss);

    // ── EMA12 / EMA26 (feeds MACD) ────────────────────────────────────────────
    this.ema12 = calcEWMA(close, orElse(lastEMA12, 0.0), 2.0 / (12 + 1.0));
    this.ema26 = calcEWMA(close, orElse(lastEMA26, 0.0), 2.0 / (26 + 1.0));
    this.macd  = ema12 - ema26;

    // Publish the new chain state — the caller persists these fields so the *next*
    // tick (possibly after a restart) can seed correctly instead of starting from zero.
    this.lastEWMAGain      = avgGain;
    this.lastEWMALoss      = avgLoss;
    this.lastSmoothedTR    = smoothedTR;
    this.lastSmoothedPlusDM  = smoothedPlusDM;
    this.lastSmoothedMinusDM = smoothedMinusDM;
}

private double calcEWMA(double value, double previousEwma, double alpha) {
    if (Double.isNaN(value)) return previousEwma;       // skip bad data, don't corrupt the chain
    if (Double.isNaN(previousEwma)) return value;       // first tick ever — initialize, don't zero-seed
    return alpha * value + (1 - alpha) * previousEwma;
}
