// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.proxmox.ssh;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Creates and removes the API user and token that the plugin uses, with the node's own tools over SSH (design-phase10-ssh-access.md section 2): the
 * token does not exist yet when a host is added, and Proxmox shows its secret only when it is created. There is one user for CloudStack ({@value #USER})
 * and one token for the Proxmox cluster, with privilege separation; the plugin keeps it with the CloudStack cluster. The roles are the ones
 * requirements.md section 3 prescribes, given to the user and to the token (a separated token only gets what both have).
 */
public final class ProxmoxApiAccess {
    public static final String USER = "cloudstack@pve";
    static final String AUDIT_ROLE = "PVEAuditor";
    static final String NET_ROLE = "CloudStackNet";
    static final String HA_ROLE = "CloudStackHA";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A token as the API client needs it: the id ("cloudstack@pve!cs-pve1") and the secret. */
    public static final class Token {
        private final String id;
        private final String secret;

        Token(String id, String secret) {
            this.id = id;
            this.secret = secret;
        }

        public String getId() {
            return id;
        }

        public String getSecret() {
            return secret;
        }
    }

    private ProxmoxApiAccess() {
    }

    /** The name of the token of a Proxmox cluster (of the node, for a node that is not in a cluster). */
    public static String tokenName(String clusterName) {
        return "cs-" + clusterName;
    }

    /**
     * Makes the user and roles when they are missing, replaces the cluster's token with a new one and gives it its rights. Users and tokens belong to the
     * whole Proxmox cluster, so one token serves every node. The role for Proxmox HA ({@code Sys.Audit} and {@code Sys.Console}) is always given, on the
     * root path without propagation, so that turning on the setting proxmox.ha.manage later needs no new token: HA checks the root path itself, while a
     * console on a node is checked on the node's own path, which that grant does not reach (requirements.md section 3). The right to change a node's
     * network is given per node, with {@link #grantNode}, as nodes are added.
     */
    public static Token createClusterToken(ProxmoxSsh ssh, String clusterName) {
        ensure(ssh, "pveum user add " + USER + " --comment 'CloudStack'", "already exists");
        ensure(ssh, "pveum role add " + NET_ROLE + " --privs 'Sys.Modify'", "already exists");
        ensure(ssh, "pveum role add " + HA_ROLE + " --privs 'Sys.Audit Sys.Console'", "already exists");
        String name = tokenName(clusterName);
        // a token that is left from an earlier cluster of the same name has a secret nobody has: it is replaced
        ssh.sudo("pveum user token remove " + USER + " " + name, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        Token token = parse(ssh.sudoOrFail("pveum user token add " + USER + " " + name + " --privsep 1 --output-format json"));
        String id = token.getId();
        grant(ssh, "/", AUDIT_ROLE, id, true);
        grant(ssh, "/vms", "PVEVMAdmin", id, true);
        grant(ssh, "/storage", "PVEDatastoreAdmin", id, true);
        grant(ssh, "/sdn/zones/localnetwork", "PVESDNUser", id, true);
        grant(ssh, "/", HA_ROLE, id, false);
        return token;
    }

    /**
     * Lets the token change the network of one node (to make the control bridge), on the node's own path without propagation. Proxmox does not add
     * the roles of a path to the ones it inherits: the roles given on a path replace them. The node path therefore gets the audit role again, or the
     * token could no longer read the node's status.
     */
    public static void grantNode(ProxmoxSsh ssh, String tokenId, String node) {
        grant(ssh, "/nodes/" + node, AUDIT_ROLE + "," + NET_ROLE, tokenId, false);
    }

    /** Removes the token by its id ("cloudstack@pve!cs-lab"); the user and the roles stay. A token that is gone already is not an error. */
    public static void revokeToken(ProxmoxSsh ssh, String tokenId) {
        String name = StringUtils.substringAfter(tokenId, "!");
        ExecResult result = ssh.sudo("pveum user token remove " + USER + " " + name, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess() && !result.describeFailure().contains("no such token") && !result.describeFailure().contains("does not exist")) {
            throw new ProxmoxSshException("The token " + name + " could not be removed: " + result.describeFailure());
        }
    }

    static Token parse(String output) {
        try {
            JsonNode json = MAPPER.readTree(output);
            String id = json.path("full-tokenid").asText("");
            String secret = json.path("value").asText("");
            if (id.isEmpty() || secret.isEmpty()) {
                throw new ProxmoxSshException("Proxmox did not print the token and its secret: " + StringUtils.abbreviate(output, 200));
            }
            return new Token(id, secret);
        } catch (IOException e) {
            throw new ProxmoxSshException("Proxmox printed something that is not a token: " + StringUtils.abbreviate(output, 200), e);
        }
    }

    private static void grant(ProxmoxSsh ssh, String path, String role, String tokenId, boolean propagate) {
        ssh.sudoOrFail("pveum acl modify " + path + " --users " + USER + " --tokens " + ProxmoxSsh.quote(tokenId) + " --roles " + role
                + (propagate ? "" : " --propagate 0"));
    }

    /** Runs a command that may fail only because the thing exists already. */
    private static void ensure(ProxmoxSsh ssh, String command, String alreadyThere) {
        ExecResult result = ssh.sudo(command, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess() && !result.describeFailure().contains(alreadyThere)) {
            throw new ProxmoxSshException(String.format("The command \"%s\" failed: %s", command, result.describeFailure()));
        }
    }
}
