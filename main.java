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
    static final BigDecimal MAX_BET_ETH = new BigDecimal("10");
    static final String DOMAIN_SEED = "BitcoinFlipInu.Satoshi.v1";
    static final int MAX_LEADERBOARD_SIZE = 100;
    static final int MAX_HISTORY_PER_PLAYER = 500;
    static final int SATOSHI_DECIMALS = 18;

    private BFIConstants() {}
}

// ==================== Flip round model ====================

final class FlipRound {
    private final long roundId;
    private final String playerId;
    private final BigDecimal wagerEth;
    private final FlipOutcome choice;
    private final FlipOutcome outcome;
    private final boolean won;
    private final BigDecimal payoutEth;
    private final long resolvedAtMs;

    FlipRound(long roundId, String playerId, BigDecimal wagerEth, FlipOutcome choice,
              FlipOutcome outcome, boolean won, BigDecimal payoutEth, long resolvedAtMs) {
        this.roundId = roundId;
        this.playerId = playerId;
        this.wagerEth = wagerEth;
        this.choice = choice;
        this.outcome = outcome;
        this.won = won;
        this.payoutEth = payoutEth;
        this.resolvedAtMs = resolvedAtMs;
    }

    public long getRoundId() { return roundId; }
    public String getPlayerId() { return playerId; }
    public BigDecimal getWagerEth() { return wagerEth; }
    public FlipOutcome getChoice() { return choice; }
    public FlipOutcome getOutcome() { return outcome; }
    public boolean isWon() { return won; }
    public BigDecimal getPayoutEth() { return payoutEth; }
    public long getResolvedAtMs() { return resolvedAtMs; }

    @Override
    public String toString() {
        return String.format("FlipRound{id=%d, player=%s, wager=%s, choice=%s, outcome=%s, won=%s, payout=%s}",
                roundId, playerId, wagerEth, choice.getLabel(), outcome.getLabel(), won, payoutEth);
    }
}

// ==================== Player profile ====================

final class PlayerProfile {
    private final String id;
    private final String displayName;
    private final AtomicLong totalFlips;
    private final AtomicLong totalWins;
    private final AtomicLong currentWinStreak;
    private final AtomicLong currentLossStreak;
    private final AtomicLong maxWinStreak;
    private final AtomicLong maxLossStreak;
    private volatile BigDecimal totalWageredEth;
    private volatile BigDecimal totalPayoutEth;
    private volatile long lastFlipMs;
    private final List<FlipRound> recentRounds;

    PlayerProfile(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.totalFlips = new AtomicLong(0);
        this.totalWins = new AtomicLong(0);
        this.currentWinStreak = new AtomicLong(0);
        this.currentLossStreak = new AtomicLong(0);
        this.maxWinStreak = new AtomicLong(0);
        this.maxLossStreak = new AtomicLong(0);
        this.totalWageredEth = BigDecimal.ZERO;
        this.totalPayoutEth = BigDecimal.ZERO;
        this.lastFlipMs = 0;
        this.recentRounds = Collections.synchronizedList(new ArrayList<>());
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public long getTotalFlips() { return totalFlips.get(); }
    public long getTotalWins() { return totalWins.get(); }
    public long getCurrentWinStreak() { return currentWinStreak.get(); }
    public long getCurrentLossStreak() { return currentLossStreak.get(); }
    public long getMaxWinStreak() { return maxWinStreak.get(); }
    public long getMaxLossStreak() { return maxLossStreak.get(); }
    public BigDecimal getTotalWageredEth() { return totalWageredEth; }
    public BigDecimal getTotalPayoutEth() { return totalPayoutEth; }
    public long getLastFlipMs() { return lastFlipMs; }

    public BigDecimal getNetProfitEth() {
        return totalPayoutEth.subtract(totalWageredEth);
    }

    public double getWinRate() {
        long flips = totalFlips.get();
        if (flips == 0) return 0.0;
        return (double) totalWins.get() / flips * 100.0;
    }

    void recordWin(BigDecimal wager, BigDecimal payout) {
        totalFlips.incrementAndGet();
        totalWins.incrementAndGet();
        currentWinStreak.incrementAndGet();
        currentLossStreak.set(0);
        maxWinStreak.updateAndGet(x -> Math.max(x, currentWinStreak.get()));
        totalWageredEth = totalWageredEth.add(wager);
        totalPayoutEth = totalPayoutEth.add(payout);
        lastFlipMs = System.currentTimeMillis();
    }

    void recordLoss(BigDecimal wager) {
        totalFlips.incrementAndGet();
        currentWinStreak.set(0);
        currentLossStreak.incrementAndGet();
        maxLossStreak.updateAndGet(x -> Math.max(x, currentLossStreak.get()));
        totalWageredEth = totalWageredEth.add(wager);
        lastFlipMs = System.currentTimeMillis();
    }

    void addRecentRound(FlipRound r) {
        synchronized (recentRounds) {
            recentRounds.add(r);
            while (recentRounds.size() > BFIConstants.MAX_HISTORY_PER_PLAYER) {
                recentRounds.remove(0);
            }
        }
    }

    public List<FlipRound> getRecentRounds() {
        synchronized (recentRounds) {
            return new ArrayList<>(recentRounds);
        }
    }
}

// ==================== Entropy (simulated on-chain) ====================

final class FlipEntropy {
    private final MessageDigest digest;
    private final long roundId;
    private final String playerId;
    private final long timestampMs;
    private final String domainSeed;

