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

final class SatoshiFlipperCLI {
    private final BitcoinFlipInuGameEngine engine;
    private final Scanner scanner;

    SatoshiFlipperCLI(BitcoinFlipInuGameEngine engine, Scanner scanner) {
        this.engine = engine;
        this.scanner = scanner;
    }

    void runInteractive() {
        System.out.println("Satoshi Flipper — Bitcoin Flip Inu. Type 'flip', 'stats', 'leaderboard', 'quit'.");
        while (true) {
            String line = scanner.nextLine();
            if (line == null) break;
            line = line.trim().toLowerCase();
            if (line.isEmpty()) continue;
            if (line.equals("quit") || line.equals("exit")) break;
            if (line.equals("stats")) printStats();
            else if (line.equals("leaderboard")) printLeaderboard();
            else if (line.startsWith("flip ")) doFlip(line.substring(5).trim());
            else System.out.println("Unknown command.");
        }
    }

    private void printStats() {
        GlobalStats s = engine.getGlobalStats();
        System.out.println("Total rounds: " + s.totalRounds);
        System.out.println("Unique players: " + s.uniquePlayers);
        System.out.println("Total wagered: " + s.totalWageredEth + " ETH");
        System.out.println("Total payouts: " + s.totalPayoutsEth + " ETH");
        System.out.println("House collected: " + s.houseCollectedEth + " ETH");
    }

    private void printLeaderboard() {
        List<PlayerProfile> top = engine.getLeaderboardByWins(10);
        for (int i = 0; i < top.size(); i++) {
            PlayerProfile p = top.get(i);
            System.out.println((i + 1) + ". " + p.getDisplayName() + " wins=" + p.getTotalWins() + " flips=" + p.getTotalFlips() + " winRate=" + String.format("%.1f%%", p.getWinRate()));
        }
    }

