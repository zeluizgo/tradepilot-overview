// Extracted and sanitized from the on-chain subscription-billing layer of a SaaS
// product (Polygon mainnet, USDT payments via meta-transactions so the *user* never
// pays gas — a relayer wallet submits and pays for both legs). All contract
// addresses, the RPC endpoint, and the relayer's private key are externalized to
// application config / encrypted secrets — placeholders are shown where they'd sit.

@Value("${polygon.rpc.url}")                private String rpcUrl;              // e.g. an Infura/Alchemy Polygon endpoint
@Value("${contract.usdt.address}")          private String usdtAddress;         // 0xYOUR_USDT_CONTRACT_ADDRESS
@Value("${contract.forwarder.address}")     private String forwarderAddress;    // 0xYOUR_FORWARDER_CONTRACT_ADDRESS
@Value("${contract.subscription.address}")  private String subscriptionAddress; // 0xYOUR_SUBSCRIPTION_CONTRACT_ADDRESS
@Value("${relayer.wallet.key}")             private String relayerKey;          // never logged, never returned in any response

private Web3j       web3j;
private Credentials relayerCredentials;

@PostConstruct
public void init() {
    web3j = Web3j.build(new HttpService(rpcUrl));
    relayerCredentials = Credentials.create(relayerKey);
}

// ── Read: a plain eth_call against the subscription contract, no gas required ──────
public BigInteger[] getPlanPrices() throws Exception {
    Function intermediatePlan = new Function("priceIntermediate", List.of(), List.of(new TypeReference<Uint256>() {}));
    Function premiumPlan      = new Function("pricePremium",      List.of(), List.of(new TypeReference<Uint256>() {}));
    return new BigInteger[]{
        callUint256(subscriptionAddress, intermediatePlan),
        callUint256(subscriptionAddress, premiumPlan)
    };
}

private BigInteger callUint256(String contractAddress, Function fn) throws Exception {
    String encoded = FunctionEncoder.encode(fn);
    EthCall result = web3j.ethCall(
        Transaction.createEthCallTransaction(null, contractAddress, encoded),
        DefaultBlockParameterName.LATEST
    ).send();

    if (result.hasError()) throw new RuntimeException("Call error: " + result.getError().getMessage());
    List<Type> decoded = FunctionReturnDecoder.decode(result.getValue(), fn.getOutputParameters());
    return ((Uint256) decoded.get(0)).getValue();
}

// ── Write: relay a meta-transaction the USER signed off-chain, so THEY pay zero gas.
// The relayer wallet submits it and pays the real gas cost. ────────────────────────
private String relayUsdtApprove(RelayRequest req) throws Exception {
    // ERC-20 executeMetaTransaction(userAddress, functionSignature, r, s, v) — the
    // (r, s, v) signature was produced client-side by the user's own wallet, proving
    // they authorized this specific approval without ever broadcasting a transaction
    // or holding native MATIC for gas themselves.
    Function fn = new Function(
        "executeMetaTransaction",
        List.of(
            new Address(req.getWallet()),
            new DynamicBytes(Numeric.hexStringToByteArray(req.getUsdtFunctionSig())),
            new Bytes32(req.getUsdtR()),
            new Bytes32(req.getUsdtS()),
            new Uint8(BigInteger.valueOf(req.getUsdtV()))
        ),
        List.of()
    );
    return sendTransaction(usdtAddress, FunctionEncoder.encode(fn), GAS_USDT, BigInteger.ZERO);
}
