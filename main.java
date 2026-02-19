import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Satoshi Flipper — Unified game engine for Bitcoin Flip Inu meme coin flip.
 * V2: Extended analytics, double-flip mode, streak bonuses, and migration helpers.
 * Simulates on-chain flip logic, player stats, leaderboards, and treasury math.
 * Single-file build; no external game data required.
 */

// ==================== Enums ====================

enum FlipOutcome {
    HEADS(0, "HEADS", "🪙"),
    TAILS(1, "TAILS", "🐕");

    private final int code;
    private final String label;
    private final String emoji;

    FlipOutcome(int code, String label, String emoji) {
        this.code = code;
        this.label = label;
        this.emoji = emoji;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }
    public String getEmoji() { return emoji; }

    public static FlipOutcome fromCode(int c) {
        return c == 0 ? HEADS : TAILS;
    }

    public static FlipOutcome random() {
        return ThreadLocalRandom.current().nextBoolean() ? HEADS : TAILS;
    }
}

enum GameTier {
