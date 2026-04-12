--
-- Copyright (c) 2025 SUSE LLC
--
-- This software is licensed to you under the GNU General Public License,
-- version 2 (GPLv2). There is NO WARRANTY for this software, express or
-- implied, including the implied warranties of MERCHANTABILITY or FITNESS
-- FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
-- along with this software; if not, see
-- http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
--

insert into rhnKSInstallType (id, label, name) (
    select sequence_nextval('rhn_ksinstalltype_id_seq'),
           'sles16generic','SUSE Linux Enterprise 16 (Agama)'
    from dual
    where not exists (select 1 from rhnKSInstallType where label = 'sles16generic')
);
