/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2009.                            (c) 2009.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*                                       
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*                                       
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*                                       
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*                                       
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*                                       
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
*  $Revision: 4 $
*
************************************************************************
*/

package ca.nrc.cadc.tap.parser.schema;

import java.net.URI;
import java.security.AccessControlException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.gms.GroupClient;
import org.opencadc.gms.GroupURI;
import org.opencadc.gms.GroupUtil;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.tap.parser.ParserUtil;
import ca.nrc.cadc.tap.parser.navigator.FromItemNavigator;
import ca.nrc.cadc.tap.schema.TableDesc;
import ca.nrc.cadc.tap.schema.TapPermissions;
import ca.nrc.cadc.tap.schema.TapSchema;
import net.sf.jsqlparser.schema.Table;

/**
 * Validate Tables.
 * 
 * @author zhangsa
 *
 */
public class TapSchemaTableValidator extends FromItemNavigator {
    protected static Logger log = Logger.getLogger(TapSchemaTableValidator.class);

    protected TapSchema tapSchema;

    private List<TableDesc> tables = new ArrayList<>();
    private Map<URI, List<GroupURI>> membershipCache = new HashMap<URI, List<GroupURI>>();

    public TapSchemaTableValidator() {
    }

    public TapSchemaTableValidator(TapSchema ts) {
        this.tapSchema = ts;
    }

    public void setTapSchema(TapSchema tapSchema) {
        this.tapSchema = tapSchema;
    }

    /**
     * Get list of tables referenced in the query.
     * 
     * @return tables referenced in query
     */
    public List<TableDesc> getTables() {
        return tables;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * net.sf.jsqlparser.statement.select.FromItemVisitor#visit(net.sf.jsqlparser.
     * schema.Table)
     */
    @Override
    public void visit(Table table) {
        log.debug("visit(table) " + table);
        String tableNameOrAlias = table.getName();
        Table qTable = ParserUtil.findFromTable(selectNavigator.getPlainSelect(), tableNameOrAlias);
        if (qTable == null)
            throw new IllegalArgumentException("Table [ " + table + " ] is not found in FROM clause");
        TableDesc td = TapSchemaUtil.findTableDesc(tapSchema, qTable);
        if (td == null)
            throw new IllegalArgumentException("Table [ " + table + " ] is not found in TapSchema");
        if (!tables.contains(td)) {
            checkPermissions(td);
            tables.add(td);
        }
    }

    // This method will throw an access control exception if the table
    // is not readable by the current (possibly anonymous) user.
    private void checkPermissions(TableDesc tableDesc) throws AccessControlException {

        log.debug("Checking permissions on table " + tableDesc.getTableName());
        TapPermissions tp = tableDesc.tapPermissions;

        // first check if the table is public
        if (tp == null) {
            log.debug("public: no tap permissions on table");
            return;
        }
        if (tp.owner == null) {
            log.debug("public: no owner in tap permissions");
            return;
        }
        if (tp.owner != null && tp.isPublic != null && tp.isPublic) {
            log.debug("public: table set as public");
            return;
        }

        Subject curSub = AuthenticationUtil.getCurrentSubject();
        boolean anon = curSub == null || curSub.getPrincipals().isEmpty();

        if (!anon) {
            if (isOwner(tp.owner, curSub)) {
                log.debug("caller is owner");
                return;
            }
            try {
                if (isMember(tp.readGroup)) {
                    log.debug("caller member of read-only group " + tp.readGroup);
                    return;
                }
                if (isMember(tp.readWriteGroup)) {
                    log.debug("caller member of read-write group " + tp.readWriteGroup);
                    return;
                }
            } catch (Exception e) {
                log.error("error getting groups or checking credentials", e);
                throw new RuntimeException(e);
            }
        }
        throw new AccessControlException("permission denied on table " + tableDesc.getTableName());
    }

    private boolean isOwner(Subject owner, Subject caller) {
        Set<Principal> ownerPrincipals = owner.getPrincipals();
        Set<Principal> callerPrincipals = caller.getPrincipals();

        for (Principal oPrin : ownerPrincipals) {
            for (Principal cPrin : callerPrincipals) {
                if (AuthenticationUtil.equals(oPrin, cPrin))
                    return true; // caller===owner
            }
        }
        return false;
    }

    // check and cache memberships for the service ID
    private boolean isMember(GroupURI group) throws Exception {

        if (group != null) {
            List<GroupURI> memberships = membershipCache.get(group.getServiceID());
            if (memberships == null) {
                // get the list of memberships from a group client
                GroupClient groupClient = GroupUtil.getGroupClient(group.getServiceID());
                if (groupClient != null && CredUtil.checkCredentials()) {
                    memberships = groupClient.getMemberships();
                    log.debug("user is a member of " + memberships.size() + " groups in service " + group.getServiceID());
                    if (memberships != null) {
                        membershipCache.put(group.getServiceID(), memberships);
                    } else {
                        // just in case the group client returns null instead of an empty list
                        membershipCache.put(group.getServiceID(), new ArrayList<GroupURI>());
                    }
                }
            }
            for (GroupURI next : memberships) {
                if (next.equals(group)) {
                    return true;
                }
            }
        }
        return false;
    }

}
