--
-- Copyright (c) 2011 Novell
--
-- This software is licensed to you under the GNU General Public License,
-- version 2 (GPLv2). There is NO WARRANTY for this software, express or
-- implied, including the implied warranties of MERCHANTABILITY or FITNESS
-- FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
-- along with this software; if not, see
-- http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
--
--
create table suseProductFile
(
  id              NUMBER NOT NULL PRIMARY KEY,
  name            VARCHAR2(256) NOT NULL,
  evr_id          NUMBER NOT NULL
                         CONSTRAINT suse_prod_file_eid_fk
                         REFERENCES rhnpackageevr (id),
  package_arch_id NUMBER NOT NULL
                         CONSTRAINT suse_prod_file_paid_fk
                         REFERENCES rhnpackagearch (id),
  vendor          VARCHAR2(256),
  summary         VARCHAR2(4000),
  description     VARCHAR2(4000),
  created   timestamp with local time zone
                DEFAULT (current_timestamp) NOT NULL,
  modified  timestamp with local time zone
                DEFAULT (current_timestamp) NOT NULL
);

CREATE SEQUENCE suse_prod_file_id_seq START WITH 100;

