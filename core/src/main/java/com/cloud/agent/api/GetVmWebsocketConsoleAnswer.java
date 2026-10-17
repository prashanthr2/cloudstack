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

import com.cloud.agent.api.LogLevel.Log4jLevel;

/**
 * What the console proxy needs to open a VM's console through a websocket: the websocket URL, the value of the {@code Authorization} header
 * the websocket request must carry, and the password of the VM's VNC server (which the viewer in the browser uses). All three are secrets
 * of a single console session, so none of them is logged.
 */
public class GetVmWebsocketConsoleAnswer extends Answer {
    @LogLevel(Log4jLevel.Off)
    private String url;
    @LogLevel(Log4jLevel.Off)
    private String authorization;
    @LogLevel(Log4jLevel.Off)
    private String password;

    public GetVmWebsocketConsoleAnswer(Command command, String details) {
        super(command, false, details);
    }

    public GetVmWebsocketConsoleAnswer(Command command, String url, String authorization, String password) {
        super(command, true, "");
        this.url = url;
        this.authorization = authorization;
        this.password = password;
    }

    public String getUrl() {
        return url;
    }

    /** The complete value of the Authorization header, for example "PVEAPIToken=user@realm!token=secret". */
    public String getAuthorization() {
        return authorization;
    }

    /** The password the VM's VNC server asks for; may be null when it asks for none. */
    public String getPassword() {
        return password;
    }
}