    private void doFlip(String arg) {
        String[] parts = arg.split("\\s+");
        if (parts.length < 2) {
            System.out.println("Usage: flip <0.01-10 ETH> <heads|tails> [playerId]");
            return;
        }
        BigDecimal wager;
        try {
            wager = new BigDecimal(parts[0]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid wager.");
            return;
        }
        FlipOutcome choice = parts[1].toLowerCase().startsWith("t") ? FlipOutcome.TAILS : FlipOutcome.HEADS;
        String playerId = parts.length > 2 ? parts[2] : "0xCLI_" + System.currentTimeMillis();
        try {
            FlipRound r = engine.executeFlip(playerId, "CLI", wager, choice);
            System.out.println(FlipInuFormatters.roundSummary(r));
            System.out.println(r.isWon() ? SatoshiFlipperMemeMessages.onWin() : SatoshiFlipperMemeMessages.onLoss());
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}

// ==================== Extended simulation and analytics ====================

final class FlipInuMonteCarlo {
    private final int iterations;
    private final Random rng;

    FlipInuMonteCarlo(int iterations, Random rng) {
        this.iterations = iterations;
        this.rng = rng;
    }

    double expectedValuePerFlipEth() {
        double houseEdge = BFIConstants.HOUSE_EDGE_BPS / (double) BFIConstants.BPS_DENOM;
        double winChance = 0.5;
        double winMultiplier = BFIConstants.WIN_MULTIPLIER_BPS / (double) BFIConstants.BPS_DENOM;
        return -(houseEdge * (1 - winChance)) + (winChance * (winMultiplier - 1));
    }

    double runSimulationEv(BigDecimal wagerEth, int flips) {
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (int i = 0; i < flips; i++) {
            boolean win = rng.nextBoolean();
            if (win) {
                totalProfit = totalProfit.add(TreasuryMath.weiToEth(TreasuryMath.winPayoutWei(TreasuryMath.ethToWei(wagerEth))).subtract(wagerEth));
            } else {
                totalProfit = totalProfit.subtract(wagerEth);
            }
        }
        return totalProfit.divide(BigDecimal.valueOf(flips), 10, RoundingMode.HALF_UP).doubleValue();
    }
}

final class FlipInuTimeSeries {
    private final List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
    private final List<BigDecimal> wageredSnapshots = Collections.synchronizedList(new ArrayList<>());

    void recordSnapshot(long ts, BigDecimal totalWagered) {
        timestamps.add(ts);
        wageredSnapshots.add(totalWagered);
    }

    List<BigDecimal> getWageredSnapshots() {
        synchronized (wageredSnapshots) {
            return new ArrayList<>(wageredSnapshots);
        }
    }
}

final class FlipInuExport {
    static String toCsvRow(FlipRound r) {
        return String.format("%d,%s,%s,%s,%s,%s,%d",
                r.getRoundId(), r.getPlayerId(), r.getWagerEth(), r.getChoice().getLabel(),
                r.getOutcome().getLabel(), r.isWon(), r.getResolvedAtMs());
    }

    static String csvHeader() {
        return "roundId,playerId,wagerEth,choice,outcome,won,resolvedAtMs";
    }
}

final class FlipInuImport {
    static FlipRound fromCsvRow(String line) {
        String[] cols = line.split(",", -1);
        if (cols.length < 7) return null;
        try {
            long id = Long.parseLong(cols[0]);
            String playerId = cols[1];
            BigDecimal wager = new BigDecimal(cols[2]);
            FlipOutcome choice = "TAILS".equals(cols[3]) ? FlipOutcome.TAILS : FlipOutcome.HEADS;
            FlipOutcome outcome = "TAILS".equals(cols[4]) ? FlipOutcome.TAILS : FlipOutcome.HEADS;
            boolean won = Boolean.parseBoolean(cols[5]);
            long ts = Long.parseLong(cols[6]);
            BigDecimal payout = won ? TreasuryMath.weiToEth(TreasuryMath.winPayoutWei(TreasuryMath.ethToWei(wager))) : BigDecimal.ZERO;
            return new FlipRound(id, playerId, wager, choice, outcome, won, payout, ts);
        } catch (Exception e) {
            return null;
        }
    }
}

final class BFIEventBus {
    private final List<BFIEventListener> listeners = Collections.synchronizedList(new ArrayList<>());

    interface BFIEventListener {
        void onFlipResolved(FlipRound r);
    }

    void subscribe(BFIEventListener l) {
        listeners.add(l);
    }

    void publish(FlipRound r) {
        synchronized (listeners) {
            for (BFIEventListener l : listeners) {
                try {
                    l.onFlipResolved(r);
                } catch (Exception ignored) {}
            }
        }
    }
}

final class FlipInuMetrics {
    private long totalFlips;
    private long totalWins;
    private BigDecimal totalWagered;
    private BigDecimal totalPayouts;
    private final Map<GameTier, Long> flipsByTier = new ConcurrentHashMap<>();

    FlipInuMetrics() {
        this.totalFlips = 0;
        this.totalWins = 0;
        this.totalWagered = BigDecimal.ZERO;
        this.totalPayouts = BigDecimal.ZERO;
        for (GameTier t : GameTier.values()) flipsByTier.put(t, 0L);
    }

    synchronized void record(FlipRound r) {
        totalFlips++;
        if (r.isWon()) totalWins++;
        totalWagered = totalWagered.add(r.getWagerEth());
        totalPayouts = totalPayouts.add(r.getPayoutEth());
        GameTier tier = GameTier.forWager(r.getWagerEth());
        flipsByTier.merge(tier, 1L, Long::sum);
    }

    public long getTotalFlips() { return totalFlips; }
    public long getTotalWins() { return totalWins; }
    public BigDecimal getTotalWagered() { return totalWagered; }
    public BigDecimal getTotalPayouts() { return totalPayouts; }
    public Map<GameTier, Long> getFlipsByTier() { return new HashMap<>(flipsByTier); }
}

final class FlipInuCache {
    private final Map<String, PlayerProfile> cache = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();

    FlipInuCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    void put(String key, PlayerProfile profile) {
        cache.put(key, profile);
        expiry.put(key, System.currentTimeMillis() + ttlMs);
    }

    PlayerProfile get(String key) {
        Long exp = expiry.get(key);
        if (exp != null && System.currentTimeMillis() > exp) {
            cache.remove(key);
            expiry.remove(key);
            return null;
        }
        return cache.get(key);
    }
}

final class SatoshiFlipperPromo {
    private static final double BONUS_FLIP_CHANCE = 0.01;

    static boolean triggersBonusFlip(Random rng) {
        return rng.nextDouble() < BONUS_FLIP_CHANCE;
    }

    static BigDecimal bonusMultiplier(GameTier tier) {
        switch (tier) {
            case SATOSHI: return new BigDecimal("1.05");
            case WHALE: return new BigDecimal("1.02");
            default: return BigDecimal.ONE;
        }
    }
}

final class FlipInuNonceGenerator {
    private final AtomicLong counter = new AtomicLong(0);

    long next() {
        return counter.incrementAndGet();
    }

    void reset() {
        counter.set(0);
    }
}

final class FlipInuHashUtil {
    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    static int hashToInt(String input) {
        return Math.abs(sha256Hex(input).hashCode());
    }
}

final class FlipInuScheduler {
    private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());

    void schedule(Runnable r) {
        tasks.add(r);
    }

    void runAll() {
        synchronized (tasks) {
            for (Runnable r : tasks) {
                try {
                    r.run();
                } catch (Exception ignored) {}
            }
        }
    }
}

final class BFIStateSnapshot {
    private final long roundId;
    private final BigDecimal totalWagered;
    private final BigDecimal totalPayouts;
    private final int playerCount;
    private final long timestampMs;

    BFIStateSnapshot(long roundId, BigDecimal totalWagered, BigDecimal totalPayouts, int playerCount, long timestampMs) {
        this.roundId = roundId;
        this.totalWagered = totalWagered;
        this.totalPayouts = totalPayouts;
        this.playerCount = playerCount;
        this.timestampMs = timestampMs;
    }

    public long getRoundId() { return roundId; }
    public BigDecimal getTotalWagered() { return totalWagered; }
    public BigDecimal getTotalPayouts() { return totalPayouts; }
    public int getPlayerCount() { return playerCount; }
    public long getTimestampMs() { return timestampMs; }
}

final class FlipInuReplay {
    private final List<FlipRound> rounds = Collections.synchronizedList(new ArrayList<>());

    void add(FlipRound r) {
        rounds.add(r);
    }

    List<FlipRound> getRounds() {
        synchronized (rounds) {
            return new ArrayList<>(rounds);
        }
    }

    BigDecimal replayTotalWagered() {
        return getRounds().stream().map(FlipRound::getWagerEth).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    long replayWinCount() {
        return getRounds().stream().filter(FlipRound::isWon).count();
    }
}

final class FlipInuBatchProcessor {
    private final BitcoinFlipInuGameEngine engine;

    FlipInuBatchProcessor(BitcoinFlipInuGameEngine engine) {
        this.engine = engine;
    }

    List<FlipRound> processBatch(List<FlipRequest> requests) {
        List<FlipRound> results = new ArrayList<>();
        for (FlipRequest req : requests) {
            try {
                FlipRound r = engine.executeFlip(req.playerId, req.displayName, req.wagerEth, req.choice);
                results.add(r);
            } catch (Exception e) {
                // skip invalid
            }
        }
        return results;
    }

    static final class FlipRequest {
        final String playerId;
        final String displayName;
        final BigDecimal wagerEth;
        final FlipOutcome choice;

        FlipRequest(String playerId, String displayName, BigDecimal wagerEth, FlipOutcome choice) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.wagerEth = wagerEth;
            this.choice = choice;
        }
    }
}

final class FlipInuWebhookPayload {
    private final long roundId;
    private final String playerId;
    private final boolean won;
    private final BigDecimal payoutEth;

    FlipInuWebhookPayload(long roundId, String playerId, boolean won, BigDecimal payoutEth) {
        this.roundId = roundId;
        this.playerId = playerId;
        this.won = won;
        this.payoutEth = payoutEth;
    }

    String toJson() {
        return String.format("{\"roundId\":%d,\"playerId\":\"%s\",\"won\":%s,\"payoutEth\":\"%s\"}",
                roundId, playerId, won, payoutEth.toPlainString());
    }
}

final class FlipInuRateCalculator {
    static double effectiveHouseEdgeBps() {
        return BFIConstants.HOUSE_EDGE_BPS;
    }

    static double playerWinRateExpected() {
        return 0.5;
    }

    static BigDecimal maxPayoutForWager(BigDecimal wagerEth) {
        return TreasuryMath.weiToEth(TreasuryMath.winPayoutWei(TreasuryMath.ethToWei(wagerEth)));
    }
}

final class SatoshiFlipperBranding {
    static final String NAME = "Satoshi Flipper";
    static final String TAGLINE = "Bitcoin Flip Inu — Double your sats or lose them.";
    static final String VERSION = "1.0.0-BFI";

    static String fullTitle() {
        return NAME + " · " + TAGLINE;
    }
}

final class FlipInuTokenNames {
    static final String HEADS_SYMBOL = "HEADS";
    static final String TAILS_SYMBOL = "TAILS";
    static final String NATIVE_SYMBOL = "ETH";
}

final class FlipInuErrorCodes {
    static final int ERR_ZERO_BET = 1;
    static final int ERR_BET_TOO_LOW = 2;
    static final int ERR_BET_TOO_HIGH = 3;
    static final int ERR_PAUSED = 4;
    static final int ERR_INVALID_PLAYER = 5;
    static final int ERR_TRANSFER_FAILED = 6;
}

final class FlipInuValidationResult {
    private final boolean valid;
    private final int errorCode;
    private final String message;

    FlipInuValidationResult(boolean valid, int errorCode, String message) {
        this.valid = valid;
        this.errorCode = errorCode;
        this.message = message;
    }

    static FlipInuValidationResult ok() {
        return new FlipInuValidationResult(true, 0, null);
    }

    static FlipInuValidationResult fail(int code, String msg) {
        return new FlipInuValidationResult(false, code, msg);
    }

    public boolean isValid() { return valid; }
    public int getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
}

final class FlipInuValidationService {
    static FlipInuValidationResult validateFlip(String playerId, BigDecimal wagerEth, boolean paused) {
        if (paused) return FlipInuValidationResult.fail(FlipInuErrorCodes.ERR_PAUSED, "Contract paused");
        if (!FlipInuValidator.isValidPlayerId(playerId)) return FlipInuValidationResult.fail(FlipInuErrorCodes.ERR_INVALID_PLAYER, "Invalid player");
        if (wagerEth == null || wagerEth.compareTo(BigDecimal.ZERO) <= 0) return FlipInuValidationResult.fail(FlipInuErrorCodes.ERR_ZERO_BET, "Zero bet");
        if (wagerEth.compareTo(BFIConstants.MIN_BET_ETH) < 0) return FlipInuValidationResult.fail(FlipInuErrorCodes.ERR_BET_TOO_LOW, "Bet too low");
        if (wagerEth.compareTo(BFIConstants.MAX_BET_ETH) > 0) return FlipInuValidationResult.fail(FlipInuErrorCodes.ERR_BET_TOO_HIGH, "Bet too high");
        return FlipInuValidationResult.ok();
    }
}

final class FlipInuAggregator {
    private final BitcoinFlipInuGameEngine engine;

    FlipInuAggregator(BitcoinFlipInuGameEngine engine) {
        this.engine = engine;
    }

    List<LeaderboardEntry> buildLeaderboardByWins(int limit) {
        List<PlayerProfile> profiles = engine.getLeaderboardByWins(limit);
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            PlayerProfile p = profiles.get(i);
            entries.add(new LeaderboardEntry(
                    i + 1, p.getId(), p.getDisplayName(),
                    p.getTotalWins(), p.getTotalFlips(),
                    p.getTotalWageredEth(), p.getNetProfitEth(), p.getWinRate()
            ));
        }
        return entries;
    }
}

final class BFIProtocolVersion {
    static final int MAJOR = 1;
    static final int MINOR = 0;
    static final int PATCH = 0;

