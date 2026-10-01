package com.tripbus.seatinventory.web;

import com.tripbus.seatinventory.common.security.CurrentUser;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.web.dto.AvailabilityResponse;
import com.tripbus.seatinventory.web.dto.HoldRequest;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import com.tripbus.seatinventory.web.dto.SeatStatesResponse;
import com.tripbus.seatinventory.web.dto.SeatView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** API pública (vía API Gateway) del inventario de asientos. */
@RestController
@RequestMapping("/seats")
public class SeatController {

    private final SeatInventoryService inventoryService;

    public SeatController(SeatInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** GetSeatStates: estado de todos los asientos (US-016/US-017). */
    @GetMapping("/itineraries/{itineraryId}")
    public SeatStatesResponse seatStates(@PathVariable UUID itineraryId, Authentication authentication) {
        return inventoryService.getSeatStates(itineraryId, optionalUser(authentication));
    }

    /** GetSeatState: estado de un asiento. */
    @GetMapping("/itineraries/{itineraryId}/{seatNumber}")
    public SeatView seatState(@PathVariable UUID itineraryId, @PathVariable String seatNumber,
                              Authentication authentication) {
        return inventoryService.getSeatState(itineraryId, seatNumber, optionalUser(authentication));
    }

    /** Disponibilidad agregada (la usa Catalog en la búsqueda). */
    @GetMapping("/availability")
    public List<AvailabilityResponse> availability(@RequestParam List<UUID> itineraryIds) {
        return inventoryService.availability(itineraryIds);
    }

    /** HoldSeats: retiene asientos por 10 minutos (US-022). 409 si alguno está ocupado (US-018). */
    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> hold(@Valid @RequestBody HoldRequest request, Authentication authentication) {
        HoldResponse hold = inventoryService.holdSeats(request.itineraryId(), request.seatNumbers(),
                CurrentUser.id(authentication), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(hold);
    }

    @GetMapping("/holds/{holdId}")
    public HoldResponse getHold(@PathVariable UUID holdId, Authentication authentication) {
        return inventoryService.getHold(holdId, CurrentUser.id(authentication));
    }

    /** ExtendHold: una sola vez, +5 minutos como máximo. */
    @PostMapping("/holds/{holdId}/extend")
    public HoldResponse extend(@PathVariable UUID holdId, Authentication authentication) {
        return inventoryService.extendHold(holdId, CurrentUser.id(authentication));
    }

    /** ReleaseHold por el propio pasajero. */
    @DeleteMapping("/holds/{holdId}")
    public ResponseEntity<Void> release(@PathVariable UUID holdId, Authentication authentication) {
        inventoryService.releaseHold(holdId, CurrentUser.id(authentication), "LIBERADA_POR_USUARIO");
        return ResponseEntity.noContent().build();
    }

    private static UUID optionalUser(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken ? CurrentUser.id(authentication) : null;
    }
}
