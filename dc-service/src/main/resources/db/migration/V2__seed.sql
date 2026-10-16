-- Seed data. Some products are sold by both branches at different prices, some by one branch only,
-- so a wrong slice (for example branch B rows sent to A) is easy to spot.

INSERT INTO branch (code, name) VALUES ('A', 'Branch A - Kuala Lumpur');
INSERT INTO branch (code, name) VALUES ('B', 'Branch B - Penang');

INSERT INTO product (sku, name) VALUES ('P-001', 'Arabica coffee beans 1kg');
INSERT INTO product (sku, name) VALUES ('P-002', 'Jasmine rice 5kg');
INSERT INTO product (sku, name) VALUES ('P-003', 'Palm cooking oil 2L');
INSERT INTO product (sku, name) VALUES ('P-004', 'Condensed milk 500g');
INSERT INTO product (sku, name) VALUES ('P-005', 'Kaya spread 400g');
INSERT INTO product (sku, name) VALUES ('P-006', 'Teh tarik mix 15 sachets');
INSERT INTO product (sku, name) VALUES ('P-007', 'White curry paste 200g');
INSERT INTO product (sku, name) VALUES ('P-008', 'Nutmeg jam 250g');
INSERT INTO product (sku, name) VALUES ('P-009', 'Sambal belacan 180g');
INSERT INTO product (sku, name) VALUES ('P-010', 'Durian cake 6 pcs');

-- Branch A: 7 products (P-005, P-006, P-009 sold only here)
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('A', 'P-001', 42.50, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('A', 'P-002', 27.90, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('A', 'P-003', 15.40, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('A', 'P-004', 4.20, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('A', 'P-005', 8.90, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('A', 'P-006', 12.00, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('A', 'P-009', 6.50, 'MYR');

-- Branch B: 6 products (P-001..P-003 also in A at other prices; P-007, P-008, P-010 only here)
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('B', 'P-001', 44.00, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('B', 'P-002', 26.50, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('B', 'P-003', 15.90, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('B', 'P-007', 9.80, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('B', 'P-008', 11.50, 'MYR');
INSERT INTO branch_price (branch_code, sku, price, currency) VALUES ('B', 'P-010', 35.00, 'MYR');
