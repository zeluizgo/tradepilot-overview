// Extracted and sanitized from the Discord bot layer of an AI trading copilot.
// Two patterns worth showing: (1) button IDs are routed purely by string prefix —
// no shared registry, each handler owns its own namespace; (2) any handler that
// calls out to a slow AI/API call is offloaded to a dedicated executor so the
// gateway thread stays free to meet Discord's interaction deadlines.

// Dedicated thread pool for AI API calls — keeps the JDA WebSocket read thread free.
// Discord requires deferReply()/replyModal() within 3 seconds of receiving an
// interaction; an AI round-trip can take several seconds, so it must never run
// inline on the thread that received the event.
private static final ExecutorService AI_EXECUTOR = Executors.newCachedThreadPool();

public void onButtonInteraction(ButtonInteractionEvent event) {
    String componentId = event.getComponentId();

    // Modal-triggering buttons MUST reply with a modal before any deferReply/deferEdit —
    // Discord allows exactly one initial response per interaction, and once you defer,
    // replyModal() is no longer valid for that interaction.
    if (componentId != null && componentId.startsWith("setup_keys_")) {
        onboardingHandler.handleApiKeyModalTrigger(event);
        return;
    }

    // Every other button path acknowledges immediately with an ephemeral placeholder;
    // every exit path below eventually edits this same deferred reply.
    event.deferReply().setEphemeral(true).queue();

    if (componentId != null && componentId.startsWith("onboard_")) {
        onboardingHandler.handleButtonInteraction(event);
        event.getHook().editOriginal("✅").setComponents().queue();
        return;
    }

    if (!isUserAuthorized(event, event.getUser())) return;

    // Buttons that need an AI call or an external API round-trip are handed off to
    // AI_EXECUTOR — the deferred reply above already told Discord "we're working on it",
    // so there's no deadline pressure once we're on this thread.
    if (componentId != null && componentId.startsWith("aisu_")) {
        String insightId = componentId.substring("aisu_".length());
        AI_EXECUTOR.submit(() -> {
            try {
                String insightText = agentService.getInsightText(insightId);
                String sessionId   = agentService.getButtonSession(insightId);
                String agentJson   = agentService.buildLimitOrderPreviewDirect(insightId, insightText, sessionId);

                JsonNode envelope = objectMapper.readTree(agentJson);
                List<Button> buttons = parseActionButtons(envelope.path("actions"));
                sendDM(event.getUser(), envelope.path("text").asText(), buildActionRows(buttons));
                event.getHook().editOriginal("📬 Sent to your DMs!").setComponents().queue();
            } catch (Exception e) {
                log.error("[BUTTON] {} failed: {}", componentId, e.getMessage(), e);
                event.getHook().editOriginal("❌ Something went wrong: " + e.getMessage()).setComponents().queue();
            }
        });
    }
}
