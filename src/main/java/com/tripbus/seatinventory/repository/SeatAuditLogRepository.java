package com.tripbus.seatinventory.repository;

import com.tripbus.seatinventory.domain.SeatAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatAuditLogRepository extends JpaRepository<SeatAuditLog, Long> {
}
