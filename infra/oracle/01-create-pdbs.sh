#!/bin/bash
# Runs once, on the first start of the Oracle container (empty data volume).
# Creates one pluggable database (PDB) per site inside the single Oracle server, plus the
# application user of each site. A PDB is a separate database: its own users, tables and
# service name (DC_PDB, BRANCH_A_PDB, BRANCH_B_PDB); a user of one PDB cannot see another.
set -euo pipefail

create_pdb() {
  local pdb="$1" user="$2" password="$3"
  echo "Creating ${pdb} with user ${user}"
  sqlplus -s / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = CDB\$ROOT;
CREATE PLUGGABLE DATABASE ${pdb}
  ADMIN USER pdb_admin IDENTIFIED BY "${ORACLE_PASSWORD}"
  FILE_NAME_CONVERT = ('/opt/oracle/oradata/FREE/pdbseed/', '/opt/oracle/oradata/FREE/${pdb}/');
ALTER PLUGGABLE DATABASE ${pdb} OPEN;
-- Reopen automatically when the container restarts.
ALTER PLUGGABLE DATABASE ${pdb} SAVE STATE;
ALTER SESSION SET CONTAINER = ${pdb};
-- A PDB created from the seed has only SYSTEM/SYSAUX/UNDO/TEMP; application data gets its own tablespace.
CREATE TABLESPACE users DATAFILE '/opt/oracle/oradata/FREE/${pdb}/users01.dbf' SIZE 20M AUTOEXTEND ON NEXT 10M;
CREATE USER ${user} IDENTIFIED BY "${password}" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO ${user};
EXIT
SQL
}

create_pdb DC_PDB       dc     "${DC_DB_PASSWORD}"
create_pdb BRANCH_A_PDB branch "${BRANCH_A_DB_PASSWORD}"
create_pdb BRANCH_B_PDB branch "${BRANCH_B_DB_PASSWORD}"