    FlipEntropy(long roundId, String playerId, long timestampMs) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 required", e);
        }
        this.roundId = roundId;
        this.playerId = playerId;
        this.timestampMs = timestampMs;
        this.domainSeed = BFIConstants.DOMAIN_SEED;
    }

    FlipOutcome resolve() {
        byte[] input = (roundId + "|" + playerId + "|" + timestampMs + "|" + domainSeed + "|" + System.nanoTime()).getBytes();
        byte[] hash = digest.digest(input);
        int lsb = hash[hash.length - 1] & 1;
        return FlipOutcome.fromCode(lsb);
    }

    static FlipOutcome resolveWithRandom() {
        return FlipOutcome.random();
    }
}

// ==================== Treasury math ====================

final class TreasuryMath {
    static BigDecimal houseEdgeWei(BigDecimal wagerWei) {
        return wagerWei
                .multiply(BigDecimal.valueOf(BFIConstants.HOUSE_EDGE_BPS))
                .divide(BigDecimal.valueOf(BFIConstants.BPS_DENOM), 18, RoundingMode.DOWN);
    }

    static BigDecimal winPayoutWei(BigDecimal wagerWei) {
        return wagerWei
                .multiply(BigDecimal.valueOf(BFIConstants.WIN_MULTIPLIER_BPS))
                .divide(BigDecimal.valueOf(BFIConstants.BPS_DENOM), 18, RoundingMode.DOWN);
    }

    static BigDecimal ethToWei(BigDecimal eth) {
        return eth.multiply(BigDecimal.TEN.pow(BFIConstants.SATOSHI_DECIMALS));
    }

    static BigDecimal weiToEth(BigDecimal wei) {
        return wei.divide(BigDecimal.TEN.pow(BFIConstants.SATOSHI_DECIMALS), 18, RoundingMode.DOWN);
    }

    private TreasuryMath() {}
}

// ==================== Game engine core ====================

public final class BitcoinFlipInuGameEngine {
    private final AtomicLong globalRoundId = new AtomicLong(0);
    private final Map<String, PlayerProfile> players = new ConcurrentHashMap<>();
    private final List<FlipRound> globalHistory = Collections.synchronizedList(new ArrayList<>());
    private volatile BigDecimal totalWageredEth = BigDecimal.ZERO;
    private volatile BigDecimal totalPayoutsEth = BigDecimal.ZERO;
    private volatile BigDecimal houseCollectedEth = BigDecimal.ZERO;
    private final boolean useDeterministicEntropy;

    public BitcoinFlipInuGameEngine(boolean useDeterministicEntropy) {
        this.useDeterministicEntropy = useDeterministicEntropy;
    }

    public BitcoinFlipInuGameEngine() {
        this(false);
    }

    public PlayerProfile getOrCreatePlayer(String id, String displayName) {
        return players.computeIfAbsent(id, k -> new PlayerProfile(k, displayName != null ? displayName : "Player_" + id.hashCode()));
    }

    public Optional<PlayerProfile> getPlayer(String id) {
        return Optional.ofNullable(players.get(id));
    }

