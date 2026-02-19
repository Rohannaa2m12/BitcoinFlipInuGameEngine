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
    PEASANT(0, "Peasant", BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.5)),
    DEGEN(1, "Degen", BigDecimal.valueOf(0.5), BigDecimal.valueOf(2)),
    WHALE(2, "Whale", BigDecimal.valueOf(2), BigDecimal.valueOf(10)),
    SATOSHI(3, "Satoshi", BigDecimal.valueOf(10), BigDecimal.valueOf(100));

    private final int index;
    private final String name;
    private final BigDecimal minEth;
    private final BigDecimal maxEth;

    GameTier(int index, String name, BigDecimal minEth, BigDecimal maxEth) {
        this.index = index;
        this.name = name;
        this.minEth = minEth;
        this.maxEth = maxEth;
    }

    public int getIndex() { return index; }
    public String getName() { return name; }
    public BigDecimal getMinEth() { return minEth; }
    public BigDecimal getMaxEth() { return maxEth; }

    public static GameTier forWager(BigDecimal eth) {
        for (GameTier t : values()) {
            if (eth.compareTo(t.minEth) >= 0 && eth.compareTo(t.maxEth) < 0) return t;
        }
        return SATOSHI;
    }
}

enum StreakType {
    NONE(0),
    WIN_STREAK(1),
    LOSS_STREAK(2);

    private final int id;

    StreakType(int id) { this.id = id; }
    public int getId() { return id; }
}

// ==================== Constants ====================

final class BFIConstants {
    static final int HOUSE_EDGE_BPS = 250;
    static final int BPS_DENOM = 10000;
    static final int WIN_MULTIPLIER_BPS = 9750;
    static final BigDecimal MIN_BET_ETH = new BigDecimal("0.01");
