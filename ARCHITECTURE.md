# TradePilot — Technical Architecture

> Companion to the [README](README.md). All diagrams and samples here are **illustrative**: they show the patterns used in production without exposing proprietary logic.

## 0. Component overview

```mermaid
flowchart TB
    UI[Discord UI<br/>alerts · buttons · embeds] --> MAIN

    subgraph MAIN["cryptospringboot"]
        DISC[JDA gateway + OAuth2 callback]
        AI[TradingCopilotAgent<br/>xAI Grok tool-use]
        SCREEN[Screener & alert engine]
        BILL[Subscription billing]
    end

    MO[marketobserver] -->|Redis + RabbitMQ| HQ[springboot-historic-queue<br/>indicators + conclusions]
    HQ -->|Redis| SCREEN
    SCREEN --> AI
    AI -->|typed tool calls| MAIN
    MAIN -->|order execution| EX[Binance · CoinEx<br/>REST APIs]
    MAIN -->|position data| PO[portfolioobserver<br/>risk management]
    BILL -->|meta-tx| POLY[Polygon / EVM<br/>USDT subscription]

    MO -.->|reads| EX

    style MAIN fill:#fef3c7,stroke:#d97706
    style HQ fill:#dbeafe,stroke:#2563eb
    style PO fill:#dbeafe,stroke:#2563eb
    style MO fill:#dcfce7,stroke:#16a34a
```

Four decoupled services, no HTTP calls between them — coordination happens exclusively through Redis and MongoDB. `cryptospringboot` is the only service users interact with directly; it owns the Discord gateway, the AI orchestration, the screener/alert logic, and subscription billing.

## 1. Grounded tool-use flow (the core pattern)

The assistant never answers market questions from model memory. Every factual claim is grounded in a tool call executed and validated by the platform:

```mermaid
sequenceDiagram
    actor U as User
    participant GW as Discord Gateway (JDA)
    participant OR as AI Orchestrator (TradingCopilotAgent)
    participant LLM as xAI Grok
    participant TS as Trading Service
    participant R as Redis

    U->>GW: clicks "AI Insight" on an alert
    GW->>OR: authenticated request (Discord identity)
    OR->>LLM: system prompt + pre-computed indicators + tool schemas
    LLM-->>OR: BUY / SELL / WAIT + optional tool_call (watch, limit order)
    OR->>OR: schema validation + confluence check
    OR->>TS: execute action tool (if any)
    TS->>R: read/write position & watch state
    R-->>TS: confirmation
    TS-->>OR: typed result (JSON)
    OR-->>U: grounded natural-language answer + action buttons
```

Design decisions worth noting:

- **Typed tools, not free text.** Every tool has a JSON Schema contract; malformed or out-of-scope calls are rejected before touching any service.
- **Pre-computed context, not raw numbers.** The model receives a Java-computed summary (regime, divergence, %B, timeframe alignment) ahead of the raw candle table — it is not asked to infer temporal patterns from a snapshot.
- **WAIT is a valid tool outcome.** The confluence rule (3+ independent signals for any directional call) is enforced at the prompt level, not left to model discretion.

## 2. Event-driven market data flow

```mermaid
flowchart LR
    EX1[Binance<br/>WebSocket + REST] --> ING[marketobserver]
    EX2[CoinEx<br/>REST] --> ING
    ING --> MQ[(RabbitMQ)]
    MQ --> NORM[springboot-historic-queue<br/>indicators + conclusions]
    NORM --> REDIS[(Redis<br/>hot state: asset:* snapshots,<br/>hist_tf_asset:* candle lists)]
    REDIS --> SERVE[cryptospringboot<br/>screener, alerts, AI]
```

- Ingestion is decoupled from serving: a burst on one exchange never blocks alert evaluation or AI responses.
- **Indicator state is bootstrapped from REST history on cold start** — a service restart never publishes non-converged (mathematically invalid) indicator values. This was the root cause fix behind the incident described in the README.
- Hot reads come exclusively from Redis — sub-millisecond, always the latest normalized snapshot.

## 3. Execution path (multi-exchange behind one abstraction)

```mermaid
flowchart TB
    ORCH[AI Orchestrator] -->|propose order| VAL[Order Validation<br/>lot size, risk profile, idempotency]
    VAL -->|approved, on user click| ROUTER[Execution Router]
    ROUTER --> BNB[Binance Adapter<br/>REST API · OTOCO brackets]
    ROUTER --> CNX[CoinEx Adapter<br/>REST API]
    BNB & CNX --> RISK[Risk Pilot<br/>trailing stop, weekly report]
```

- The LLM **proposes**; the platform **disposes**. No order reaches an adapter without the user's click on a Discord button.
- Orders ship as OTOCO brackets on Binance: entry + take-profit + stop-loss in one atomic call, sized from the asset's ATR and the user's risk profile. CoinEx has no OTOCO equivalent — falls back to a plain limit order.
- Once filled, the Risk Pilot takes over: stop-loss where none exists, automatic trailing stop as price runs in the user's favor.
- A separate billing service settles Intermediate/Premium subscriptions in USDT on Polygon (EVM) — Web3 as payments infrastructure, isolated from the trading path. Discord authorization links the user's ID to their wallet before the smart-contract subscription is signed.

## 4. Sanitized samples

See [`samples/`](samples/) for real, sanitized extracts covering exchange abstraction, incremental indicator state, Redis event handling, Discord interaction routing, OAuth identity capture, and the on-chain subscription contract.

---

*These documents describe architecture patterns only. Production code, trading strategies, prompts, and model configurations are proprietary.*