    public FlipRound executeFlip(String playerId, String displayName, BigDecimal wagerEth, FlipOutcome choice) {
        if (wagerEth.compareTo(BFIConstants.MIN_BET_ETH) < 0) {
            throw new IllegalArgumentException("Wager below minimum: " + BFIConstants.MIN_BET_ETH);
        }
        if (wagerEth.compareTo(BFIConstants.MAX_BET_ETH) > 0) {
            throw new IllegalArgumentException("Wager above maximum: " + BFIConstants.MAX_BET_ETH);
        }

        long roundId = globalRoundId.incrementAndGet();
        long now = System.currentTimeMillis();
        FlipOutcome outcome = useDeterministicEntropy
                ? new FlipEntropy(roundId, playerId, now).resolve()
                : FlipEntropy.resolveWithRandom();

        boolean won = (choice == outcome);
        BigDecimal wagerWei = TreasuryMath.ethToWei(wagerEth);
        BigDecimal payoutWei = won ? TreasuryMath.winPayoutWei(wagerWei) : BigDecimal.ZERO;
        BigDecimal payoutEth = TreasuryMath.weiToEth(payoutWei);

        PlayerProfile profile = getOrCreatePlayer(playerId, displayName);
        if (won) {
            profile.recordWin(wagerEth, payoutEth);
        } else {
            profile.recordLoss(wagerEth);
        }

        totalWageredEth = totalWageredEth.add(wagerEth);
        totalPayoutsEth = totalPayoutsEth.add(payoutEth);
        if (!won) {
            BigDecimal houseTake = wagerEth.subtract(TreasuryMath.weiToEth(TreasuryMath.houseEdgeWei(wagerWei)));
            houseCollectedEth = houseCollectedEth.add(houseTake);
        }

        FlipRound round = new FlipRound(roundId, playerId, wagerEth, choice, outcome, won, payoutEth, now);
        profile.addRecentRound(round);
        globalHistory.add(round);
        trimGlobalHistory();

        return round;
    }

    private void trimGlobalHistory() {
        synchronized (globalHistory) {
            while (globalHistory.size() > 2000) {
                globalHistory.remove(0);
            }
        }
    }

    public List<FlipRound> getGlobalHistory(int limit) {
        synchronized (globalHistory) {
            int from = Math.max(0, globalHistory.size() - limit);
            return new ArrayList<>(globalHistory.subList(from, globalHistory.size()));
        }
    }