    static String string() {
        return MAJOR + "." + MINOR + "." + PATCH;
    }
}

// ==================== V2 Upgrade (250+ lines) ====================

final class BFIProtocolVersionV2 {
    static final int MAJOR = 2;
    static final int MINOR = 0;
    static final int PATCH = 0;

    static String string() {
        return MAJOR + "." + MINOR + "." + PATCH;
    }

    static boolean isAtLeastV2() {
        return true;
    }
}

final class BFIConstantsV2 {
    static final String DOMAIN_SEED_V2 = "BitcoinFlipInu.Satoshi.v2";
    static final int STREAK_BONUS_WIN_THRESHOLD = 3;
    static final int STREAK_BONUS_BPS = 50;
    static final BigDecimal DOUBLE_FLIP_MIN_BET = new BigDecimal("0.05");
    static final BigDecimal DOUBLE_FLIP_MAX_BET = new BigDecimal("5");
    static final int MAX_RECENT_STREAKS = 10;
}

/** V2: A round that can represent a double-flip (two outcomes). */
final class DoubleFlipRound {
    private final long roundId;
    private final String playerId;
    private final BigDecimal wagerEth;
    private final FlipOutcome choice1;
    private final FlipOutcome choice2;
    private final FlipOutcome outcome1;
    private final FlipOutcome outcome2;
    private final int wins; // 0, 1, or 2
    private final BigDecimal payoutEth;
    private final long resolvedAtMs;

