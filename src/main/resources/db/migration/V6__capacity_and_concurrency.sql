-- Additive migration; existing business rows are preserved.
CREATE TABLE booking_guard (id INT PRIMARY KEY);
INSERT INTO booking_guard(id) VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9), (10), (11), (12), (13), (14), (15), (16), (17), (18), (19), (20), (21), (22), (23), (24), (25), (26), (27), (28), (29), (30), (31), (32), (33), (34), (35), (36), (37), (38), (39), (40), (41), (42), (43), (44), (45), (46), (47), (48), (49), (50), (51), (52), (53), (54), (55), (56), (57), (58), (59), (60), (61), (62), (63), (64), (65), (66), (67), (68), (69), (70), (71), (72), (73), (74), (75), (76), (77), (78), (79), (80), (81), (82), (83), (84), (85), (86), (87), (88), (89), (90), (91), (92), (93), (94), (95), (96), (97), (98), (99), (100), (101), (102), (103), (104), (105), (106), (107), (108), (109), (110), (111), (112), (113), (114), (115), (116), (117), (118), (119), (120), (121), (122), (123), (124), (125), (126), (127), (128), (129), (130), (131), (132), (133), (134), (135), (136), (137), (138), (139), (140), (141), (142), (143), (144), (145), (146), (147), (148), (149), (150), (151), (152), (153), (154), (155), (156), (157), (158), (159), (160), (161), (162), (163), (164), (165), (166), (167), (168), (169), (170), (171), (172), (173), (174), (175), (176), (177), (178), (179), (180), (181), (182), (183), (184), (185), (186), (187), (188), (189), (190), (191), (192), (193), (194), (195), (196), (197), (198), (199), (200), (201), (202), (203), (204), (205), (206), (207), (208), (209), (210), (211), (212), (213), (214), (215), (216), (217), (218), (219), (220), (221), (222), (223), (224), (225), (226), (227), (228), (229), (230), (231), (232), (233), (234), (235), (236), (237), (238), (239), (240), (241), (242), (243), (244), (245), (246), (247), (248), (249), (250), (251), (252), (253), (254), (255);
CREATE TABLE job_lease (job_name VARCHAR(80) PRIMARY KEY, owner_id VARCHAR(64), lease_until VARCHAR(40) NOT NULL);
INSERT INTO job_lease VALUES ('notification-reminders', NULL, '1970-01-01T00:00:00+08:00');
ALTER TABLE notification_outbox ADD COLUMN claimed_at VARCHAR(40);
ALTER TABLE notification_outbox ADD COLUMN grant_reserved BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_appointment_created ON appointment(created_at,id);
CREATE INDEX idx_appointment_store_created ON appointment(store_id,created_at,id);
CREATE INDEX idx_appointment_status_date ON appointment(status,booking_date,id);
CREATE INDEX idx_appointment_assigned_status ON appointment(assigned_therapist_id,status);
CREATE INDEX idx_history_appointment_created ON appointment_history(appointment_id,created_at,id);
CREATE INDEX idx_customer_created ON customer_profile(created_at,id);
CREATE INDEX idx_audit_store_created ON audit_log(store_id,created_at,id);
CREATE INDEX idx_outbox_store_created ON notification_outbox(store_id,created_at,id);
CREATE INDEX idx_outbox_due ON notification_outbox(delivery_status,next_attempt_at,created_at);
CREATE INDEX idx_pms_pending_created ON pms_outbox(acknowledged,created_at);
