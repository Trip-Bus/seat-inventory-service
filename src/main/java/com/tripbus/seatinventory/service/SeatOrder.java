package com.tripbus.seatinventory.service;

import java.util.Comparator;
import java.util.List;

/** Orden natural de asientos ("2" antes que "10"). Se usa para adquirir locks en orden determinista. */
public final class SeatOrder {

    public static final Comparator<String> NATURAL = (a, b) -> {
        boolean na = a.chars().allMatch(Character::isDigit);
        boolean nb = b.chars().allMatch(Character::isDigit);
        if (na && nb && !a.isEmpty() && !b.isEmpty()) {
            int cmp = Long.compare(Long.parseLong(a), Long.parseLong(b));
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.compareTo(b);
    };

    private SeatOrder() {
    }

    public static List<String> sortedDistinct(List<String> seats) {
        return seats.stream().map(String::trim).distinct().sorted(NATURAL).toList();
    }
}