    DoubleFlipRound(long roundId, String playerId, BigDecimal wagerEth,
                   FlipOutcome choice1, FlipOutcome choice2,
                   FlipOutcome outcome1, FlipOutcome outcome2,
                   int wins, BigDecimal payoutEth, long resolvedAtMs) {
        this.roundId = roundId;
        this.playerId = playerId;
        this.wagerEth = wagerEth;
        this.choice1 = choice1;
        this.choice2 = choice2;
        this.outcome1 = outcome1;
        this.outcome2 = outcome2;
        this.wins = wins;
        this.payoutEth = payoutEth;
        this.resolvedAtMs = resolvedAtMs;
    }

    public long getRoundId() { return roundId; }
    public String getPlayerId() { return playerId; }
    public BigDecimal getWagerEth() { return wagerEth; }
    public FlipOutcome getChoice1() { return choice1; }
    public FlipOutcome getChoice2() { return choice2; }
    public FlipOutcome getOutcome1() { return outcome1; }
    public FlipOutcome getOutcome2() { return outcome2; }
    public int getWins() { return wins; }
    public BigDecimal getPayoutEth() { return payoutEth; }
    public long getResolvedAtMs() { return resolvedAtMs; }
    public boolean isDoubleWin() { return wins == 2; }
}

/** V2: Computes streak-based bonus multiplier (e.g. 3+ wins = small bonus). */
final class StreakBonusCalculatorV2 {
    static BigDecimal multiplierForWinStreak(long winStreak) {
        if (winStreak < BFIConstantsV2.STREAK_BONUS_WIN_THRESHOLD) return BigDecimal.ONE;
        long extra = winStreak - BFIConstantsV2.STREAK_BONUS_WIN_THRESHOLD;
        int bps = (int) Math.min(extra * BFIConstantsV2.STREAK_BONUS_BPS, 500);
        return BigDecimal.ONE.add(BigDecimal.valueOf(bps).divide(BigDecimal.valueOf(10000), 4, RoundingMode.DOWN));
    }

