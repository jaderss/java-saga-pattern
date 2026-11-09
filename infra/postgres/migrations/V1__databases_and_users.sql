CREATE USER transfer WITH PASSWORD '${transferPassword}';
CREATE USER account WITH PASSWORD '${accountPassword}';
CREATE USER risk WITH PASSWORD '${riskPassword}';

CREATE DATABASE transfer OWNER transfer;
CREATE DATABASE account OWNER account;
CREATE DATABASE risk OWNER risk;
