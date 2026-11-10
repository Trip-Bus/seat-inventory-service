-- HT-08: archivo de viajes viejos. Mismo esquema que las tablas vivas (columnas, valores por defecto, CHECK,
-- índices y la restricción EXCLUDE); InventoryArchiver mueve aquí los viajes que salieron hace más de
-- tripbus.inventory.archive-after-days. Sin claves foráneas ni el trigger anti doble venta: el archivo
-- solo se lee (reportes de ocupación de booking, hasta 366 días).
-- IMPORTANTE: una migración que cambie una tabla viva debe cambiar también su *_archive (InventoryArchiver
-- copia con SELECT *).
CREATE TABLE itineraries_archive (LIKE itineraries INCLUDING ALL);
CREATE TABLE seat_states_archive (LIKE seat_states INCLUDING ALL);
CREATE TABLE seat_occupancies_archive (LIKE seat_occupancies INCLUDING ALL);
CREATE TABLE holds_archive (LIKE holds INCLUDING ALL);
CREATE TABLE hold_seats_archive (LIKE hold_seats INCLUDING ALL);
CREATE TABLE waitlist_archive (LIKE waitlist INCLUDING ALL);
CREATE TABLE seat_audit_log_archive (LIKE seat_audit_log INCLUDING ALL);

-- El job busca los viajes por fecha de salida.
CREATE INDEX idx_itineraries_fecha_salida ON itineraries (fecha_salida);