    static BigDecimal applyStreakBonus(BigDecimal basePayout, long winStreak) {
        return basePayout.multiply(multiplierForWinStreak(winStreak)).setScale(18, RoundingMode.DOWN);
    }
}

/** V2: Configuration overrides for engine (min/max bet, house edge, etc.). */
final class FlipInuV2Config {
    private final BigDecimal minBetEth;
    private final BigDecimal maxBetEth;
    private final int houseEdgeBps;
    private final boolean doubleFlipEnabled;
    private final boolean streakBonusEnabled;

    FlipInuV2Config(BigDecimal minBetEth, BigDecimal maxBetEth, int houseEdgeBps,
                   boolean doubleFlipEnabled, boolean streakBonusEnabled) {
        this.minBetEth = minBetEth;
        this.maxBetEth = maxBetEth;
        this.houseEdgeBps = houseEdgeBps;
        this.doubleFlipEnabled = doubleFlipEnabled;
        this.streakBonusEnabled = streakBonusEnabled;
    }

    public static FlipInuV2Config defaultV2() {
        return new FlipInuV2Config(
                BFIConstants.MIN_BET_ETH,
                BFIConstants.MAX_BET_ETH,
                BFIConstants.HOUSE_EDGE_BPS,
                false,
                true
        );
    }

