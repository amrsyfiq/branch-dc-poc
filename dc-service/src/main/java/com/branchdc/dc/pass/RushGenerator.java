package com.branchdc.dc.pass;

import com.branchdc.events.TravelPass;

import java.time.LocalDate;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Makes up travellers for the holiday-rush simulation: random but plausible names, nationalities, birth
 * dates and passport numbers (one letter + 8 digits). All fake; none of it is real data.
 */
final class RushGenerator {

    private static final List<String> FIRST = List.of("Aina", "Hafiz", "Mei", "Wei Jie", "Siti", "Arun", "Kavya",
            "Daniel", "Sarah", "Kenji", "Yuna", "Lucas", "Emma", "Budi", "Putri", "Somchai", "Nok", "Minh", "Linh",
            "Jose", "Ana", "Omar", "Layla", "Chen", "Hana", "Ravi", "Noah", "Mia", "Tariq", "Zara");
    private static final List<String> LAST = List.of("Abdullah", "Tan", "Lim", "Wong", "Rahman", "Kumar", "Nair",
            "Smith", "Jones", "Sato", "Kim", "Silva", "Muller", "Santoso", "Wijaya", "Srisuk", "Nguyen", "Tran",
            "Reyes", "Cruz", "Haddad", "Hassan", "Li", "Park", "Patel", "Taylor", "Brown", "Ali", "Khan", "Ong");
    private static final List<String> NATIONALITIES = List.of("SGP", "IDN", "THA", "CHN", "JPN", "KOR", "IND",
            "AUS", "GBR", "USA", "DEU", "FRA", "PHL", "VNM", "BRN", "SAU", "ARE", "NZL");
    private static final List<TravelPass.Type> TYPES = List.of(TravelPass.Type.TOURIST, TravelPass.Type.TOURIST,
            TravelPass.Type.TOURIST, TravelPass.Type.BUSINESS, TravelPass.Type.STUDENT, TravelPass.Type.TRANSIT);
    private static final String LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ";

    private RushGenerator() {
    }

    /** {@code count} approved passes, valid for two weeks from a few days before or after {@code today}. */
    static List<TravelPass> generate(int count, LocalDate today, RandomGenerator random) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            String name = pick(FIRST, random) + " " + pick(LAST, random);
            String passport = LETTERS.charAt(random.nextInt(LETTERS.length()))
                    + String.valueOf(10_000_000 + random.nextInt(90_000_000));
            LocalDate born = today.minusYears(18 + random.nextInt(60)).minusDays(random.nextInt(365));
            LocalDate from = today.plusDays(random.nextInt(-3, 10));
            return new TravelPass(passport, name, pick(NATIONALITIES, random), born, pick(TYPES, random),
                    from, from.plusDays(14), TravelPass.Status.APPROVED);
        }).toList();
    }

    private static <T> T pick(List<T> values, RandomGenerator random) {
        return values.get(random.nextInt(values.size()));
    }
}
