package com.tripbus.seatinventory.events;

public final class InventoryEvents {

    // Publica
    public static final String SEAT_HELD = "SeatHeld";
    public static final String SEAT_RELEASED = "SeatReleased";
    public static final String SEAT_SOLD = "SeatSold";
    public static final String HOLD_EXPIRED = "HoldExpired";
    public static final String SEAT_STATE_CHANGED = "SeatStateChanged";
    public static final String WAITLIST_SEAT_OFFERED = "WaitlistSeatOffered";
    public static final String WAITLIST_CLOSED = "WaitlistClosed";

    // Consume
    public static final String ITINERARY_PUBLISHED = "ItineraryPublished";
    public static final String ITINERARY_CANCELLED = "ItineraryCancelled";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String OFFER_CLOSED = "OfferClosed";
    public static final String ITINERARY_RESCHEDULED = "ItineraryRescheduled";

    private InventoryEvents() {
    }
}