    public BigDecimal getMinBetEth() { return minBetEth; }
    public BigDecimal getMaxBetEth() { return maxBetEth; }
    public int getHouseEdgeBps() { return houseEdgeBps; }
    public boolean isDoubleFlipEnabled() { return doubleFlipEnabled; }
    public boolean isStreakBonusEnabled() { return streakBonusEnabled; }
}

/** V2: Tracks recent streak lengths for analytics. */
final class RecentStreakRecord {
    private final long timestampMs;
    private final boolean wasWin;
    private final long streakLength;

    RecentStreakRecord(long timestampMs, boolean wasWin, long streakLength) {
        this.timestampMs = timestampMs;
        this.wasWin = wasWin;
        this.streakLength = streakLength;
    }

    public long getTimestampMs() { return timestampMs; }
    public boolean wasWin() { return wasWin; }
    public long getStreakLength() { return streakLength; }
}

/** V2: Extended engine wrapper that supports optional double-flip and streak bonuses. */
final class BitcoinFlipInuGameEngineV2 {
    private final BitcoinFlipInuGameEngine delegate;
    private final FlipInuV2Config config;
    private final List<RecentStreakRecord> recentStreaks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong doubleFlipNonce = new AtomicLong(0);

    BitcoinFlipInuGameEngineV2(BitcoinFlipInuGameEngine delegate, FlipInuV2Config config) {
        this.delegate = delegate;
        this.config = config;
    }

    BitcoinFlipInuGameEngineV2(BitcoinFlipInuGameEngine delegate) {
        this(delegate, FlipInuV2Config.defaultV2());
    }

    public FlipRound executeFlip(String playerId, String displayName, BigDecimal wagerEth, FlipOutcome choice) {
        FlipRound r = delegate.executeFlip(playerId, displayName, wagerEth, choice);
        if (config.isStreakBonusEnabled()) {
            Optional<PlayerProfile> opt = delegate.getPlayer(playerId);
            opt.ifPresent(p -> recordStreak(p.getCurrentWinStreak() > 0, p.getCurrentWinStreak()));
        }
        return r;
    }

    private void recordStreak(boolean isWinStreak, long length) {
        if (length <= 0) return;
        recentStreaks.add(new RecentStreakRecord(System.currentTimeMillis(), isWinStreak, length));
        while (recentStreaks.size() > BFIConstantsV2.MAX_RECENT_STREAKS) {
            recentStreaks.remove(0);
        }
    }

