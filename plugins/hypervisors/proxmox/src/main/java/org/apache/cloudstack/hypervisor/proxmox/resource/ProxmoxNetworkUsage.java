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
package org.apache.cloudstack.hypervisor.proxmox.resource;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.Duration;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.NetworkUsageAnswer;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.utils.ExecutionResult;

/**
 * The traffic counters of a router (iptables rules and ip counters in the router, read with {@code netusage.sh} and, for a VPC,
 * {@code vpc_netusage.sh}), as the KVM host does through its router proxy script: here the script runs in the router through the guest agent.
 */
final class ProxmoxNetworkUsage {
    private static final Duration TIMEOUT = Duration.standardSeconds(120);

    private ProxmoxNetworkUsage() {
    }

    static Answer execute(NetworkUsageCommand cmd, ProxmoxRouterDeployer deployer) {
        deployer.register(cmd.getPrivateIP(), cmd.getDomRName());
        String option = cmd.getOption();
        if (cmd.isForVpc()) {
            if ("create".equals(option)) {
                return new NetworkUsageAnswer(cmd, vpcUsage(deployer, cmd.getPrivateIP(), cmd.getGatewayIP(), "create", cmd.getVpcCIDR()), 0L, 0L);
            } else if ("get".equals(option) || "vpn".equals(option)) {
                long[] stats = stats(vpcUsage(deployer, cmd.getPrivateIP(), cmd.getGatewayIP(), option, null));
                return new NetworkUsageAnswer(cmd, "", stats[0], stats[1]);
            }
            return new NetworkUsageAnswer(cmd, vpcUsage(deployer, cmd.getPrivateIP(), cmd.getGatewayIP(), option, cmd.getVpcCIDR()), 0L, 0L);
        }
        if ("create".equals(option)) {
            return new NetworkUsageAnswer(cmd, usage(deployer, cmd.getPrivateIP(), "create", null, null), 0L, 0L);
        }
        long[] stats = stats(usage(deployer, cmd.getPrivateIP(), "get", null, null));
        return new NetworkUsageAnswer(cmd, "", stats[0], stats[1]);
    }

    /** The arguments of {@code netusage.sh} for an option, as the KVM host builds them; null for an unknown option. */
    static String usageArguments(String option, String vif, String publicIp) {
        switch (option) {
            case "get":
                return "-g" + (StringUtils.isNotEmpty(publicIp) ? " -l " + publicIp : "");
            case "create":
                return "-c";
            case "reset":
                return "-r";
            case "addVif":
                return "-a " + vif;
            case "deleteVif":
                return "-d " + vif;
            default:
                return null;
        }
    }

    /** The output line of the script (its last), or null when it failed. */
    static String usage(ProxmoxRouterDeployer deployer, String routerIp, String option, String vif, String publicIp) {
        String arguments = usageArguments(option, vif, publicIp);
        return arguments == null ? null : lastLine(deployer.executeInVR(routerIp, "netusage.sh", arguments, TIMEOUT));
    }

    static String vpcUsageArguments(String publicIp, String option, String vpcCidr) {
        StringBuilder arguments = new StringBuilder("-l ").append(publicIp);
        switch (option) {
            case "get":
                arguments.append(" -g");
                break;
            case "create":
                arguments.append(" -c -v ").append(vpcCidr);
                break;
            case "reset":
                arguments.append(" -r");
                break;
            case "vpn":
                arguments.append(" -n");
                break;
            case "remove":
                arguments.append(" -d");
                break;
            default:
                break;
        }
        return arguments.toString();
    }

    static String vpcUsage(ProxmoxRouterDeployer deployer, String routerIp, String publicIp, String option, String vpcCidr) {
        return lastLine(deployer.executeInVR(routerIp, "vpc_netusage.sh", vpcUsageArguments(publicIp, option, vpcCidr), TIMEOUT));
    }

    private static String lastLine(ExecutionResult result) {
        if (!result.isSuccess()) {
            return null;
        }
        String last = null;
        for (String line : StringUtils.defaultString(result.getDetails()).split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                last = line.trim();
            }
        }
        return last;
    }

    /** "sent:received:sent:received ..." summed per direction, as the script prints it; zeros when there is nothing. */
    static long[] stats(String output) {
        long[] stats = new long[2];
        if (output != null) {
            String[] parts = output.split(":");
            for (int i = 0; i + 1 < parts.length; i += 2) {
                stats[0] += Long.parseLong(parts[i].trim());
                stats[1] += Long.parseLong(parts[i + 1].trim());
            }
        }
        return stats;
    }
}