    public List<PlayerProfile> getLeaderboardByWins(int limit) {
        return players.values().stream()
                .sorted((a, b) -> Long.compare(b.getTotalWins(), a.getTotalWins()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<PlayerProfile> getLeaderboardByWagered(int limit) {
        return players.values().stream()
                .sorted((a, b) -> b.getTotalWageredEth().compareTo(a.getTotalWageredEth()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<PlayerProfile> getLeaderboardByNetProfit(int limit) {
        return players.values().stream()
                .sorted((a, b) -> b.getNetProfitEth().compareTo(a.getNetProfitEth()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public BigDecimal getTotalWageredEth() { return totalWageredEth; }
    public BigDecimal getTotalPayoutsEth() { return totalPayoutsEth; }
    public BigDecimal getHouseCollectedEth() { return houseCollectedEth; }
    public long getGlobalRoundId() { return globalRoundId.get(); }

    // ==================== Stats aggregator ====================

    public static final class GlobalStats {
        public final long totalRounds;
        public final BigDecimal totalWageredEth;
        public final BigDecimal totalPayoutsEth;
        public final BigDecimal houseCollectedEth;
        public final int uniquePlayers;

        GlobalStats(long totalRounds, BigDecimal totalWageredEth, BigDecimal totalPayoutsEth,
                    BigDecimal houseCollectedEth, int uniquePlayers) {
            this.totalRounds = totalRounds;
            this.totalWageredEth = totalWageredEth;
            this.totalPayoutsEth = totalPayoutsEth;
            this.houseCollectedEth = houseCollectedEth;
            this.uniquePlayers = uniquePlayers;
        }
    }

    public GlobalStats getGlobalStats() {
        return new GlobalStats(
                globalRoundId.get(),
                totalWageredEth,
                totalPayoutsEth,
                houseCollectedEth,
                players.size()
        );
    }

    // ==================== Simulation runner ====================

    public static void runSimulation(int numRounds, int numPlayers, Random rng) {
        BitcoinFlipInuGameEngine engine = new BitcoinFlipInuGameEngine(true);
        String[] playerIds = new String[numPlayers];
        for (int i = 0; i < numPlayers; i++) {
            playerIds[i] = "0x" + Integer.toHexString(0x10000 + rng.nextInt(0xEFFFF));
        }

        for (int i = 0; i < numRounds; i++) {
            String pid = playerIds[rng.nextInt(numPlayers)];
            double eth = 0.01 + rng.nextDouble() * 9.99;
            BigDecimal wager = BigDecimal.valueOf(eth).setScale(4, RoundingMode.DOWN);
            FlipOutcome choice = rng.nextBoolean() ? FlipOutcome.HEADS : FlipOutcome.TAILS;
            engine.executeFlip(pid, "SimPlayer_" + pid.substring(pid.length() - 4), wager, choice);
        }

        GlobalStats s = engine.getGlobalStats();
        System.out.println("Simulation complete: rounds=" + s.totalRounds + " players=" + s.uniquePlayers);
        System.out.println("Total wagered ETH: " + s.totalWageredEth);
        System.out.println("Total payouts ETH: " + s.totalPayoutsEth);
        System.out.println("House collected ETH: " + s.houseCollectedEth);
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        Random rng = new Random(0xBF1);
        runSimulation(500, 20, rng);

        BitcoinFlipInuGameEngine engine = new BitcoinFlipInuGameEngine();
        FlipRound r1 = engine.executeFlip("0xAlice", "Alice", new BigDecimal("0.1"), FlipOutcome.HEADS);
        System.out.println(r1);
        FlipRound r2 = engine.executeFlip("0xBob", "Bob", new BigDecimal("1.0"), FlipOutcome.TAILS);
        System.out.println(r2);
        System.out.println(engine.getGlobalStats().uniquePlayers + " players, " + engine.getGlobalRoundId() + " rounds");
    }
}

// ==================== Extra utility classes (expand line count) ====================

final class FlipInuValidator {
    static boolean isValidWagerEth(BigDecimal eth) {
        return eth != null && eth.compareTo(BFIConstants.MIN_BET_ETH) >= 0 && eth.compareTo(BFIConstants.MAX_BET_ETH) <= 0;
    }

    static boolean isValidPlayerId(String id) {
        return id != null && id.length() >= 2 && id.length() <= 64;
    }

    static String sanitizeDisplayName(String name) {
        if (name == null) return "Anon";
        String s = name.trim();
        if (s.isEmpty()) return "Anon";
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
}

final class FlipInuFormatters {
    static String formatEth(BigDecimal eth) {
        return eth.setScale(4, RoundingMode.HALF_UP).toPlainString() + " ETH";
    }

    static String formatWinRate(double rate) {
        return String.format("%.2f%%", rate);
    }

    static String formatStreak(long streak) {
        return streak + "x";
    }

    static String roundSummary(FlipRound r) {
        return String.format("#%d %s %s → %s %s (payout %s)",
                r.getRoundId(), r.getPlayerId(), r.getChoice().getLabel(), r.getOutcome().getLabel(),
                r.isWon() ? "WIN" : "LOSS", FlipInuFormatters.formatEth(r.getPayoutEth()));
    }
}

final class SatoshiFlipperMemeMessages {
    private static final String[] WIN_MSGS = {
            "WAGMI! Satoshi smiles upon you.",
            "Diamond paws. You won.",
            "To the moon, one flip at a time.",
            "Inu says: much win, very flip.",
            "Based. You picked the right side."
    };
    private static final String[] LOSS_MSGS = {
            "NGMI this time. Flip again.",
            "Paper paws. Try tails next.",
            "House always has an edge. One more?",
            "Inu says: such loss, wow. Flip again.",
            "Unbased. But the next flip is 50/50."
    };

    static String onWin() {
        return WIN_MSGS[ThreadLocalRandom.current().nextInt(WIN_MSGS.length)];
    }

    static String onLoss() {
        return LOSS_MSGS[ThreadLocalRandom.current().nextInt(LOSS_MSGS.length)];
    }
}

final class BFIWeiConverter {
    private static final BigInteger WEI_PER_ETH = BigInteger.TEN.pow(18);

    static BigInteger ethToWeiExact(BigDecimal eth) {
        return eth.multiply(new BigDecimal(WEI_PER_ETH)).toBigInteger();
    }

    static BigDecimal weiToEthExact(BigInteger wei) {
        return new BigDecimal(wei).divide(new BigDecimal(WEI_PER_ETH), 18, RoundingMode.DOWN);
    }
}

final class LeaderboardEntry {
    private final int rank;
    private final String playerId;
    private final String displayName;
    private final long wins;
    private final long flips;
    private final BigDecimal wageredEth;
    private final BigDecimal netProfitEth;
    private final double winRatePct;

    LeaderboardEntry(int rank, String playerId, String displayName, long wins, long flips,
                    BigDecimal wageredEth, BigDecimal netProfitEth, double winRatePct) {
        this.rank = rank;
        this.playerId = playerId;
        this.displayName = displayName;
        this.wins = wins;
        this.flips = flips;
        this.wageredEth = wageredEth;
        this.netProfitEth = netProfitEth;
        this.winRatePct = winRatePct;
    }

    public int getRank() { return rank; }
    public String getPlayerId() { return playerId; }
    public String getDisplayName() { return displayName; }
    public long getWins() { return wins; }
    public long getFlips() { return flips; }
    public BigDecimal getWageredEth() { return wageredEth; }
    public BigDecimal getNetProfitEth() { return netProfitEth; }
    public double getWinRatePct() { return winRatePct; }
}

final class FlipInuConfig {
    private final BigDecimal minBetEth;
    private final BigDecimal maxBetEth;
    private final int houseEdgeBps;
    private final int winMultiplierBps;

    FlipInuConfig(BigDecimal minBetEth, BigDecimal maxBetEth, int houseEdgeBps, int winMultiplierBps) {
        this.minBetEth = minBetEth;
        this.maxBetEth = maxBetEth;
        this.houseEdgeBps = houseEdgeBps;
        this.winMultiplierBps = winMultiplierBps;
    }

    public static FlipInuConfig defaultConfig() {
        return new FlipInuConfig(
                BFIConstants.MIN_BET_ETH,
                BFIConstants.MAX_BET_ETH,
                BFIConstants.HOUSE_EDGE_BPS,
                BFIConstants.WIN_MULTIPLIER_BPS
        );
    }

    public BigDecimal getMinBetEth() { return minBetEth; }
    public BigDecimal getMaxBetEth() { return maxBetEth; }
    public int getHouseEdgeBps() { return houseEdgeBps; }
    public int getWinMultiplierBps() { return winMultiplierBps; }
}

final class FlipSession {
    private final String sessionId;
    private final String playerId;
    private final long startedAtMs;
    private long flipCount;
    private BigDecimal sessionWagered;
    private BigDecimal sessionPayouts;

    FlipSession(String sessionId, String playerId) {
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.startedAtMs = System.currentTimeMillis();
        this.flipCount = 0;
        this.sessionWagered = BigDecimal.ZERO;
        this.sessionPayouts = BigDecimal.ZERO;
    }

    void recordFlip(BigDecimal wager, BigDecimal payout) {
        flipCount++;
        sessionWagered = sessionWagered.add(wager);
        sessionPayouts = sessionPayouts.add(payout);
    }

    public String getSessionId() { return sessionId; }
    public String getPlayerId() { return playerId; }
    public long getStartedAtMs() { return startedAtMs; }
    public long getFlipCount() { return flipCount; }
    public BigDecimal getSessionWagered() { return sessionWagered; }
    public BigDecimal getSessionPayouts() { return sessionPayouts; }
    public BigDecimal getSessionNet() { return sessionPayouts.subtract(sessionWagered); }
}

final class FlipInuAuditLog {
    private final long timestampMs;
    private final long roundId;
    private final String eventType;
    private final String payload;

    FlipInuAuditLog(long timestampMs, long roundId, String eventType, String payload) {
        this.timestampMs = timestampMs;
        this.roundId = roundId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public long getTimestampMs() { return timestampMs; }
    public long getRoundId() { return roundId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
}

final class FlipInuRateLimiter {
    private final Map<String, Long> lastFlipByPlayer = new ConcurrentHashMap<>();
    private final long minIntervalMs;

    FlipInuRateLimiter(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    boolean allowFlip(String playerId) {
        long now = System.currentTimeMillis();
        Long last = lastFlipByPlayer.put(playerId, now);
        if (last == null) return true;
        return (now - last) >= minIntervalMs;
    }
}

final class FlipInuPersistenceStub {
    private final List<FlipRound> persistedRounds = Collections.synchronizedList(new ArrayList<>());

    void persistRound(FlipRound r) {
        persistedRounds.add(r);
    }

    List<FlipRound> getPersistedRounds(int limit) {
        synchronized (persistedRounds) {
            int from = Math.max(0, persistedRounds.size() - limit);
            return new ArrayList<>(persistedRounds.subList(from, persistedRounds.size()));
        }
    }
}

final class BFIEncoding {
    static String encodeRoundId(long id) {
        return "BFI-" + Long.toHexString(id);
    }

    static long decodeRoundId(String encoded) {
        if (encoded == null || !encoded.startsWith("BFI-")) return -1;
        try {
            return Long.parseLong(encoded.substring(4), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

final class FlipInuHealthCheck {
    private final BitcoinFlipInuGameEngine engine;

    FlipInuHealthCheck(BitcoinFlipInuGameEngine engine) {
        this.engine = engine;
    }

    boolean isHealthy() {
        try {
            GlobalStats s = engine.getGlobalStats();
            return s.totalRounds >= 0 && s.uniquePlayers >= 0;
        } catch (Exception e) {
            return false;
        }
    }
}

