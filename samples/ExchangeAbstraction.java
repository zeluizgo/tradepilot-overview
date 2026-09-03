// Extracted and sanitized from the exchange integration layer of a multi-exchange
// crypto trading bot. Real values (API endpoints, credentials) are never hardcoded —
// they're injected per-user from encrypted storage. Shown here: the interface every
// exchange implements, and how two exchanges with genuinely different REST field
// names (Binance vs. CoinEx v2) are unified behind one lookup so order-building code
// never branches on which exchange it's talking to.

public abstract class AbstractExchangeService {

    protected String apiKey;
    protected String secretKey;

    public abstract String executeOrder(Map<String, String> parameters) throws Exception;

    public abstract String listOrders(Map<String, String> parameters) throws Exception;

    public abstract String balance() throws Exception;

    public abstract String ticker(Map<String, String> parameters) throws Exception;

    /** Translates a canonical field name (e.g. "quantity") into this exchange's own request key. */
    public abstract String getRequestKey(String key);

    /** Translates this exchange's own response key back into the canonical field name. */
    public abstract String getResponseKey(String key);

    /** Returns a new instance of this service configured with the given user keys —
     *  each order call runs with the calling user's own credentials, not a shared bot key. */
    public abstract AbstractExchangeService withKeys(String apiKey, String secretKey);
}

// ── Binance: field names largely match the canonical ones ──────────────────────────
public class BinanceService extends AbstractExchangeService {

    private static final Map<String, String> MAPPING_REQUEST_KEY_PARAMS = new LinkedHashMap<>();
    static {
        MAPPING_REQUEST_KEY_PARAMS.put("symbol",   "symbol");
        MAPPING_REQUEST_KEY_PARAMS.put("side",     "side");
        MAPPING_REQUEST_KEY_PARAMS.put("quantity", "quantity");
        MAPPING_REQUEST_KEY_PARAMS.put("price",    "price");
    }

    @Override
    public String getRequestKey(String key) {
        return MAPPING_REQUEST_KEY_PARAMS.getOrDefault(key, "Unknown");
    }
}

// ── CoinEx v2: different field names for the same concepts (market vs. symbol,
// amount vs. quantity) — the mapping table absorbs the difference so
// OrderFactory's order-building logic is written once, against the canonical keys. ──
public class CoinExService extends AbstractExchangeService {

    private static final Map<String, String> MAPPING_REQUEST_KEY_PARAMS = new LinkedHashMap<>();
    static {
        MAPPING_REQUEST_KEY_PARAMS.put("symbol",   "market");   // CoinEx calls it "market"
        MAPPING_REQUEST_KEY_PARAMS.put("side",     "side");
        MAPPING_REQUEST_KEY_PARAMS.put("quantity", "amount");   // CoinEx calls it "amount"
        MAPPING_REQUEST_KEY_PARAMS.put("price",    "price");
    }

    @Override
    public String getRequestKey(String key) {
        return MAPPING_REQUEST_KEY_PARAMS.getOrDefault(key, "Unknown");
    }

    @Override
    public String executeOrder(Map<String, String> parameters) throws Exception {
        // CoinEx v2's /v2/spot/order has no timeInForce field at all — sending it causes
        // a hard 4004 "invalid argument" rejection, unlike Binance which just ignores
        // unrecognized optional fields. This kind of exchange-specific quirk is exactly
        // why each implementation, not the shared interface, owns its own field wiring.
        parameters.remove("timeInForce");
        parameters.put(getRequestKey("side"), parameters.get(getRequestKey("side")).toLowerCase());
        return callCoinExV2("/v2/spot/order", HttpMethod.POST, parameters);
    }
}
