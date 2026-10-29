package com.tripbus.seatinventory.web;

import com.tripbus.seatinventory.common.security.CurrentUser;
import com.tripbus.seatinventory.domain.Hold;
import com.tripbus.seatinventory.service.Actor;
import com.tripbus.seatinventory.service.OccupancyService;
import com.tripbus.seatinventory.service.SeatInventoryService;
import com.tripbus.seatinventory.service.SeatLifecycleService;
import com.tripbus.seatinventory.service.WaitlistService;
import com.tripbus.seatinventory.web.dto.AvailabilityResponse;
import com.tripbus.seatinventory.web.dto.BlockSeatsRequest;
import com.tripbus.seatinventory.web.dto.BlockSeatsResponse;
import com.tripbus.seatinventory.web.dto.HoldRequest;
import com.tripbus.seatinventory.web.dto.HoldResponse;
import com.tripbus.seatinventory.web.dto.OccupancyResponse;
import com.tripbus.seatinventory.web.dto.SeatStatesResponse;
import com.tripbus.seatinventory.web.dto.SeatView;
import com.tripbus.seatinventory.web.dto.UnblockSeatsRequest;
import com.tripbus.seatinventory.web.dto.WaitlistRequest;
import com.tripbus.seatinventory.web.dto.WaitlistResponse;
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
    private final SeatLifecycleService lifecycleService;
    private final OccupancyService occupancyService;
    private final WaitlistService waitlistService;

    public SeatController(SeatInventoryService inventoryService, SeatLifecycleService lifecycleService,
                          OccupancyService occupancyService, WaitlistService waitlistService) {
        this.inventoryService = inventoryService;
        this.lifecycleService = lifecycleService;
        this.occupancyService = occupancyService;
        this.waitlistService = waitlistService;
    }

    /** GetSeatStates: estado de todos los asientos (US-016/US-017). */
    @GetMapping("/itineraries/{itineraryId}")
    public SeatStatesResponse seatStates(@PathVariable UUID itineraryId, Authentication authentication) {
        return inventoryService.getSeatStates(itineraryId, optionalActor(authentication));
    }

    /** GetSeatState: estado de un asiento. */
    @GetMapping("/itineraries/{itineraryId}/{seatNumber}")
    public SeatView seatState(@PathVariable UUID itineraryId, @PathVariable String seatNumber,
                              Authentication authentication) {
        return inventoryService.getSeatState(itineraryId, seatNumber, optionalActor(authentication));
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
        inventoryService.releaseHold(holdId, CurrentUser.id(authentication), Hold.LIBERADA_POR_USUARIO);
        return ResponseEntity.noContent().build();
    }

    /** US-112: bloquea asientos DISPONIBLE (OPERADOR de la empresa del viaje o ADMIN). */
    @PostMapping("/itineraries/{itineraryId}/blocks")
    public BlockSeatsResponse block(@PathVariable UUID itineraryId, @Valid @RequestBody BlockSeatsRequest request,
                                    Authentication authentication) {
        return lifecycleService.blockSeats(itineraryId, request.seatNumbers(), request.motivo(),
                actor(authentication));
    }

    /** US-112: devuelve a la venta asientos BLOQUEADO. */
    @DeleteMapping("/itineraries/{itineraryId}/blocks")
    public BlockSeatsResponse unblock(@PathVariable UUID itineraryId, @Valid @RequestBody UnblockSeatsRequest request,
                                      Authentication authentication) {
        return lifecycleService.unblockSeats(itineraryId, request.seatNumbers(), actor(authentication));
    }

    /** US-191: ocupación y abandono de compra (OPERADOR de la empresa del viaje o ADMIN; a otros, 404). */
    @GetMapping("/itineraries/{itineraryId}/occupancy")
    public OccupancyResponse occupancy(@PathVariable UUID itineraryId, Authentication authentication) {
        return occupancyService.ocupacion(itineraryId, actor(authentication));
    }

    /** US-140: anotarse en la lista de espera de un viaje agotado. */
    @PostMapping("/itineraries/{itineraryId}/waitlist")
    public ResponseEntity<WaitlistResponse> joinWaitlist(@PathVariable UUID itineraryId,
                                                         @Valid @RequestBody WaitlistRequest request,
                                                         Authentication authentication) {
        WaitlistResponse turno = waitlistService.anotarse(itineraryId, CurrentUser.id(authentication),
                request.cantidad());
        return ResponseEntity.status(HttpStatus.CREATED).body(turno);
    }

    /** US-140: salir de la lista de espera. */
    @DeleteMapping("/itineraries/{itineraryId}/waitlist")
    public ResponseEntity<Void> leaveWaitlist(@PathVariable UUID itineraryId, Authentication authentication) {
        waitlistService.salir(itineraryId, CurrentUser.id(authentication));
        return ResponseEntity.noContent().build();
    }

    /** US-140: mis turnos, con la posición en la fila de los que siguen esperando. */
    @GetMapping("/waitlist/me")
    public List<WaitlistResponse> myWaitlist(Authentication authentication) {
        return waitlistService.misTurnos(CurrentUser.id(authentication));
    }

    private static Actor optionalActor(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken ? actor(authentication) : null;
    }

    private static Actor actor(Authentication authentication) {
        return new Actor(CurrentUser.id(authentication), CurrentUser.role(authentication),
                CurrentUser.companyId(authentication));
    }
}
