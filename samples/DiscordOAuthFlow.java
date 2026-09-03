// Extracted and sanitized from the Discord OAuth2 onboarding flow of an AI trading
// copilot. Two things worth showing: (1) the callback captures and pins the user's
// Discord ID from Discord's OWN identity system before anything wallet-related ever
// happens — the crypto-payment step later (a separate static page, since Discord's
// in-app browser can't run dApp JS) just receives this ID as a query param, it never
// re-derives identity itself; (2) client_id/client_secret/redirect_uri are injected
// from config, never hardcoded.

@GetMapping("/discord/callback")
public Mono<String> discordCallback(@RequestParam String code,
                                    @RequestParam(required = false) String state) {

    // state encodes which flow sent the user here. "sub_0" / "sub_1" = the in-Discord
    // "upgrade plan" flow: we only need the Discord ID at this point, so we exchange
    // the code, read the ID, and hand off to the subscribe page — the wallet-connection
    // step happens entirely on that separate page, keyed by this same ID.
    if (state != null && state.startsWith("sub_")) {
        String plan = state.substring(4);
        return oauthService.exchangeCodeForToken(code)
                .flatMap(tokenResponse -> {
                    String accessToken = tokenResponse.path("access_token").asText();
                    return oauthService.getUserInfo(accessToken)
                            .map(userInfo -> {
                                String discordId = userInfo.path("id").asText();
                                return "redirect:/subscribe.html?discordId=" + discordId + "&plan=" + plan;
                            });
                })
                .onErrorResume(e -> Mono.just("redirect:/error.html?msg=" + encode(e.getMessage())));
    }

    // Otherwise: full onboarding — exchange code, fetch identity, add to guild, persist,
    // send the first onboarding question as a DM.
    return oauthService.exchangeCodeForToken(code)
            .flatMap(tokenResponse -> {
                String accessToken = tokenResponse.path("access_token").asText();
                return oauthService.getUserInfo(accessToken)
                        .flatMap(userInfo -> {
                            String discordId = userInfo.path("id").asText();
                            return oauthService.addUserToGuild(discordId, accessToken)
                                    .then(oauthService.saveUser(userInfo, tokenResponse))
                                    .then(onboardingHandler.sendFirstQuestion(discordId));
                        });
            })
            .thenReturn("redirect:/success.html")
            .onErrorResume(e -> Mono.just("redirect:/error.html?msg=" + encode(e.getMessage())));
}

// ── The actual token exchange — a standard OAuth2 authorization-code grant ─────────
public Mono<JsonNode> exchangeCodeForToken(String code) {
    return discordWebClient.post()
            .uri("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("client_id", clientId)          // @Value-injected, never hardcoded
                    .with("client_secret", clientSecret)                    // @Value-injected, never logged
                    .with("grant_type", "authorization_code")
                    .with("code", code)
                    .with("redirect_uri", redirectUri))                     // @Value-injected per environment
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new RuntimeException("Token exchange failed: " + body))))
            .bodyToMono(JsonNode.class);
}
