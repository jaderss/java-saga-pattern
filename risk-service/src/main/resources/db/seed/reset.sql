DELETE FROM reservation;
DELETE FROM limit_usage;
DELETE FROM watchlist;

INSERT INTO watchlist (account_id, reason) VALUES
    ('ACC-5005', 'sanctions list match');
