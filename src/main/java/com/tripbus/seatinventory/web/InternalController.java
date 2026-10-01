package com.tripbus.seatinventory.web;

import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.web.dto.ConfirmSaleRequest;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import com.tripbus.seatinventory.web.dto.InternalHoldRequest;
import com.tripbus.seatinventory.web.dto.OfferSeatsRequest;
import com.tripbus.seatinventory.web.dto.OfferSeatsResponse;
import com.tripbus.seatinventory.web.dto.ReleaseRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API servicio-a-servicio (equivalente REST de los métodos gRPC del doc).
 * Protegida con X-Internal-Token y nunca expuesta por el API Gateway.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final SeatInventoryService inventoryService;

    public InternalController(SeatInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> hold(@Valid @RequestBody InternalHoldRequest request) {
        HoldResponse hold = inventoryService.holdSeats(request.itineraryId(), request.seatNumbers(), request.userId(),
                request.offerId());
        return ResponseEntity.status(HttpStatus.CREATED).body(hold);
    }

    @GetMapping("/holds/{holdId}")
    public HoldResponse getHold(@PathVariable UUID holdId) {
        return inventoryService.getHold(holdId, null);
    }

    @PostMapping("/holds/{holdId}/confirm")
    public HoldResponse confirm(@PathVariable UUID holdId, @Valid @RequestBody ConfirmSaleRequest request) {
        return inventoryService.confirmSale(holdId, request.bookingId());
    }

    @PostMapping("/holds/{holdId}/release")
    public HoldResponse release(@PathVariable UUID holdId, @RequestBody(required = false) ReleaseRequest request) {
        return inventoryService.releaseHold(holdId, null, request != null ? request.motivo() : "LIBERADA_POR_SISTEMA");
    }

    @PostMapping("/offers/{offerId}/seats")
    public OfferSeatsResponse reserveOfferSeats(@PathVariable UUID offerId, @Valid @RequestBody OfferSeatsRequest request) {
        return inventoryService.reserveOfferSeats(offerId, request.itineraryId(), request.seatNumbers());
    }

    @PostMapping("/offers/{offerId}/release")
    public ResponseEntity<Void> releaseOfferSeats(@PathVariable UUID offerId) {
        inventoryService.releaseOfferSeats(offerId);
        return ResponseEntity.noContent().build();
    }
}
