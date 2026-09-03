// Extracted and sanitized from the alert engine of a market-data pipeline. Redis
// keyspace notifications drive the whole flow: a downstream service writes an
// updated market snapshot, this listener reacts to the write event, evaluates
// alert conditions against it, and dispatches to Discord — no polling anywhere.

@PostConstruct
public void initListener() {
    // Subscribe to DEL events on "ignore:*" flags. A companion service sets a short-
    // lived "ignore:{key}" flag while it's mid-write to a snapshot key; when that flag
    // expires/is deleted, this listener knows the snapshot is now safe to read and
    // fully consistent — a lightweight write-barrier without a distributed lock.
    redisTemplate.listenToChannel("__keyevent@0__:del")
            .map(Message::getMessage)
            .filter(key -> key.startsWith(IGNORE_PREFIX))
            .subscribe(key -> handleSnapshotReady(key).subscribe());
}

private Mono<Void> handleSnapshotReady(String ignoreKey) {
    String assetKey = ignoreKey.substring(IGNORE_PREFIX.length());

    return getAsset(assetKey)
            .flatMap(asset -> watchStrategy(asset).then(checkWatchConditions(asset)))
            .doOnError(e -> log.error("Error processing snapshot for {}", assetKey, e))
            .then();
}

// ── Alert evaluation + dispatch, gated on a shared volatility check ────────────────
private Mono<Void> watchStrategy(CryptoAsset asset) {
    AssetGroupThreshold threshold = thresholdConfigService.getThreshold(asset.getSymbol());

    // A flat-peg or thin-book asset can pin momentum indicators to an extreme reading
    // that's mathematically correct but semantically meaningless — this gate runs
    // once, ahead of every strategy below, using only fields already on the snapshot
    // (zero extra Redis round-trips).
    boolean volatilityOk = passesVolatilityGate(asset, threshold);

    boolean volumeSpike = asset.getPrevTimestamp() != null
            && volumeRatio(asset) > threshold.getVolumeMultiplier();

    // A raw volume spike carries no directional information by itself, so confirming
    // it needs a short async read of recent candles — evaluated reactively and
    // chained ahead of the rest of the pipeline rather than blocking on it.
    Mono<Void> momentumAlert = (volumeSpike && volatilityOk && discordMessageService.isRadarPicos())
            ? evaluateMomentumDirection(asset, threshold)
                .flatMap(evaluation -> sendMomentumAlert(evaluation, asset, threshold))
            : Mono.empty();

    return momentumAlert;
}
