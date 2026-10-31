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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A node for tests: it accepts any login, shows one host key, and answers commands with what the test scripts. */
public class ScriptedSshLink implements SshLink {
    public String fingerprint = "SHA256:x5aXc/0jcbNxnAqPbL1diu0q8h1/iGBrnkMtBOCZHdY";
    public boolean acceptsKey = true;
    public boolean acceptsPassword = true;
    public final List<String> commands = new ArrayList<>();
    public Function<String, ExecResult> answers = command -> new ExecResult(0, "", "");
    public boolean closed;

    @Override
    public void connect(int timeoutMillis, HostKeyCheck check) throws IOException {
        if (!check.accept(fingerprint)) {
            throw new IOException("the host key was refused");
        }
    }

    @Override
    public boolean authenticateWithKey(String user, String privateKeyPem) {
        return acceptsKey;
    }

    @Override
    public boolean authenticateWithPassword(String user, String password) {
        return acceptsPassword;
    }

    @Override
    public ExecResult exec(String command, byte[] stdin, int timeoutMillis) {
        commands.add(command);
        return answers.apply(command);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** The commands that contain the text. */
    public List<String> commandsWith(String text) {
        List<String> found = new ArrayList<>();
        for (String command : commands) {
            if (command.contains(text)) {
                found.add(command);
            }
        }
        return found;
    }
}
