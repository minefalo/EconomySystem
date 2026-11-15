package de.Minefalo.economySystem.config;

/**
 * Die beiden Zinsarten aus der Blockcraft-Wirtschaftsformel.
 */
public enum InterestType {

    SAVINGS("spar", "interest-type-savings"),
    CREDIT("kredit", "interest-type-credit");

    private final String configKey;
    private final String messageKey;

    InterestType(String configKey, String messageKey) {
        this.configKey = configKey;
        this.messageKey = messageKey;
    }

    /**
     * Schlüssel innerhalb von {@code interest.factors} in der settings.yml.
     */
    public String configKey() {
        return configKey;
    }

    /**
     * Nachrichten-Schlüssel für den Anzeigenamen.
     */
    public String messageKey() {
        return messageKey;
    }
}
