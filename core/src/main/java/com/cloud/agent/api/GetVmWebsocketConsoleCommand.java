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
package com.cloud.agent.api;

/**
 * Asks the host of a VM for a console that is reached through an authenticated websocket (a hypervisor whose console cannot be reached
 * with a plain VNC connection to a host and port). The answer is one-time: it has to be asked for each time a console is opened.
 */
public class GetVmWebsocketConsoleCommand extends Command {
    private final String vmName;

    public GetVmWebsocketConsoleCommand(String vmName) {
        this.vmName = vmName;
    }

    public String getVmName() {
        return vmName;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