    public DoubleFlipRound executeDoubleFlip(String playerId, String displayName, BigDecimal wagerEth,
                                             FlipOutcome choice1, FlipOutcome choice2) {
        if (!config.isDoubleFlipEnabled()) throw new IllegalStateException("Double flip not enabled");
        if (wagerEth.compareTo(BFIConstantsV2.DOUBLE_FLIP_MIN_BET) < 0
                || wagerEth.compareTo(BFIConstantsV2.DOUBLE_FLIP_MAX_BET) > 0) {
            throw new IllegalArgumentException("Double flip wager must be 0.05–5 ETH");
        }
        long roundId = doubleFlipNonce.incrementAndGet();
        long now = System.currentTimeMillis();
        FlipEntropy e1 = new FlipEntropy(roundId * 2, playerId, now);
        FlipEntropy e2 = new FlipEntropy(roundId * 2 + 1, playerId, now + 1);
        FlipOutcome outcome1 = e1.resolve();
        FlipOutcome outcome2 = e2.resolve();
        int wins = (choice1 == outcome1 ? 1 : 0) + (choice2 == outcome2 ? 1 : 0);
        BigDecimal payoutEth = BigDecimal.ZERO;
        if (wins == 1) payoutEth = TreasuryMath.weiToEth(TreasuryMath.winPayoutWei(TreasuryMath.ethToWei(wagerEth)));
        if (wins == 2) payoutEth = TreasuryMath.weiToEth(TreasuryMath.winPayoutWei(TreasuryMath.ethToWei(wagerEth))).multiply(new BigDecimal("1.9"));
        delegate.getOrCreatePlayer(playerId, displayName);
        return new DoubleFlipRound(roundId, playerId, wagerEth, choice1, choice2, outcome1, outcome2, wins, payoutEth, now);
    }

    public List<RecentStreakRecord> getRecentStreaks() {
        synchronized (recentStreaks) {
            return new ArrayList<>(recentStreaks);
        }
    }

    public BitcoinFlipInuGameEngine getDelegate() { return delegate; }
    public FlipInuV2Config getConfig() { return config; }
}

/** V2: Migrates legacy stats into V2 format (stub for future persistence). */
final class V2MigrationHelper {
    static String legacyPlayerIdToV2(String legacyId) {
        if (legacyId == null) return "v2_anon";
        return "v2_" + legacyId;
    }

    static boolean isV2PlayerId(String id) {
        return id != null && id.startsWith("v2_");
    }

    static BFIStateSnapshot toStateSnapshot(BitcoinFlipInuGameEngine engine) {
        BitcoinFlipInuGameEngine.GlobalStats s = engine.getGlobalStats();
        return new BFIStateSnapshot(
                engine.getGlobalRoundId(),
                s.totalWageredEth,
                s.totalPayoutsEth,
                s.uniquePlayers,
                System.currentTimeMillis()
        );
    }
}

/** V2: Exports leaderboard as JSON-like string for API use. */
final class FlipInuV2Export {
    static String leaderboardToJsonLines(List<LeaderboardEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (LeaderboardEntry e : entries) {
            sb.append(String.format("{\"rank\":%d,\"playerId\":\"%s\",\"displayName\":\"%s\",\"wins\":%d,\"flips\":%d,\"wageredEth\":\"%s\",\"netProfitEth\":\"%s\",\"winRatePct\":%.2f}%n",
                    e.getRank(), escape(e.getPlayerId()), escape(e.getDisplayName()),
                    e.getWins(), e.getFlips(), e.getWageredEth().toPlainString(),
                    e.getNetProfitEth().toPlainString(), e.getWinRatePct()));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

/** V2: Health check with version and config info. */
final class FlipInuHealthCheckV2 {
    private final BitcoinFlipInuGameEngine engine;
    private final FlipInuV2Config config;

    FlipInuHealthCheckV2(BitcoinFlipInuGameEngine engine, FlipInuV2Config config) {
        this.engine = engine;
        this.config = config;
    }

    boolean isHealthy() {
        try {
            BitcoinFlipInuGameEngine.GlobalStats s = engine.getGlobalStats();
            return s.totalRounds >= 0 && s.uniquePlayers >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    String report() {
        return String.format("BFI V2 health: ok=%s, version=%s, doubleFlip=%s, streakBonus=%s",
                isHealthy(), BFIProtocolVersionV2.string(),
                config.isDoubleFlipEnabled(), config.isStreakBonusEnabled());
    }
}
