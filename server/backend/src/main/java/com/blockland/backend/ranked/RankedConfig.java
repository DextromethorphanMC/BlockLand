package com.blockland.backend.ranked;

import java.util.ArrayList;
import java.util.List;

/**
 * How the ranked ladder (排位) scores: everyone starts at {@code startScore}; a match moves a player by team Elo times K
 * ({@code placementK} for the first {@code placementMatches} matches, then {@code kFactor}); the score thresholds
 * decide the tier (煤炭 below {@code iron}, 钢铁, 黄金, 钻石 from {@code diamond}); 猎首 needs a score of at least
 * {@code headhunter} <b>and</b> a place within the top {@code headhunterTop} of the ladder (otherwise 钻石).
 *
 * @param season the current season, counted up by a reset
 */
public record RankedConfig(int startScore, int kFactor, int placementMatches, int placementK,
                           int iron, int gold, int diamond, int headhunter, int headhunterTop, int season) {
    public static final RankedConfig DEFAULT = new RankedConfig(1000, 32, 5, 64, 1150, 1350, 1600, 1900, 50, 1);

    /** The problems of an edited config (empty = valid). */
    public List<String> problems() {
        List<String> errors = new ArrayList<>();
        if (startScore < 0 || startScore > 10000) errors.add("初始分数必须在 0 到 10000 之间");
        if (kFactor < 1 || kFactor > 200) errors.add("K 值必须在 1 到 200 之间");
        if (placementK < 1 || placementK > 400) errors.add("定级赛 K 值必须在 1 到 400 之间");
        if (placementMatches < 0 || placementMatches > 50) errors.add("定级赛场数必须在 0 到 50 之间");
        if (iron < 0 || !(iron < gold && gold < diamond && diamond <= headhunter)) {
            errors.add("段位分数线必须依次递增：钢铁 < 黄金 < 钻石 ≤ 猎首");
        }
        if (headhunter > 100000) errors.add("猎首分数线过高");
        if (headhunterTop < 1 || headhunterTop > 10000) errors.add("猎首名额必须在 1 到 10000 之间");
        return errors;
    }

    RankedConfig withSeason(int next) {
        return new RankedConfig(startScore, kFactor, placementMatches, placementK, iron, gold, diamond, headhunter, headhunterTop, next);
    }
}
