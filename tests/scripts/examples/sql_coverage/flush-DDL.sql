CREATE TABLE Flush_P2 (
  ID INTEGER DEFAULT '0' NOT NULL,
  TINY TINYINT NOT NULL,
--  SMALL SMALLINT NOT NULL,
--  BIG BIGINT NOT NULL,
  PRIMARY KEY (ID)
);

CREATE TABLE Flush_R2 (
  ID INTEGER DEFAULT '0' NOT NULL,
--  TINY TINYINT NOT NULL,
--  SMALL SMALLINT NOT NULL,
  BIG BIGINT NOT NULL,
  PRIMARY KEY (ID)
);
--PARTITION TABLE Flush_P2 ON COLUMN ID;
