CREATE TABLE P1 (
  ID INTEGER DEFAULT '0' NOT NULL,
  TINY TINYINT NOT NULL,
  SMALL SMALLINT NOT NULL,
  BIG BIGINT NOT NULL,
  PRIMARY KEY (ID)
);
CREATE VIEW MATP1 (BIG, NUM, IDCOUNT, TINYCOUNT, SMALLCOUNT, BIGCOUNT, TINYSUM, SMALLSUM) AS SELECT BIG, COUNT(*), COUNT(ID), COUNT(TINY), COUNT(SMALL), COUNT(BIG), SUM(TINY), SUM(SMALL) FROM P1 GROUP BY BIG;

CREATE TABLE R1 (
  ID INTEGER DEFAULT '0' NOT NULL,
  TINY TINYINT NOT NULL,
  SMALL SMALLINT NOT NULL,
  BIG BIGINT NOT NULL,
  PRIMARY KEY (ID)
);
CREATE VIEW MATR1 (BIG, NUM, TINYSUM, SMALLSUM) AS SELECT BIG, COUNT(*), SUM(TINY), SUM(SMALL) FROM R1 WHERE ID > 5 GROUP BY BIG;
PARTITION TABLE P1 ON COLUMN ID;
