DELETE FROM account_entry;
DELETE FROM account;

INSERT INTO account (id, owner_name, currency, balance, status) VALUES
    ('ACC-1001', 'Alice Martins', 'USD', 10000.00, 'ACTIVE'),
    ('ACC-2002', 'Bob Silva',     'USD',  2500.00, 'ACTIVE'),
    ('ACC-3003', 'Carol Souza',   'USD',    80.00, 'ACTIVE'),
    ('ACC-4004', 'Dave Costa',    'USD',  1000.00, 'FROZEN'),
    ('ACC-5005', 'Mallory Reis',  'USD',   500.00, 'ACTIVE');
