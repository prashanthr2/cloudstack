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
 * token does not exist yet when a host is added, and Proxmox shows its secret only when it is created. There is one user for CloudStack ({@value #USER}),
 * and one token per node, with privilege separation, so that a node added or removed later never changes the secret of another. The roles are the
 * ones requirements.md section 3 prescribes, given to the user and to the token (a separated token only gets what both have).
 */
public final class ProxmoxApiAccess {
    public static final String USER = "cloudstack@pve";
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

    /** The name of the token of a node. */
    public static String tokenName(String node) {
        return "cs-" + node;
    }

    /**
     * Makes the user and roles when they are missing, replaces the node's token with a new one and gives it its rights. {@code manageHa} adds the role for
     * Proxmox HA (a wide right, only when CloudStack is to manage HA).
     */
    public static Token create(ProxmoxSsh ssh, String node, boolean manageHa) {
        ensure(ssh, "pveum user add " + USER + " --comment 'CloudStack'", "already exists");
        ensure(ssh, "pveum role add " + NET_ROLE + " --privs 'Sys.Modify'", "already exists");
        if (manageHa) {
            ensure(ssh, "pveum role add " + HA_ROLE + " --privs 'Sys.Audit Sys.Console'", "already exists");
        }
        String name = tokenName(node);
        // a token that is left from an earlier host of the same name has a secret nobody has: it is replaced
        ssh.sudo("pveum user token remove " + USER + " " + name, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        String output = ssh.sudoOrFail("pveum user token add " + USER + " " + name + " --privsep 1 --output-format json");
        Token token = parse(output);
        String id = token.getId();
        grant(ssh, "/", "PVEAuditor", id, true);
        grant(ssh, "/vms", "PVEVMAdmin", id, true);
        grant(ssh, "/storage", "PVEDatastoreAdmin", id, true);
        grant(ssh, "/sdn/zones/localnetwork", "PVESDNUser", id, true);
        grant(ssh, "/nodes/" + node, NET_ROLE, id, false);
        if (manageHa) {
            grant(ssh, "/", HA_ROLE, id, false);
        }
        return token;
    }

    /** The name of the short-lived token that reads the cluster while a host is being added (it has no other right than reading). */
    public static final String DISCOVERY_TOKEN = "cs-discovery";

    /**
     * A token that can only read, to find the nodes of the cluster when a host is added: the tokens of the nodes are made only for the nodes that are
     * added, and the token of a node that is added already must not be replaced, since its secret is in use. Remove it with {@link #revokeToken}.
     */
    public static Token createDiscoveryToken(ProxmoxSsh ssh) {
        ensure(ssh, "pveum user add " + USER + " --comment 'CloudStack'", "already exists");
        ssh.sudo("pveum user token remove " + USER + " " + DISCOVERY_TOKEN, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        Token token = parse(ssh.sudoOrFail("pveum user token add " + USER + " " + DISCOVERY_TOKEN + " --privsep 1 --output-format json"));
        grant(ssh, "/", "PVEAuditor", token.getId(), true);
        return token;
    }

    /** Removes the node's token (the user and the roles stay, other nodes use them). A token that is gone already is not an error. */
    public static void revoke(ProxmoxSsh ssh, String node) {
        revokeToken(ssh, tokenName(node));
    }

    public static void revokeToken(ProxmoxSsh ssh, String name) {
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
